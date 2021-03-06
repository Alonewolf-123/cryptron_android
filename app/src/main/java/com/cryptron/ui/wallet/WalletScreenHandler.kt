/**
 * BreadWallet
 *
 * Created by Ahsan Butt <ahsan.butt@breadwallet.com> on 7/26/19.
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
package com.cryptron.ui.wallet

import android.content.Context
import com.cryptron.app.BreadApp
import com.cryptron.breadbox.BreadBox
import com.cryptron.breadbox.defaultUnit
import com.cryptron.breadbox.feeForToken
import com.cryptron.breadbox.formatFiatForUi
import com.cryptron.breadbox.hashString
import com.cryptron.breadbox.toBigDecimal
import com.cryptron.breadbox.toSanitizedString
import com.breadwallet.crypto.Amount
import com.breadwallet.crypto.Transfer
import com.breadwallet.crypto.TransferDirection
import com.cryptron.effecthandler.metadata.MetaDataEffect
import com.cryptron.effecthandler.metadata.MetaDataEvent
import com.cryptron.model.PriceChange
import com.cryptron.repository.RatesRepository
import com.cryptron.tools.manager.BRSharedPrefs
import com.cryptron.tools.sqlite.RatesDataSource
import com.cryptron.tools.util.EventUtils
import com.cryptron.tools.util.TokenUtil
import com.cryptron.ui.models.TransactionState
import com.cryptron.ui.navigation.NavEffectTransformer
import com.cryptron.ui.wallet.WalletScreen.E
import com.cryptron.ui.wallet.WalletScreen.F
import com.platform.util.AppReviewPromptManager
import com.spotify.mobius.Connectable
import drewcarlson.mobius.flow.flowTransformer
import drewcarlson.mobius.flow.subtypeEffectHandler
import drewcarlson.mobius.flow.transform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest
import java.math.BigDecimal
import kotlin.math.min

private const val MAX_PROGRESS = 100

@Suppress("TooManyFunctions")
object WalletScreenHandler {

    fun createEffectHandler(
        context: Context,
        breadBox: BreadBox,
        navEffectHandler: NavEffectTransformer,
        metadataEffectHandler: Connectable<MetaDataEffect, MetaDataEvent>,
        showCreateAccountDialog: () -> Unit,
        showCreateAccountErrorDialog: () -> Unit
    ) = subtypeEffectHandler<F, E> {
        addTransformer<F.Nav>(navEffectHandler)
        addTransformer(handleCheckReviewPrompt(context))
        addTransformer(handleLoadPricePerUnit(context))

        addTransformer(handleLoadBalance(breadBox))
        addTransformer(handleLoadTransactions(breadBox))
        addTransformer(handleLoadCurrencyName(breadBox))
        addTransformer(handleLoadSyncState(breadBox))
        addTransformer(handleWalletState(breadBox))
        addConsumer(handleCreateAccount(breadBox))

        addTransformer(handleLoadTransactionMetaData(metadataEffectHandler))
        addTransformer(handleLoadTransactionMetaDataSingle(metadataEffectHandler))

        addActionSync<F.RecordReviewPrompt>(Default, ::handleRecordReviewPrompt)
        addActionSync<F.RecordReviewPromptDismissed>(
            Default,
            handleRecordReviewPromptDismissed(context)
        )

        addConsumerSync(Default, ::handleTrackEvent)
        addConsumerSync(Default, ::handleUpdateCryptoPreferred)
        addFunctionSync(Default, ::handleLoadIsTokenSupported)
        addFunctionSync(Default, ::handleConvertCryptoTransactions)
        addFunctionSync(Default, handleLoadChartInterval(RatesRepository.getInstance(context)))
        addFunctionSync<F.LoadCryptoPreferred>(Default) {
            E.OnIsCryptoPreferredLoaded(BRSharedPrefs.isCryptoPreferred())
        }
        addActionSync<F.ShowCreateAccountDialog>(Main, showCreateAccountDialog)
        addActionSync<F.ShowCreateAccountErrorDialog>(Main, showCreateAccountErrorDialog)
    }

    private fun handleUpdateCryptoPreferred(
        effect: F.UpdateCryptoPreferred
    ) {
        com.cryptron.tools.util.EventUtils.pushEvent(com.cryptron.tools.util.EventUtils.EVENT_AMOUNT_SWAP_CURRENCY)
        BRSharedPrefs.setIsCryptoPreferred(b = effect.cryptoPreferred)
    }

    private fun handleConvertCryptoTransactions(
        effect: F.ConvertCryptoTransactions
    ) = effect.transactions
        .filter { it.hash.isPresent }
        .map { it.asWalletTransaction() }
        .run(E::OnTransactionsUpdated)

    private fun handleLoadIsTokenSupported(
        effect: F.LoadIsTokenSupported
    ) = com.cryptron.tools.util.TokenUtil.isTokenSupported(effect.currencyCode)
        .run(E::OnIsTokenSupportedUpdated)

    private fun handleCheckReviewPrompt(
        context: Context
    ) = flowTransformer<F.CheckReviewPrompt, E> { effects ->
        effects.transformLatest { (currencyCode, transactions) ->
            if (AppReviewPromptManager.showReview(context, currencyCode, transactions)) {
                emit(E.OnShowReviewPrompt)
            }
        }
    }

    private fun handleRecordReviewPrompt() {
        com.cryptron.tools.util.EventUtils.pushEvent(com.cryptron.tools.util.EventUtils.EVENT_REVIEW_PROMPT_DISPLAYED)
    }

    private fun handleRecordReviewPromptDismissed(
        context: Context
    ): () -> Unit = {
        com.cryptron.tools.util.EventUtils.pushEvent(com.cryptron.tools.util.EventUtils.EVENT_REVIEW_PROMPT_DISMISSED)
        AppReviewPromptManager.onReviewPromptDismissed(context)
    }

    private fun handleTrackEvent(value: F.TrackEvent) {
        com.cryptron.tools.util.EventUtils.pushEvent(value.eventName, value.attributes)
    }

    private fun handleLoadPricePerUnit(
        context: Context
    ) = flowTransformer<F.LoadFiatPricePerUnit, E> { effects ->
        val ratesRepository = RatesRepository.getInstance(context)
        val ratesDataSource = RatesDataSource.getInstance(context)
        val fiatIso = BRSharedPrefs.getPreferredFiatIso(context)
        effects
            .transformLatest { effect ->
                // Emit effect and observe rate changes,
                // dispatching the latest effect if needed
                emit(effect)
                emitAll(ratesDataSource
                    .rateChangesFlow()
                    .map { effect })
            }
            .mapLatest { effect ->
                val exchangeRate: BigDecimal? = ratesRepository.getFiatForCrypto(
                    BigDecimal.ONE,
                    effect.currencyCode,
                    BRSharedPrefs.getPreferredFiatIso(context)
                )
                val fiatPricePerUnit = exchangeRate?.formatFiatForUi(fiatIso).orEmpty()
                val priceChange: PriceChange? = ratesRepository.getPriceChange(effect.currencyCode)
                E.OnFiatPricePerUpdated(fiatPricePerUnit, priceChange)
            }
    }

    private fun handleLoadChartInterval(
        ratesRepository: RatesRepository
    ): (F.LoadChartInterval) -> E = { effect ->
        val dataPoints = ratesRepository.getHistoricalData(
            effect.currencyCode,
            BRSharedPrefs.getPreferredFiatIso(),
            effect.interval
        )
        E.OnMarketChartDataUpdated(dataPoints)
    }

    private fun handleLoadTransactions(
        breadBox: BreadBox
    ) = flowTransformer<F.LoadTransactions, E> { effects ->
        effects
            .flatMapLatest { effect ->
                breadBox.walletTransfers(effect.currencyCode).combine(
                    breadBox.wallet(effect.currencyCode)
                        .mapLatest { it.walletManager.network.height }
                        .distinctUntilChanged()
                )
                { transfers, _ -> transfers }
            }
            .mapLatest { transactions ->
                E.OnTransactionsUpdated(
                    transactions
                        .filter { it.hash.isPresent }
                        .map { it.asWalletTransaction() }
                        .sortedByDescending(WalletTransaction::timeStamp)
                )
            }
    }

    private fun handleLoadBalance(breadBox: BreadBox) =
        flowTransformer<F.LoadWalletBalance, E> { effects ->
            effects
                .flatMapLatest { effect ->
                    breadBox.wallet(effect.currencyCode)
                        .map { it.balance }
                        .distinctUntilChanged()
                }
                .mapLatest { balance ->
                    E.OnBalanceUpdated(
                        balance.toBigDecimal(),
                        getBalanceInFiat(balance)
                    )
                }
        }

    private fun handleLoadCurrencyName(breadBox: BreadBox) =
        flowTransformer<F.LoadCurrencyName, E> { effects ->
            effects
                .map { effect ->
                    com.cryptron.tools.util.TokenUtil.getTokenItemByCurrencyCode(effect.currencyCode)?.name ?:
                        breadBox.wallet(effect.currencyCode).first().currency.name
                }
                .map { E.OnCurrencyNameUpdated(it) }
        }

    private fun handleLoadSyncState(breadBox: BreadBox) =
        flowTransformer<F.LoadSyncState, E> { effects ->
            effects
                .flatMapLatest { (currencyId) ->
                    breadBox.walletSyncState(currencyId)
                }
                .mapLatest { state ->
                    E.OnSyncProgressUpdated(
                        state.percentComplete,
                        state.timestamp,
                        state.isSyncing
                    )
                }
        }

    private fun handleLoadTransactionMetaData(
        metadataEffectHandler: Connectable<MetaDataEffect, MetaDataEvent>
    ) = flowTransformer<F.LoadTransactionMetaData, E> { effects ->
        effects
            .map { MetaDataEffect.LoadTransactionMetaData(it.currencyCode, it.transactionHashes) }
            .transform(metadataEffectHandler)
            .filterIsInstance<MetaDataEvent.OnTransactionMetaDataUpdated>()
            .map { event ->
                E.OnTransactionMetaDataUpdated(
                    event.transactionHash,
                    event.txMetaData
                )
            }
    }

    private fun handleLoadTransactionMetaDataSingle(
        metaDataEffectHandler: Connectable<MetaDataEffect, MetaDataEvent>
    ) = flowTransformer<F.LoadTransactionMetaDataSingle, E> { effects ->
        effects
            .map {
                MetaDataEffect.LoadTransactionMetaDataSingle(
                    it.currencyCode,
                    it.transactionHashes
                )
            }
            .transform(metaDataEffectHandler)
            .filterIsInstance<MetaDataEvent.OnTransactionMetaDataSingleUpdated>()
            .map { event ->
                E.OnTransactionMetaDataLoaded(event.metadata)
            }
    }

    private fun RatesDataSource.rateChangesFlow() =
        callbackFlow<Unit> {
            val listener = RatesDataSource.OnDataChanged { offer(Unit) }
            addOnDataChangedListener(listener)
            awaitClose {
                removeOnDataChangedListener(listener)
            }
        }

    private fun handleWalletState(breadBox: BreadBox) =
        flowTransformer<F.LoadWalletState, E> { effects ->
            effects
                .flatMapLatest {
                    breadBox.walletState(it.currencyCode)
                }
                .mapLatest {
                    E.OnWalletStateUpdated(it)
                }
        }

    private fun handleCreateAccount(breadBox: BreadBox): suspend (F.CreateAccount) -> Unit =
        { breadBox.initializeWallet(it.currencyCode) }
}

private fun getBalanceInFiat(balanceAmt: Amount): BigDecimal {
    val context = BreadApp.getBreadContext()
    return RatesRepository.getInstance(context).getFiatForCrypto(
        balanceAmt.toBigDecimal(),
        balanceAmt.currency.code,
        BRSharedPrefs.getPreferredFiatIso(context)
    ) ?: BigDecimal.ZERO
}

fun Transfer.asWalletTransaction(): WalletTransaction {
    val confirmations = confirmations.orNull()?.toInt() ?: 0
    val confirmationsUntilFinal = wallet.walletManager.network.confirmationsUntilFinal.toInt()
    val isComplete = confirmations >= confirmationsUntilFinal
    val transferState = TransactionState.valueOf(state)
    val feeForToken = feeForToken()
    val amountInDefault = when {
        amount.unit == wallet.defaultUnit -> amount
        else -> amount.convert(wallet.defaultUnit).get()
    }
    val isErrored = state.failedError.isPresent || confirmation.orNull()?.success == false
    return WalletTransaction(
        txHash = hashString(),
        amount = when {
            feeForToken.isBlank() -> amountInDefault.toBigDecimal()
            else -> fee.toBigDecimal()
        },
        amountInFiat = getBalanceInFiat(
            when {
                feeForToken.isBlank() -> amountInDefault
                else -> fee
            }
        ),
        toAddress = target.orNull()?.toSanitizedString() ?: "<unknown>",
        fromAddress = source.orNull()?.toSanitizedString() ?: "<unknown>",
        isReceived = direction == TransferDirection.RECEIVED,
        fee = fee.doubleAmount(unitForFee.base).or(0.0).toBigDecimal(),
        confirmations = confirmations,
        isComplete = isComplete,
        isPending = when (transferState) {
            TransactionState.CONFIRMING -> true
            TransactionState.CONFIRMED -> !isComplete
            else -> false
        },
        isErrored = isErrored,
        progress = min(
            ((confirmations.toDouble() / confirmationsUntilFinal) * MAX_PROGRESS).toInt(),
            MAX_PROGRESS
        ),
        timeStamp = confirmation.orNull()?.confirmationTime?.time ?: System.currentTimeMillis(),
        currencyCode = wallet.currency.code,
        feeToken = feeForToken
    )
}
