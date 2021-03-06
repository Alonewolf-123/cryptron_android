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

import com.cryptron.tools.util.EventUtils
import com.cryptron.ui.home.HomeScreen.E
import com.cryptron.ui.home.HomeScreen.F
import com.cryptron.ui.home.HomeScreen.M
import com.spotify.mobius.Effects.effects
import com.spotify.mobius.Next.dispatch
import com.spotify.mobius.Next.next
import com.spotify.mobius.Next.noChange
import com.spotify.mobius.Update

const val MAX_CRYPTO_DIGITS = 8

val HomeScreenUpdate = Update<M, E, F> { model, event ->
    when (event) {
        is E.OnWalletDisplayOrderUpdated -> next(
            model.copy(
                wallets = model.wallets.values
                    .sortedBy { event.displayOrder.indexOf(it.currencyId) }
                    .associateBy(Wallet::currencyCode)
            ),
            setOf(F.UpdateWalletOrder(event.displayOrder))
        )
        is E.OnWalletSyncProgressUpdated -> {
            when (val wallet = model.wallets[event.currencyCode]) {
                null -> noChange<M, F>()
                else -> {
                    val wallets = model.wallets.toMutableMap()
                    wallets[event.currencyCode] = wallet.copy(
                        syncProgress = event.progress,
                        syncingThroughMillis = event.syncThroughMillis,
                        isSyncing = event.isSyncing,
                        state = Wallet.State.READY
                    )
                    next(model.copy(wallets = wallets))
                }
            }
        }
        is E.OnBuyBellNeededLoaded -> next(model.copy(isBuyBellNeeded = event.isBuyBellNeeded))
        is E.OnEnabledWalletsUpdated -> {
            next(
                model.copy(
                    wallets = event.wallets
                        .associateBy(Wallet::currencyCode)
                        .mapValues { (currencyCode, wallet) ->
                            model.wallets[currencyCode] ?: wallet
                        },
                    displayOrder = event.wallets.map(Wallet::currencyId)
                )
            )
        }
        is E.OnWalletsUpdated -> {
            val wallets = model.wallets.toMutableMap()
            event.wallets.forEach { wallet ->
                wallets[wallet.currencyCode] = wallet
            }
            next(
                model.copy(
                    wallets = wallets
                )
            )
        }
        is E.OnUnitPriceChanged -> {
            model.wallets[event.currencyCode]?.let { wallet ->
                val wallets = model.wallets.toMutableMap()
                wallets[event.currencyCode] = wallet.copy(
                    fiatPricePerUnit = event.fiatPricePerUnit,
                    priceChange = event.priceChange
                )
                next<M, F>(model.copy(wallets = wallets.toMap()))
            } ?: noChange<M, F>()
        }
        is E.OnWalletBalanceUpdated -> {
            when (val wallet = model.wallets[event.currencyCode]) {
                null -> noChange<M, F>()
                else -> {
                    when (wallet.balance == event.balance && wallet.fiatBalance == event.fiatBalance) {
                        true -> noChange<M, F>()
                        else -> {
                            val wallets = model.wallets.toMutableMap()
                            wallets[event.currencyCode] = wallet.copy(
                                balance = event.balance,
                                fiatBalance = event.fiatBalance,
                                state = Wallet.State.READY
                            )
                            next(model.copy(wallets = wallets))
                        }
                    }
                }
            }
        }
        is E.OnConnectionUpdated -> next(model.copy(hasInternet = event.isConnected))
        is E.OnWalletClicked -> {
            val wallet = model.wallets[event.currencyCode]
            when (wallet?.state) {
                null, Wallet.State.LOADING -> noChange<M, F>()
                else -> dispatch<M, F>(
                    effects(
                        F.GoToWallet(event.currencyCode)
                    )
                )
            }
        }
        is E.OnAddWalletsClicked -> dispatch(effects(F.GoToAddWallet))
        E.OnBuyClicked -> dispatch(effects(F.GoToBuy))
        E.OnTradeClicked -> dispatch(effects(F.GoToTrade))
        E.OnMenuClicked -> dispatch(effects(F.GoToMenu))
        is E.OnPromptLoaded -> next(model.copy(promptId = event.promptId))
        is E.OnDeepLinkProvided -> dispatch(
            effects(
                F.GoToDeepLink(
                    event.url
                )
            )
        )
        is E.OnInAppNotificationProvided -> dispatch(
            effects(
                F.GoToInappMessage(
                    event.inAppMessage
                )
            )
        )
        is E.OnPushNotificationOpened -> dispatch(
            effects(
                F.RecordPushNotificationOpened(
                    event.campaignId
                )
            )
        )
        is E.OnShowBuyAndSell -> {
            val clickAttributes =
                mapOf(com.cryptron.tools.util.EventUtils.EVENT_ATTRIBUTE_BUY_AND_SELL to model.showBuyAndSell.toString())
            next<M, F>(
                model.copy(showBuyAndSell = event.showBuyAndSell),
                effects(
                    F.TrackEvent(
                        com.cryptron.tools.util.EventUtils.EVENT_HOME_DID_TAP_BUY,
                        clickAttributes
                    )
                )
            )
        }
        is E.OnPromptDismissed -> {
            val promptName = when (event.promptId) {
                PromptItem.EMAIL_COLLECTION -> com.cryptron.tools.util.EventUtils.PROMPT_EMAIL
                PromptItem.FINGER_PRINT -> com.cryptron.tools.util.EventUtils.PROMPT_TOUCH_ID
                PromptItem.PAPER_KEY -> com.cryptron.tools.util.EventUtils.PROMPT_PAPER_KEY
                PromptItem.UPGRADE_PIN -> com.cryptron.tools.util.EventUtils.PROMPT_UPGRADE_PIN
                PromptItem.RECOMMEND_RESCAN -> com.cryptron.tools.util.EventUtils.PROMPT_RECOMMEND_RESCAN
            }
            val eventName = promptName + com.cryptron.tools.util.EventUtils.EVENT_PROMPT_SUFFIX_DISMISSED
            next(
                model.copy(promptId = null),
                effects(
                    F.DismissPrompt(event.promptId),
                    F.TrackEvent(eventName)
                )
            )
        }
        E.OnFingerprintPromptClicked -> {
            val eventName = com.cryptron.tools.util.EventUtils.PROMPT_TOUCH_ID + com.cryptron.tools.util.EventUtils.EVENT_PROMPT_SUFFIX_TRIGGER
            next(
                model.copy(promptId = null),
                effects(
                    F.DismissPrompt(PromptItem.FINGER_PRINT),
                    F.GoToFingerprintSettings,
                    F.TrackEvent(eventName)
                )
            )
        }
        E.OnPaperKeyPromptClicked -> {
            val eventName = com.cryptron.tools.util.EventUtils.PROMPT_PAPER_KEY + com.cryptron.tools.util.EventUtils.EVENT_PROMPT_SUFFIX_TRIGGER
            next(
                model.copy(promptId = null),
                effects(
                    F.GoToWriteDownKey,
                    F.TrackEvent(eventName)
                )
            )
        }
        E.OnUpgradePinPromptClicked -> {
            val eventName = com.cryptron.tools.util.EventUtils.PROMPT_UPGRADE_PIN + com.cryptron.tools.util.EventUtils.EVENT_PROMPT_SUFFIX_TRIGGER
            next(
                model.copy(promptId = null),
                effects(
                    F.GoToUpgradePin,
                    F.TrackEvent(eventName)
                )
            )
        }
        E.OnRescanPromptClicked -> {
            val eventName =
                com.cryptron.tools.util.EventUtils.PROMPT_RECOMMEND_RESCAN + com.cryptron.tools.util.EventUtils.EVENT_PROMPT_SUFFIX_TRIGGER
            next(
                model.copy(promptId = null),
                effects(
                    F.StartRescan,
                    F.TrackEvent(eventName)
                )
            )
        }
        is E.OnEmailPromptClicked -> {
            val eventName = com.cryptron.tools.util.EventUtils.PROMPT_EMAIL + com.cryptron.tools.util.EventUtils.EVENT_PROMPT_SUFFIX_TRIGGER
            dispatch(
                effects(
                    F.SaveEmail(event.email),
                    F.TrackEvent(eventName)
                )
            )
        }
    }
}
