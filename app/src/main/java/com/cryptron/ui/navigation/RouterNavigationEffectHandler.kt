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
package com.cryptron.ui.navigation

import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.FadeChangeHandler
import com.bluelinelabs.conductor.changehandler.HorizontalChangeHandler
import com.bluelinelabs.conductor.changehandler.VerticalChangeHandler
import com.cryptron.R
import com.cryptron.legacy.presenter.settings.NotificationSettingsController
import com.cryptron.logger.logError
import com.cryptron.protocols.messageexchange.MessageExchangeService
import com.cryptron.tools.util.BRConstants
import com.cryptron.tools.util.EventUtils
import com.cryptron.tools.util.Link
import com.cryptron.tools.util.ServerBundlesHelper
import com.cryptron.tools.util.asCryptoRequestUrl
import com.cryptron.tools.util.asLink
import com.cryptron.tools.util.btc
import com.cryptron.ui.addwallets.AddWalletsController
import com.cryptron.ui.auth.AuthenticationController
import com.cryptron.ui.auth.AuthenticationController.Mode
import com.cryptron.ui.changehandlers.BottomSheetChangeHandler
import com.cryptron.ui.controllers.AlertDialogController
import com.cryptron.ui.controllers.SignalController
import com.cryptron.ui.disabled.DisabledController
import com.cryptron.ui.home.HomeController
import com.cryptron.ui.importwallet.ImportController
import com.cryptron.ui.login.LoginController
import com.cryptron.ui.notification.InAppNotificationActivity
import com.cryptron.ui.onboarding.OnBoardingController
import com.cryptron.ui.pin.InputPinController
import com.cryptron.ui.provekey.PaperKeyProveController
import com.cryptron.ui.receive.ReceiveController
import com.cryptron.ui.scanner.ScannerController
import com.cryptron.ui.send.SendSheetController
import com.cryptron.ui.settings.SettingsController
import com.cryptron.ui.settings.about.AboutController
import com.cryptron.ui.settings.analytics.ShareDataController
import com.cryptron.ui.settings.currency.DisplayCurrencyController
import com.cryptron.ui.settings.fastsync.FastSyncController
import com.cryptron.ui.settings.fingerprint.FingerprintSettingsController
import com.cryptron.ui.settings.nodeselector.NodeSelectorController
import com.cryptron.ui.settings.segwit.EnableSegWitController
import com.cryptron.ui.settings.segwit.LegacyAddressController
import com.cryptron.ui.settings.wipewallet.WipeWalletController
import com.cryptron.ui.showkey.ShowPaperKeyController
import com.cryptron.ui.sync.SyncBlockchainController
import com.cryptron.ui.txdetails.TxDetailsController
import com.cryptron.ui.wallet.BrdWalletController
import com.cryptron.ui.wallet.WalletController
import com.cryptron.ui.web.WebController
import com.cryptron.ui.writedownkey.WriteDownKeyController
import com.cryptron.util.errorHandler
import com.cryptron.util.isBrd
import com.platform.HTTPServer
import com.platform.util.AppReviewPromptManager
import com.spotify.mobius.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

@Suppress("TooManyFunctions")
class RouterNavigationEffectHandler(
    private val router: Router
) : Connection<NavigationEffect>,
    NavigationEffectHandlerSpec {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + errorHandler())

    override fun accept(value: NavigationEffect) {
        scope.launch { patch(value) }
    }

    override fun dispose() {
        scope.cancel()
    }

    fun Controller.asTransaction(
        popChangeHandler: ControllerChangeHandler? = FadeChangeHandler(),
        pushChangeHandler: ControllerChangeHandler? = FadeChangeHandler()
    ) = RouterTransaction.with(this)
        .popChangeHandler(popChangeHandler)
        .pushChangeHandler(pushChangeHandler)

    override fun goToWallet(effect: NavigationEffect.GoToWallet) {
        val walletController = when {
            effect.currencyCode.isBrd() -> BrdWalletController()
            else -> WalletController(effect.currencyCode)
        }
        router.pushController(
            RouterTransaction.with(walletController)
                .popChangeHandler(HorizontalChangeHandler())
                .pushChangeHandler(HorizontalChangeHandler())
        )
    }

    override fun goBack() {
        if (!router.handleBack()) {
            router.activity?.onBackPressed()
        }
    }

    override fun goToBrdRewards() {
        val rewardsUrl = HTTPServer.getPlatformUrl(HTTPServer.URL_REWARDS)
        router.pushController(
            WebController(rewardsUrl).asTransaction(
                VerticalChangeHandler(),
                VerticalChangeHandler()
            )
        )
    }

    override fun goToReview() {
        com.cryptron.tools.util.EventUtils.pushEvent(com.cryptron.tools.util.EventUtils.EVENT_REVIEW_PROMPT_GOOGLE_PLAY_TRIGGERED)
        AppReviewPromptManager.openGooglePlay(checkNotNull(router.activity))
    }

    override fun goToBuy() {
        val url = String.format(
            BRConstants.CURRENCY_PARAMETER_STRING_FORMAT,
            HTTPServer.getPlatformUrl(HTTPServer.URL_BUY),
            btc.toUpperCase(Locale.ROOT)
        )
        val webTransaction =
            WebController(url).asTransaction(
                VerticalChangeHandler(),
                VerticalChangeHandler()
            )

        when (router.backstack.lastOrNull()?.controller()) {
            is HomeController -> router.pushController(webTransaction)
            else -> {
                router.setBackstack(
                    listOf(
                        HomeController().asTransaction(),
                        webTransaction
                    ),
                    VerticalChangeHandler()
                )
            }
        }
    }

    override fun goToTrade() {
        val url = HTTPServer.getPlatformUrl(HTTPServer.URL_TRADE)
        router.pushController(
            WebController(url).asTransaction(
                VerticalChangeHandler(),
                VerticalChangeHandler()
            )
        )
    }

    override fun goToMenu(effect: NavigationEffect.GoToMenu) {
        router.pushController(
            RouterTransaction.with(SettingsController(effect.settingsOption))
                .popChangeHandler(VerticalChangeHandler())
                .pushChangeHandler(VerticalChangeHandler())
        )
    }

    override fun goToAddWallet() {
        router.pushController(
            RouterTransaction.with(AddWalletsController())
                .popChangeHandler(HorizontalChangeHandler())
                .pushChangeHandler(HorizontalChangeHandler())
        )
    }

    override fun goToSend(effect: NavigationEffect.GoToSend) {
        val controller = when {
            effect.cryptoRequest != null -> SendSheetController(
                effect.cryptoRequest.asCryptoRequestUrl()
            )
            effect.cryptoRequestUrl != null -> SendSheetController(effect.cryptoRequestUrl)
            else -> SendSheetController(effect.currencyId)
        }
        router.pushController(RouterTransaction.with(controller))
    }

    override fun goToReceive(effect: NavigationEffect.GoToReceive) {
        val controller = ReceiveController(effect.currencyCode)
        router.pushController(RouterTransaction.with(controller))
    }

    override fun goToTransaction(effect: NavigationEffect.GoToTransaction) {
        val controller = TxDetailsController(effect.currencyId, effect.txHash)
        router.pushController(RouterTransaction.with(controller))
    }

    override fun goToDeepLink(effect: NavigationEffect.GoToDeepLink) {
        val link = effect.url?.asLink() ?: effect.link
        if (link == null) {
            logError("Failed to parse url, ${effect.url}")
            showLaunchScreen(effect.authenticated)
            return
        }
        val isTopLogin = router.backstack.lastOrNull()?.controller() is LoginController
        if (isTopLogin && effect.authenticated) {
            router.popCurrentController()
        }
        when (link) {
            is Link.CryptoRequestUrl -> {
                val sendController = SendSheetController(link).asTransaction()
                router.pushWithStackIfEmpty(sendController, effect.authenticated) {
                    listOf(
                        HomeController().asTransaction(),
                        WalletController(link.currencyCode).asTransaction(
                            popChangeHandler = HorizontalChangeHandler(),
                            pushChangeHandler = HorizontalChangeHandler()
                        ),
                        sendController
                    )
                }
            }
            is Link.BreadUrl.ScanQR -> {
                val controller = ScannerController().asTransaction()
                router.pushWithStackIfEmpty(controller, effect.authenticated) {
                    listOf(
                        HomeController().asTransaction(),
                        controller
                    )
                }
            }
            is Link.ImportWallet -> {
                val controller = ImportController().asTransaction()
                router.pushWithStackIfEmpty(controller, effect.authenticated) {
                    listOf(
                        HomeController().asTransaction(),
                        controller
                    )
                }
            }
            is Link.PlatformUrl -> {
                val controller = WebController(link.url).asTransaction()
                router.pushWithStackIfEmpty(controller, effect.authenticated) {
                    listOf(
                        HomeController().asTransaction(),
                        controller
                    )
                }
            }
            is Link.PlatformDebugUrl -> {
                val context = router.activity!!.applicationContext
                if (!link.webBundleUrl.isNullOrBlank()) {
                    com.cryptron.tools.util.ServerBundlesHelper.setWebPlatformDebugURL(context, link.webBundleUrl)
                } else if (!link.webBundle.isNullOrBlank()) {
                    com.cryptron.tools.util.ServerBundlesHelper.setDebugBundle(
                        context,
                        com.cryptron.tools.util.ServerBundlesHelper.Type.WEB,
                        link.webBundle
                    )
                }

                showLaunchScreen(effect.authenticated)
            }
            Link.BreadUrl.ScanQR -> {
                val controller = ScannerController().asTransaction()
                router.pushWithStackIfEmpty(controller, effect.authenticated) {
                    listOf(
                        HomeController().asTransaction(),
                        controller
                    )
                }
            }
            is Link.WalletPairUrl -> {
                val context = router.activity!!.applicationContext
                com.cryptron.protocols.messageexchange.MessageExchangeService.enqueueWork(
                    context, com.cryptron.protocols.messageexchange.MessageExchangeService.createIntent(
                        context,
                        com.cryptron.protocols.messageexchange.MessageExchangeService.ACTION_REQUEST_TO_PAIR,
                        link.pairingMetaData
                    )
                )
                showLaunchScreen(effect.authenticated)
            }
            else -> {
                logError("Failed to route deeplink, going Home.")
                showLaunchScreen(effect.authenticated)
            }
        }
    }

    private fun showLaunchScreen(isAuthenticated: Boolean) {
        if (!router.hasRootController()) {
            val root = if (isAuthenticated) {
                HomeController()
            } else {
                LoginController(showHome = true)
            }
            router.setRoot(root.asTransaction())
        }
    }

    override fun goToInAppMessage(effect: NavigationEffect.GoToInAppMessage) {
        InAppNotificationActivity.start(checkNotNull(router.activity), effect.inAppMessage)
    }

    override fun goToFaq(effect: NavigationEffect.GoToFaq) {
        router.pushController(
            WebController(effect.asSupportUrl()).asTransaction(
                BottomSheetChangeHandler(),
                BottomSheetChangeHandler()
            )
        )
    }

    override fun goToSetPin(effect: NavigationEffect.GoToSetPin) {
        val transaction = RouterTransaction.with(
            InputPinController(
                onComplete = effect.onComplete,
                pinUpdate = !effect.onboarding,
                skipWriteDown = effect.skipWriteDownKey
            )
        ).pushChangeHandler(HorizontalChangeHandler())
            .popChangeHandler(HorizontalChangeHandler())
        if (effect.onboarding) {
            router.setBackstack(listOf(transaction), HorizontalChangeHandler())
        } else {
            router.pushController(transaction)
        }
    }

    override fun goToHome() {
        router.setBackstack(
            listOf(RouterTransaction.with(HomeController())), HorizontalChangeHandler()
        )
    }

    override fun goToLogin() {
        router.pushController(
            RouterTransaction.with(LoginController())
                .popChangeHandler(FadeChangeHandler())
                .pushChangeHandler(FadeChangeHandler())
        )
    }

    override fun goToAuthentication() {
        val res = checkNotNull(router.activity).resources
        val controller = AuthenticationController(
            Mode.PIN_REQUIRED,
            title = res.getString(R.string.VerifyPin_title),
            message = res.getString(R.string.VerifyPin_continueBody)
        )
        val listener = router.backstack.lastOrNull()?.controller()
        if (listener is AuthenticationController.Listener) {
            controller.targetController = listener
        }
        router.pushController(RouterTransaction.with(controller))
    }

    override fun goToErrorDialog(effect: NavigationEffect.GoToErrorDialog) {
        val res = checkNotNull(router.activity).resources
        router.pushController(
            RouterTransaction.with(
                AlertDialogController(
                    effect.message,
                    effect.title,
                    negativeText = res.getString(R.string.AccessibilityLabels_close)
                )
            )
        )
    }

    override fun goToDisabledScreen() {
        router.pushController(
            RouterTransaction.with(DisabledController())
                .pushChangeHandler(FadeChangeHandler())
                .popChangeHandler(FadeChangeHandler())
        )
    }

    override fun goToQrScan() {
        val controller = ScannerController()
        controller.targetController = router.backstack.lastOrNull()?.controller()
        router.pushController(
            RouterTransaction.with(controller)
                .pushChangeHandler(FadeChangeHandler())
                .popChangeHandler(FadeChangeHandler())
        )
    }

    override fun goToWriteDownKey(effect: NavigationEffect.GoToWriteDownKey) {
        router.pushController(
            RouterTransaction.with(WriteDownKeyController(effect.onComplete, effect.requestAuth))
                .pushChangeHandler(HorizontalChangeHandler())
                .popChangeHandler(HorizontalChangeHandler())
        )
    }

    override fun goToPaperKey(effect: NavigationEffect.GoToPaperKey) {
        router.pushController(
            RouterTransaction.with(ShowPaperKeyController(effect.phrase, effect.onComplete))
                .pushChangeHandler(HorizontalChangeHandler())
                .popChangeHandler(HorizontalChangeHandler())
        )
    }

    override fun goToPaperKeyProve(effect: NavigationEffect.GoToPaperKeyProve) {
        router.pushController(
            RouterTransaction.with(PaperKeyProveController(effect.phrase, effect.onComplete))
                .pushChangeHandler(HorizontalChangeHandler())
                .popChangeHandler(HorizontalChangeHandler())
        )
    }

    override fun goToGooglePlay() {
        AppReviewPromptManager.openGooglePlay(checkNotNull(router.activity))
    }

    override fun goToAbout() {
        router.pushController(
            AboutController().asTransaction(
                HorizontalChangeHandler(),
                HorizontalChangeHandler()
            )
        )
    }

    override fun goToDisplayCurrency() {
        router.pushController(
            RouterTransaction.with(DisplayCurrencyController())
                .pushChangeHandler(HorizontalChangeHandler())
                .popChangeHandler(HorizontalChangeHandler())
        )
    }

    override fun goToNotificationsSettings() {
        router.pushController(
            NotificationSettingsController().asTransaction(
                HorizontalChangeHandler(),
                HorizontalChangeHandler()
            )
        )
    }

    override fun goToShareData() {
        router.pushController(
            ShareDataController().asTransaction(
                HorizontalChangeHandler(),
                HorizontalChangeHandler()
            )
        )
    }

    override fun goToFingerprintAuth() {
        router.pushController(
            RouterTransaction.with(FingerprintSettingsController())
                .pushChangeHandler(HorizontalChangeHandler())
                .popChangeHandler(HorizontalChangeHandler())
        )
    }

    override fun goToWipeWallet() {
        router.pushController(
            RouterTransaction.with(WipeWalletController())
                .pushChangeHandler(HorizontalChangeHandler())
                .popChangeHandler(HorizontalChangeHandler())
        )
    }

    override fun goToOnboarding() {
        router.pushController(
            RouterTransaction.with(OnBoardingController())
                .pushChangeHandler(HorizontalChangeHandler())
                .popChangeHandler(HorizontalChangeHandler())
        )
    }

    override fun goToImportWallet() {
        router.pushController(
            ImportController().asTransaction(
                HorizontalChangeHandler(),
                HorizontalChangeHandler()
            )
        )
    }

    override fun goToSyncBlockchain(effect: NavigationEffect.GoToSyncBlockchain) {
        router.pushController(
            RouterTransaction.with(SyncBlockchainController(effect.currencyCode))
                .popChangeHandler(HorizontalChangeHandler())
                .pushChangeHandler(HorizontalChangeHandler())
        )
    }

    override fun goToBitcoinNodeSelector() {
        router.pushController(
            RouterTransaction.with(NodeSelectorController())
                .pushChangeHandler(HorizontalChangeHandler())
                .popChangeHandler(HorizontalChangeHandler())
        )
    }

    override fun goToEnableSegWit() {
        router.pushController(
            RouterTransaction.with(EnableSegWitController())
                .pushChangeHandler(HorizontalChangeHandler())
                .popChangeHandler(HorizontalChangeHandler())
        )
    }

    override fun goToLegacyAddress() {
        router.pushController(
            RouterTransaction.with(LegacyAddressController())
        )
    }

    override fun goToFastSync(effect: NavigationEffect.GoToFastSync) {
        router.pushController(
            RouterTransaction.with(FastSyncController(effect.currencyCode))
                .pushChangeHandler(HorizontalChangeHandler())
                .popChangeHandler(HorizontalChangeHandler())
        )
    }

    override fun goToTransactionComplete() {
        val res = checkNotNull(router.activity).resources
        router.replaceTopController(
            RouterTransaction.with(
                SignalController(
                    title = res.getString(R.string.Alerts_sendSuccess),
                    description = res.getString(R.string.Alerts_sendSuccessSubheader),
                    iconResId = R.drawable.ic_check_mark_white
                )
            )
        )
    }

    override fun goToNativeApiExplorer() {
        val url = "file:///android_asset/native-api-explorer.html"
        router.pushController(RouterTransaction.with(WebController(url)))
    }

    override fun goToATMMap(effect: NavigationEffect.GoToATMMap) {
        router.pushController(
            WebController(effect.url, effect.mapJson).asTransaction(
                VerticalChangeHandler(),
                VerticalChangeHandler()
            )
        )
    }

    private inline fun Router.pushWithStackIfEmpty(
        topTransaction: RouterTransaction,
        isAuthenticated: Boolean,
        createStack: () -> List<RouterTransaction>
    ) {
        if (backstackSize <= 1) {
            val stack = if (isAuthenticated) {
                createStack()
            } else {
                createStack() + LoginController(showHome = false).asTransaction()
            }
            setBackstack(stack, FadeChangeHandler())
        } else {
            pushController(topTransaction)
            if (!isAuthenticated) {
                pushController(LoginController(showHome = false).asTransaction())
            }
        }
    }
}
