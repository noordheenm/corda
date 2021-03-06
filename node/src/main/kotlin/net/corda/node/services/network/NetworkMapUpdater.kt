package net.corda.node.services.network

import com.google.common.util.concurrent.MoreExecutors
import net.corda.core.CordaRuntimeException
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.SignedData
import net.corda.core.internal.*
import net.corda.core.messaging.DataFeed
import net.corda.core.messaging.ParametersUpdateInfo
import net.corda.core.node.NetworkParameters
import net.corda.core.node.services.KeyManagementService
import net.corda.core.serialization.serialize
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.minutes
import net.corda.node.services.api.NetworkMapCacheInternal
import net.corda.node.services.config.NetworkParameterAcceptanceSettings
import net.corda.node.utilities.NamedThreadFactory
import net.corda.nodeapi.exceptions.OutdatedNetworkParameterHashException
import net.corda.nodeapi.internal.SignedNodeInfo
import net.corda.nodeapi.internal.network.*
import rx.Subscription
import rx.subjects.PublishSubject
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.*
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class NetworkMapUpdater(private val networkMapCache: NetworkMapCacheInternal,
                        private val nodeInfoWatcher: NodeInfoWatcher,
                        private val networkMapClient: NetworkMapClient?,
                        private val baseDirectory: Path,
                        private val extraNetworkMapKeys: List<UUID>
) : AutoCloseable {
    companion object {
        private val logger = contextLogger()
        private val defaultRetryInterval = 1.minutes
    }

    private val parametersUpdatesTrack = PublishSubject.create<ParametersUpdateInfo>()
    private val networkMapPoller = ScheduledThreadPoolExecutor(1, NamedThreadFactory("Network Map Updater Thread")).apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }
    private var newNetworkParameters: Pair<ParametersUpdate, SignedNetworkParameters>? = null
    private var fileWatcherSubscription: Subscription? = null
    private var autoAcceptNetworkParameters: Boolean = true
    private lateinit var trustRoot: X509Certificate
    private lateinit var currentParametersHash: SecureHash
    private lateinit var ourNodeInfo: SignedNodeInfo
    private lateinit var ourNodeInfoHash: SecureHash
    private lateinit var networkParameters: NetworkParameters
    private lateinit var keyManagementService: KeyManagementService
    private lateinit var excludedAutoAcceptNetworkParameters: Set<String>

    override fun close() {
        fileWatcherSubscription?.unsubscribe()
        MoreExecutors.shutdownAndAwaitTermination(networkMapPoller, 50, TimeUnit.SECONDS)
    }

    fun start(trustRoot: X509Certificate,
              currentParametersHash: SecureHash,
              ourNodeInfo: SignedNodeInfo,
              networkParameters: NetworkParameters,
              keyManagementService: KeyManagementService,
              networkParameterAcceptanceSettings: NetworkParameterAcceptanceSettings) {
        require(fileWatcherSubscription == null) { "Should not call this method twice." }
        this.trustRoot = trustRoot
        this.currentParametersHash = currentParametersHash
        this.ourNodeInfo = ourNodeInfo
        this.ourNodeInfoHash = ourNodeInfo.raw.hash
        this.networkParameters = networkParameters
        this.keyManagementService = keyManagementService
        this.autoAcceptNetworkParameters = networkParameterAcceptanceSettings.autoAcceptEnabled
        this.excludedAutoAcceptNetworkParameters = networkParameterAcceptanceSettings.excludedAutoAcceptableParameters

        val autoAcceptNetworkParametersNames = NetworkParameters.autoAcceptablePropertyNames - excludedAutoAcceptNetworkParameters
        if (autoAcceptNetworkParameters && autoAcceptNetworkParametersNames.isNotEmpty()) {
            logger.info("Auto-accept enabled for network parameter changes which modify only: $autoAcceptNetworkParametersNames")
        }
        watchForNodeInfoFiles()
        if (networkMapClient != null) {
            watchHttpNetworkMap()
        }
    }

    private fun watchForNodeInfoFiles() {
        nodeInfoWatcher
                .nodeInfoUpdates()
                .subscribe {
                    for (update in it) {
                        when (update) {
                            is NodeInfoUpdate.Add -> networkMapCache.addNode(update.nodeInfo)
                            is NodeInfoUpdate.Remove -> {
                                if (update.hash != ourNodeInfoHash) {
                                    val nodeInfo = networkMapCache.getNodeByHash(update.hash)
                                    nodeInfo?.let(networkMapCache::removeNode)
                                }
                            }
                        }
                    }
                    if (networkMapClient == null) {
                        // Mark the network map cache as ready on a successful poll of the node infos dir if not using
                        // the HTTP network map even if there aren't any node infos
                        networkMapCache.nodeReady.set(null)
                    }
                }
    }

    private fun watchHttpNetworkMap() {
        // The check may be expensive, so always run it in the background even the first time.
        networkMapPoller.submit(object : Runnable {
            override fun run() {
                val nextScheduleDelay = try {
                    updateNetworkMapCache()
                } catch (e: Exception) {
                    logger.warn("Error encountered while updating network map, will retry in $defaultRetryInterval", e)
                    defaultRetryInterval
                }
                // Schedule the next update.
                networkMapPoller.schedule(this, nextScheduleDelay.toMillis(), TimeUnit.MILLISECONDS)
            }
        })
    }

    fun trackParametersUpdate(): DataFeed<ParametersUpdateInfo?, ParametersUpdateInfo> {
        val currentUpdateInfo = newNetworkParameters?.let {
            ParametersUpdateInfo(it.first.newParametersHash, it.second.verified(), it.first.description, it.first.updateDeadline)
        }
        return DataFeed(currentUpdateInfo, parametersUpdatesTrack)
    }

    fun updateNetworkMapCache(): Duration {
        if (networkMapClient == null) {
            throw CordaRuntimeException("Network map cache can be updated only if network map/compatibility zone URL is specified")
        }

        val (globalNetworkMap, cacheTimeout) = networkMapClient.getNetworkMap()
        globalNetworkMap.parametersUpdate?.let { handleUpdateNetworkParameters(networkMapClient, it) }

        val additionalHashes = extraNetworkMapKeys.flatMap {
            try {
                networkMapClient.getNetworkMap(it).payload.nodeInfoHashes
            } catch (e: Exception) {
                // Failure to retrieve one network map using UUID shouldn't stop the whole update.
                logger.warn("Error encountered when downloading network map with uuid '$it', skipping...", e)
                emptyList<SecureHash>()
            }
        }

        val allHashesFromNetworkMap = (globalNetworkMap.nodeInfoHashes + additionalHashes).toSet()

        if (currentParametersHash != globalNetworkMap.networkParameterHash) {
            exitOnParametersMismatch(globalNetworkMap)
        }

        val currentNodeHashes = networkMapCache.allNodeHashes

        // Remove node info from network map.
        (currentNodeHashes - allHashesFromNetworkMap - nodeInfoWatcher.processedNodeInfoHashes)
                .mapNotNull { if (it != ourNodeInfoHash) networkMapCache.getNodeByHash(it) else null }
                .forEach(networkMapCache::removeNode)

        (allHashesFromNetworkMap - currentNodeHashes).mapNotNull {
            // Download new node info from network map
            try {
                networkMapClient.getNodeInfo(it)
            } catch (e: Exception) {
                // Failure to retrieve one node info shouldn't stop the whole update, log and return null instead.
                logger.warn("Error encountered when downloading node info '$it', skipping...", e)
                null
            }
        }.forEach {
            // Add new node info to the network map cache, these could be new node info or modification of node info for existing nodes.
            networkMapCache.addNode(it)
        }

        // Mark the network map cache as ready on a successful poll of the HTTP network map, even on the odd chance that
        // it's empty
        networkMapCache.nodeReady.set(null)

        return cacheTimeout
    }

    private fun exitOnParametersMismatch(networkMap: NetworkMap) {
        val updatesFile = baseDirectory / NETWORK_PARAMS_UPDATE_FILE_NAME
        val acceptedHash = if (updatesFile.exists()) updatesFile.readObject<SignedNetworkParameters>().raw.hash else null
        val exitCode = if (acceptedHash == networkMap.networkParameterHash) {
            logger.info("Flag day occurred. Network map switched to the new network parameters: " +
                    "${networkMap.networkParameterHash}. Node will shutdown now and needs to be started again.")
            0
        } else {
            // TODO This needs special handling (node omitted update process or didn't accept new parameters)
            logger.error(
                    """Node is using network parameters with hash $currentParametersHash but the network map is advertising ${networkMap.networkParameterHash}.
To resolve this mismatch, and move to the current parameters, delete the $NETWORK_PARAMS_FILE_NAME file from the node's directory and restart.
The node will shutdown now.""")
            1
        }
        exitProcess(exitCode)
    }

    private fun handleUpdateNetworkParameters(networkMapClient: NetworkMapClient, update: ParametersUpdate) {
        if (update.newParametersHash == newNetworkParameters?.first?.newParametersHash) {
            // This update was handled already.
            return
        }
        val newSignedNetParams = networkMapClient.getNetworkParameters(update.newParametersHash)
        val newNetParams = newSignedNetParams.verifiedNetworkParametersCert(trustRoot)
        logger.info("Downloaded new network parameters: $newNetParams from the update: $update")
        newNetworkParameters = Pair(update, newSignedNetParams)
        val updateInfo = ParametersUpdateInfo(
                update.newParametersHash,
                newNetParams,
                update.description,
                update.updateDeadline)

        if (autoAcceptNetworkParameters && networkParameters.canAutoAccept(newNetParams, excludedAutoAcceptNetworkParameters)) {
            logger.info("Auto-accepting network parameter update ${update.newParametersHash}")
            acceptNewNetworkParameters(update.newParametersHash) { hash ->
                hash.serialize().sign { keyManagementService.sign(it.bytes, ourNodeInfo.verified().legalIdentities[0].owningKey) }
            }
        } else {
            parametersUpdatesTrack.onNext(updateInfo)
        }
    }

    fun acceptNewNetworkParameters(parametersHash: SecureHash, sign: (SecureHash) -> SignedData<SecureHash>) {
        networkMapClient ?: throw IllegalStateException("Network parameters updates are not supported without compatibility zone configured")
        // TODO This scenario will happen if node was restarted and didn't download parameters yet, but we accepted them.
        // Add persisting of newest parameters from update.
        val (update, signedNewNetParams) = requireNotNull(newNetworkParameters) { "Couldn't find parameters update for the hash: $parametersHash" }
        // We should check that we sign the right data structure hash.
        val newNetParams = signedNewNetParams.verifiedNetworkParametersCert(trustRoot)
        val newParametersHash = signedNewNetParams.raw.hash
        if (parametersHash == newParametersHash) {
            // The latest parameters have priority.
            signedNewNetParams.serialize()
                    .open()
                    .copyTo(baseDirectory / NETWORK_PARAMS_UPDATE_FILE_NAME, StandardCopyOption.REPLACE_EXISTING)
            networkMapClient.ackNetworkParametersUpdate(sign(parametersHash))
            logger.info("Accepted network parameter update $update: $newNetParams")
        } else {
            throw OutdatedNetworkParameterHashException(parametersHash, newParametersHash)
        }
    }
}
