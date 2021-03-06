/**
 * BreadWallet
 *
 * Created by Ahsan Butt <ahsan.butt@breadwallet.com> on 8/1/19.
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
package com.cryptron.ui.home

import android.content.Context
import com.cryptron.breadbox.BreadBox
import com.cryptron.breadbox.WalletState
import com.cryptron.breadbox.applyDisplayOrder
import com.cryptron.breadbox.currencyId
import com.cryptron.breadbox.toBigDecimal
import com.breadwallet.crypto.Amount
import com.breadwallet.crypto.WalletManagerState
import com.cryptron.ext.bindConsumerIn
import com.cryptron.ext.throttleLatest
import com.cryptron.model.Experiments
import com.cryptron.repository.ExperimentsRepositoryImpl
import com.cryptron.repository.MessagesRepository
import com.cryptron.repository.RatesRepository
import com.cryptron.tools.manager.BRSharedPrefs
import com.cryptron.tools.sqlite.RatesDataSource
import com.cryptron.tools.util.BRConstants
import com.cryptron.tools.util.CurrencyUtils
import com.cryptron.tools.util.EventUtils
import com.cryptron.tools.util.TokenUtil
import com.cryptron.ui.home.HomeScreen.E
import com.cryptron.ui.home.HomeScreen.F
import com.cryptron.util.errorHandler
import com.platform.interfaces.AccountMetaDataProvider
import com.platform.interfaces.WalletProvider
import com.spotify.mobius.Connection
import com.spotify.mobius.functions.Consumer
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.transform
import java.math.BigDecimal
import java.util.Locale
import com.breadwallet.crypto.Wallet as CryptoWallet

private const val DATA_THROTTLE_MS = 500L

class HomeScreenHandler(
    private val output: Consumer<E>,
    private val context: Context,
    private val breadBox: BreadBox,
    private val walletProvider: WalletProvider,
    private val accountMetaDataProvider: AccountMetaDataProvider
) : Connection<F>,
    CoroutineScope,
    RatesDataSource.OnDataChanged {

    override val coroutineContext = SupervisorJob() + Dispatchers.Default + errorHandler()

    init {
        RatesDataSource.getInstance(context).addOnDataChangedListener(this)
    }

    override fun accept(value: F) {
        when (value) {
            F.LoadWallets -> loadWallets()
            F.LoadIsBuyBellNeeded -> loadIsBuyBellNeeded()
            F.CheckInAppNotification -> checkInAppNotification()
            F.CheckIfShowBuyAndSell -> checkIfShowBuyAndSell()
            is F.RecordPushNotificationOpened -> recordPushNotificationOpened(value.campaignId)
            is F.TrackEvent -> com.cryptron.tools.util.EventUtils.pushEvent(
                value.eventName,
                value.attributes
            )
            is F.UpdateWalletOrder -> {
                accountMetaDataProvider
                    .reorderWallets(value.orderedCurrencyIds)
                    .launchIn(this)
            }
        }
    }

    override fun dispose() {
        RatesDataSource.getInstance(context).removeOnDataChangedListener(this)
        cancel()
    }

    private fun loadWallets() {
        // Load enabled wallets
        walletProvider.enabledWallets()
            .distinctUntilChanged { old, new -> old.containsAll(new) }
            .mapLatest { wallets ->
                wallets.mapNotNull { currencyId ->
                    val token = com.cryptron.tools.util.TokenUtil.getTokenItems(context)
                        .find { currencyId.equals(it.currencyId, true) }
                    if (token == null) {
                        null
                    } else {
                        val currencyCode = token.symbol.toLowerCase(Locale.ROOT)
                        Wallet(
                            currencyId = currencyId,
                            currencyCode = currencyCode,
                            currencyName = token.name,
                            fiatPricePerUnit = getFiatPerPriceUnit(currencyCode),
                            priceChange = getPriceChange(currencyCode),
                            state = when (breadBox.walletState(currencyCode).first()) {
                                WalletState.Loading -> Wallet.State.LOADING
                                else -> Wallet.State.UNINITIALIZED
                            }
                        )
                    }
                }
            }
            .map { E.OnEnabledWalletsUpdated(it) }
            .bindConsumerIn(output, this)

        // Update wallets list
        breadBox.wallets()
            .throttleLatest(DATA_THROTTLE_MS)
            .applyDisplayOrder(walletProvider.enabledWallets())
            .mapLatest { wallets -> wallets.map { it.asWallet() } }
            .map { E.OnWalletsUpdated(it) }
            .bindConsumerIn(output, this)

        // Update wallet balances
        breadBox.currencyCodes()
            .throttleLatest(DATA_THROTTLE_MS)
            .flatMapLatest { currencyCodes ->
                currencyCodes.map { currencyCode ->
                    breadBox.wallet(currencyCode)
                        .distinctUntilChangedBy { it.balance }
                }.merge()
            }
            .map {
                E.OnWalletBalanceUpdated(
                    currencyCode = it.currency.code,
                    balance = it.balance.toBigDecimal(),
                    fiatBalance = getBalanceInFiat(it.balance)
                )
            }
            .bindConsumerIn(output, this)

        // Update wallet sync state
        breadBox.currencyCodes()
            .flatMapLatest { currencyCodes ->
                currencyCodes.map {
                    breadBox.walletSyncState(it)
                        .mapLatest { syncState ->
                            E.OnWalletSyncProgressUpdated(
                                currencyCode = syncState.currencyCode,
                                progress = syncState.percentComplete,
                                syncThroughMillis = syncState.timestamp,
                                isSyncing = syncState.isSyncing
                            )
                        }
                }.merge()
            }
            .bindConsumerIn(output, this)

        updatePriceData()
    }

    private fun loadIsBuyBellNeeded() {
        val isBuyBellNeeded =
            ExperimentsRepositoryImpl.isExperimentActive(Experiments.BUY_NOTIFICATION) &&
                com.cryptron.tools.util.CurrencyUtils.isBuyNotificationNeeded(context)
        output.accept(E.OnBuyBellNeededLoaded(isBuyBellNeeded))
    }

    private fun checkInAppNotification() {
        val notification = MessagesRepository.getInAppNotification(context) ?: return

        // If the notification contains an image we need to pre fetch it to avoid showing the image space empty
        // while we fetch the image while the notification is shown.
        when (notification.imageUrl == null) {
            true -> output.accept(E.OnInAppNotificationProvided(notification))
            false -> {
                Picasso.get().load(notification.imageUrl).fetch(object : Callback {

                    override fun onSuccess() {
                        output.accept(E.OnInAppNotificationProvided(notification))
                    }

                    override fun onError(exception: Exception) {
                    }
                })
            }
        }
    }

    private fun recordPushNotificationOpened(campaignId: String) {
        val attributes = HashMap<String, String>()
        attributes[com.cryptron.tools.util.EventUtils.EVENT_ATTRIBUTE_CAMPAIGN_ID] = campaignId
        com.cryptron.tools.util.EventUtils.pushEvent(com.cryptron.tools.util.EventUtils.EVENT_MIXPANEL_APP_OPEN, attributes)
        com.cryptron.tools.util.EventUtils.pushEvent(com.cryptron.tools.util.EventUtils.EVENT_PUSH_NOTIFICATION_OPEN)
    }

    override fun onChanged() {
        updatePriceData()
        val wallets = breadBox.getSystemUnsafe()?.wallets ?: emptyList()
        wallets.onEach { wallet ->
            updateBalance(wallet.currency.code, wallet.balance)
        }
    }

    private fun updatePriceData() {
        breadBox.currencyCodes()
            .take(1)
            .transform { currencyCodes ->
                currencyCodes.onEach { code ->
                    emit(
                        E.OnUnitPriceChanged(
                            code,
                            getFiatPerPriceUnit(code),
                            getPriceChange(code)
                        )
                    )
                }
            }
            .bindConsumerIn(output, this)
    }

    private fun getFiatPerPriceUnit(currencyCode: String): BigDecimal {
        return RatesRepository.getInstance(context)
            .getFiatForCrypto(
                BigDecimal.ONE,
                currencyCode,
                BRSharedPrefs.getPreferredFiatIso(context)
            )
            ?: BigDecimal.ZERO
    }

    private fun updateBalance(currencyCode: String, balanceAmt: Amount) {
        val balanceInFiat = getBalanceInFiat(balanceAmt)

        output.accept(
            E.OnWalletBalanceUpdated(
                currencyCode,
                balanceAmt.toBigDecimal(),
                balanceInFiat
            )
        )
    }

    private fun getBalanceInFiat(balanceAmt: Amount): BigDecimal {
        return RatesRepository.getInstance(context).getFiatForCrypto(
            balanceAmt.toBigDecimal(),
            balanceAmt.currency.code,
            BRSharedPrefs.getPreferredFiatIso(context)
        ) ?: BigDecimal.ZERO
    }

    private fun getPriceChange(currencyCode: String) =
        RatesRepository.getInstance(context).getPriceChange(currencyCode)

    private fun CryptoWallet.asWallet(): Wallet {
        return Wallet(
            currencyId = currencyId,
            currencyName = currency.name,
            currencyCode = currency.code,
            fiatPricePerUnit = getFiatPerPriceUnit(currency.code),
            balance = balance.toBigDecimal(),
            fiatBalance = getBalanceInFiat(balance),
            syncProgress = 0f, // will update via sync events
            syncingThroughMillis = 0L, // will update via sync events
            priceChange = getPriceChange(currency.code),
            state = Wallet.State.READY,
            isSyncing = walletManager.state == WalletManagerState.SYNCING()
        )
    }

    private fun checkIfShowBuyAndSell() {
        val showBuyAndSell =
            ExperimentsRepositoryImpl.isExperimentActive(Experiments.BUY_SELL_MENU_BUTTON)
                && BRSharedPrefs.getPreferredFiatIso() == BRConstants.USD
        com.cryptron.tools.util.EventUtils.pushEvent(
            com.cryptron.tools.util.EventUtils.EVENT_EXPERIMENT_BUY_SELL_MENU_BUTTON,
            mapOf(com.cryptron.tools.util.EventUtils.EVENT_ATTRIBUTE_SHOW to showBuyAndSell.toString())
        )
        output.accept(E.OnShowBuyAndSell(showBuyAndSell))
    }
}
