/**
 * BreadWallet
 *
 * Created by Drew Carlson <drew.carlson@breadwallet.com> on 9/10/19.
 * Copyright (c) 2019 breadwallet LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cryptron.breadbox

import com.cryptron.app.BreadApp
import com.breadwallet.crypto.Account
import com.breadwallet.crypto.Key
import com.breadwallet.crypto.Network
import com.breadwallet.crypto.System
import com.breadwallet.crypto.Transfer
import com.breadwallet.crypto.Wallet
import com.breadwallet.crypto.WalletManager
import com.breadwallet.crypto.WalletManagerState
import com.breadwallet.crypto.blockchaindb.BlockchainDb
import com.breadwallet.crypto.events.network.NetworkEvent
import com.breadwallet.crypto.events.system.SystemEvent
import com.breadwallet.crypto.events.system.SystemListener
import com.breadwallet.crypto.events.system.SystemNetworkAddedEvent
import com.breadwallet.crypto.events.transfer.TranferEvent
import com.breadwallet.crypto.events.wallet.WalletEvent
import com.breadwallet.crypto.events.wallet.WalletTransferAddedEvent
import com.breadwallet.crypto.events.wallet.WalletTransferChangedEvent
import com.breadwallet.crypto.events.wallet.WalletTransferDeletedEvent
import com.breadwallet.crypto.events.wallet.WalletTransferSubmittedEvent
import com.breadwallet.crypto.events.walletmanager.WalletManagerChangedEvent
import com.breadwallet.crypto.events.walletmanager.WalletManagerCreatedEvent
import com.breadwallet.crypto.events.walletmanager.WalletManagerEvent
import com.breadwallet.crypto.events.walletmanager.WalletManagerSyncProgressEvent
import com.breadwallet.crypto.events.walletmanager.WalletManagerSyncRecommendedEvent
import com.cryptron.ext.throttleLatest
import com.cryptron.logger.logDebug
import com.cryptron.logger.logInfo
import com.cryptron.tools.manager.BRSharedPrefs
import com.cryptron.tools.security.BrdUserManager
import com.cryptron.tools.util.Bip39Reader
import com.cryptron.tools.util.TokenUtil
import com.cryptron.util.errorHandler
import com.platform.interfaces.WalletProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val DEFAULT_THROTTLE_MS = 100L
private const val AGGRESSIVE_THROTTLE_MS = 300L

@Suppress("TooManyFunctions")
internal class CoreBreadBox(
    private val storageFile: File,
    private val isMainnet: Boolean = false,
    private val walletProvider: WalletProvider,
    private val blockchainDb: BlockchainDb,
    private val userManager: BrdUserManager
) : BreadBox,
    SystemListener {

    init {
        // Set default words list
        val context = BreadApp.getBreadContext()
        val words = com.cryptron.tools.util.Bip39Reader.getBip39Words(context, BRSharedPrefs.recoveryKeyLanguage)
        Key.setDefaultWordList(words)
    }

    private var system: System? = null
    private val systemExecutor = Executors.newSingleThreadScheduledExecutor()

    private val openScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + errorHandler("openScope")
    )

    private val systemChannel = BroadcastChannel<System>(BUFFERED)
    private val accountChannel = BroadcastChannel<Account>(BUFFERED)
    private val walletsChannel = BroadcastChannel<List<Wallet>>(BUFFERED)
    private val walletSyncStateChannel = BroadcastChannel<WalletSyncState>(BUFFERED)

    private val walletTransfersChannelMap = createChannelMap<String, List<Transfer>>()
    private val transferUpdatedChannelMap = createChannelMap<String, Transfer>()

    private var networkManager: NetworkManager? = null

    private fun <K, V> createChannelMap(): MutableMap<K, BroadcastChannel<V>> =
        mutableMapOf<K, BroadcastChannel<V>>().run {
            withDefault { key -> getOrPut(key) { BroadcastChannel(BUFFERED) } }
        }

    private val _isOpen = AtomicBoolean(false)
    override var isOpen: Boolean
        get() = _isOpen.get()
        set(value) {
            _isOpen.set(value)
        }

    @Synchronized
    override fun open(account: Account) {
        logDebug("Opening CoreBreadBox")

        check(!isOpen) { "open() called while BreadBox was open." }
        check(account.serialize().isNotEmpty()) { "Account.serialize() contains 0 bytes" }

        if (!storageFile.exists()) {
            logDebug("Making storage directories")
            check(storageFile.mkdirs()) {
                "Failed to create storage directory: ${storageFile.absolutePath}"
            }
        }

        fun newSystem() = System.create(
            systemExecutor,
            this@CoreBreadBox,
            account,
            isMainnet,
            storageFile.absolutePath,
            blockchainDb
        ).apply {
            logDebug("Created new System instance")
            configure(emptyList())
        }

        system = (system ?: newSystem()).also { system ->
            check(system.account.serialize()!!.contentEquals(account.serialize())) {
                "Provided Account does not match existing System Account, " +
                    "CoreBreadBox does not support swapping accounts at runtime."
            }

            logDebug("Dispatching initial System values")

            system.connectAll()

            networkManager = NetworkManager(
                system,
                openScope,
                listOf(DefaultNetworkInitializer(userManager))
            )
            systemChannel.offer(system)
            accountChannel.offer(system.account)
            system.wallets?.let { wallets ->
                walletsChannel.offer(wallets)
                wallets.forEach {
                    walletTransfersChannelMap
                        .getValue(it.currency.code.toLowerCase(Locale.ROOT))
                        .offer(it.transfers)
                }
            }
        }

        isOpen = true

        walletProvider
            .enabledWallets()
            .onEach { enabledWallets ->
                networkManager?.enabledWallets = enabledWallets
                system?.wallets?.let {
                    walletsChannel.offer(it)
                }
            }
            .launchIn(openScope)

        walletProvider
            .walletModes()
            .onEach { modes ->
                networkManager?.managerModes = modes
                system?.wallets?.let {
                    walletsChannel.offer(it)
                }
            }
            .launchIn(openScope)

        logInfo("BreadBox opened successfully")
    }

    @Synchronized
    override fun close() {
        logDebug("Closing BreadBox")

        check(isOpen) { "BreadBox must be opened before calling close()." }

        openScope.coroutineContext.cancelChildren()

        checkNotNull(system).disconnectAll()

        isOpen = false

        networkManager = null
    }

    override fun system(): Flow<System> =
        systemChannel
            .asFlow()
            .throttleLatest(DEFAULT_THROTTLE_MS)
            .fromSystemOnStart { it }

    override fun account() =
        accountChannel
            .asFlow()
            .throttleLatest(DEFAULT_THROTTLE_MS)
            .fromSystemOnStart(System::getAccount)

    override fun wallets(filterByTracked: Boolean) =
        walletsChannel
            .asFlow()
            .throttleLatest(DEFAULT_THROTTLE_MS)
            .fromSystemOnStart(System::getWallets)
            .mapLatest { wallets ->
                when {
                    filterByTracked -> {
                        wallets.filterByCurrencyIds(
                            walletProvider.enabledWallets().first()
                        )
                    }
                    else -> wallets
                }
            }
            .distinctUntilChangedBy { wallets ->
                wallets.map { it.currency.code }
            }

    override fun wallet(currencyCode: String) =
        walletsChannel
            .asFlow()
            .throttleLatest(DEFAULT_THROTTLE_MS)
            .fromSystemOnStart(System::getWallets)
            .mapLatest { wallets ->
                wallets.firstOrNull {
                    it.currency.code.equals(currencyCode, true)
                }
            }
            .filterNotNull()

    override fun currencyCodes(): Flow<List<String>> =
        combine(
            walletProvider.enabledWallets().throttleLatest(AGGRESSIVE_THROTTLE_MS),
            walletsChannel.asFlow().throttleLatest(AGGRESSIVE_THROTTLE_MS)
        ) { enabledWallets, wallets ->
            enabledWallets
                .associateWith { wallets.findByCurrencyId(it) }
                .mapValues { (currencyId, wallet) ->
                    wallet?.currency?.code ?: com.cryptron.tools.util.TokenUtil.getTokenItemForCurrencyId(currencyId)
                        ?.symbol?.toLowerCase(Locale.ROOT)
                }.values
                .filterNotNull()
                .toList()
        }

    override fun walletSyncState(currencyCode: String) =
        walletSyncStateChannel
            .asFlow()
            .throttleLatest(DEFAULT_THROTTLE_MS)
            .filter { it.currencyCode.equals(currencyCode, true) }
            .onStart {
                // Dispatch initial sync state
                val isSyncing = wallet(currencyCode)
                    .map { it.walletManager.state.type == WalletManagerState.Type.SYNCING }
                    .first()
                emit(
                    WalletSyncState(
                        currencyCode = currencyCode,
                        percentComplete = if (isSyncing) 0f else 1f,
                        timestamp = 0,
                        isSyncing = isSyncing
                    )
                )
            }
            .distinctUntilChanged()

    @Synchronized
    override fun walletTransfers(currencyCode: String) =
        walletTransfersChannelMap.getValue(currencyCode.toLowerCase(Locale.ROOT))
            .asFlow()
            .throttleLatest(AGGRESSIVE_THROTTLE_MS)
            .fromSystemOnStart { system ->
                system.wallets
                    .find { it.currency.code.equals(currencyCode, true) }
                    ?.transfers
            }

    @Synchronized
    override fun walletTransfer(currencyCode: String, transferHash: String) =
        transferUpdatedChannelMap.getValue(currencyCode.toLowerCase(Locale.ROOT))
            .asFlow()
            .throttleLatest(AGGRESSIVE_THROTTLE_MS)
            .filter { it.hashString() == transferHash }
            .fromSystemOnStart { system ->
                system.wallets
                    .find { it.currency.code.equals(currencyCode, true) }
                    ?.transfers
                    ?.singleOrNull { it.hashString() == transferHash }
            }

    @Synchronized
    override fun walletTransferUpdates(currencyCode: String): Flow<Transfer> =
        transferUpdatedChannelMap.getValue(currencyCode)
            .asFlow()
            .throttleLatest(AGGRESSIVE_THROTTLE_MS)

    override fun initializeWallet(currencyCode: String) {
        check(isOpen) { "initializeWallet cannot be called before open." }
        val system = checkNotNull(system)
        val networkManager = checkNotNull(networkManager)
        val network = system.networks.find { it.containsCurrencyCode(currencyCode) }
        checkNotNull(network) {
            "Network with currency code '$currencyCode' not found."
        }
        openScope.launch {
            networkManager.completeNetworkInitialization(network.currency.uids)
        }
    }

    override fun walletState(currencyCode: String): Flow<WalletState> =
        system().flatMapLatest { system ->
            val network = system.networks.find { it.containsCurrencyCode(currencyCode) }
            val uids = checkNotNull(
                network?.currency?.uids
                    ?: com.cryptron.tools.util.TokenUtil.getTokenItemByCurrencyCode(currencyCode)?.currencyId
            ) { "Currency Id not found for '$currencyCode'" }
            checkNotNull(networkManager).networkState(uids).map { networkState ->
                when (networkState) {
                    is NetworkState.Initialized -> WalletState.Initialized
                    is NetworkState.Loading -> WalletState.Loading
                    is NetworkState.ActionNeeded -> WalletState.WaitingOnAction
                    is NetworkState.Error -> WalletState.Error
                }
            }
        }

    @Synchronized
    override fun getSystemUnsafe(): System? = system

    @Synchronized
    override fun handleWalletEvent(
        system: System,
        manager: WalletManager,
        wallet: Wallet,
        event: WalletEvent
    ) {
        walletsChannel.offer(system.wallets)

        @Synchronized
        fun updateTransfer(transfer: Transfer) {
            walletTransfersChannelMap
                .getValue(wallet.currency.code)
                .offer(wallet.transfers)
            transferUpdatedChannelMap
                .getValue(wallet.currency.code)
                .offer(transfer)
        }

        when (event) {
            is WalletTransferSubmittedEvent ->
                updateTransfer(event.transfer)
            is WalletTransferDeletedEvent ->
                updateTransfer(event.transfer)
            is WalletTransferAddedEvent ->
                updateTransfer(event.transfer)
            is WalletTransferChangedEvent ->
                updateTransfer(event.transfer)
        }
    }

    @Synchronized
    override fun handleManagerEvent(
        system: System,
        manager: WalletManager,
        event: WalletManagerEvent
    ) {
        walletsChannel.offer(system.wallets)

        when (event) {
            is WalletManagerCreatedEvent -> {
                logDebug("Wallet Manager Created: '${manager.name}' mode ${manager.mode}")
                networkManager?.connectManager(manager)
            }
            is WalletManagerSyncProgressEvent -> {
                val timeStamp = event.timestamp?.get()?.time
                logDebug("(${manager.currency.code}) Sync Progress progress=${event.percentComplete} time=$timeStamp")
                // NOTE: Fulfill percentComplete fractional expectation of consumers
                walletSyncStateChannel.offer(
                    WalletSyncState(
                        currencyCode = manager.currency.code,
                        percentComplete = event.percentComplete / 100,
                        timestamp = event.timestamp.orNull()?.time ?: 0L,
                        isSyncing = true
                    )
                )
            }
            is WalletManagerChangedEvent -> {
                val fromStateType = event.oldState.type
                val toStateType = event.newState.type
                logDebug("(${manager.currency.code}) State Changed from='$fromStateType' to='$toStateType'")

                // Syncing is complete, manually signal change to observers
                if (fromStateType == WalletManagerState.Type.SYNCING) {
                    walletSyncStateChannel.offer(
                        WalletSyncState(
                            currencyCode = manager.currency.code,
                            percentComplete = 1f,
                            timestamp = 0L,
                            isSyncing = false
                        )
                    )
                }

                if (toStateType == WalletManagerState.Type.SYNCING) {
                    walletSyncStateChannel.offer(
                        WalletSyncState(
                            currencyCode = manager.currency.code,
                            percentComplete = 0f,
                            timestamp = 0L,
                            isSyncing = true
                        )
                    )
                }

                if (fromStateType != WalletManagerState.Type.CONNECTED &&
                    toStateType == WalletManagerState.Type.CONNECTED
                ) {
                    logDebug("Wallet Manager Connected: '${manager.name}'")
                    networkManager?.registerCurrencies(manager)
                }
            }
            is WalletManagerSyncRecommendedEvent -> {
                logDebug("Syncing '${manager.currency.code}' to ${event.depth}")
                manager.syncToDepth(event.depth)
            }
        }
    }

    override fun handleNetworkEvent(system: System, network: Network, event: NetworkEvent) = Unit

    override fun handleSystemEvent(system: System, event: SystemEvent) {
        if (event is SystemNetworkAddedEvent) {
            logDebug("Network '${event.network.name}' added.")
            networkManager?.initializeNetwork(event.network)
        }
    }

    override fun handleTransferEvent(
        system: System,
        manager: WalletManager,
        wallet: Wallet,
        transfer: Transfer,
        event: TranferEvent
    ) {
        synchronized(this) {
            transferUpdatedChannelMap.getValue(wallet.currency.code).offer(transfer)
            walletTransfersChannelMap.getValue(wallet.currency.code).offer(wallet.transfers)
        }
    }

    /** Emit's the result of [extract] when [system] and the result value are not null */
    private fun <T> Flow<T>.fromSystemOnStart(extract: (System) -> T?) = onStart {
        if (isOpen) {
            emit(system?.run(extract) ?: return@onStart)
        }
    }
}
