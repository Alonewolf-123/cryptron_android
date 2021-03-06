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

import com.cryptron.legacy.presenter.entities.CryptoRequest
import com.cryptron.model.InAppMessage
import com.cryptron.tools.util.Link
import com.cryptron.ui.settings.SettingsSection
import drewcarlson.switchboard.MobiusHandlerSpec
import io.sweers.redacted.annotation.Redacted

/**
 * [NavEffectHolder] can be applied to a screen specific
 * navigation effect to support [RouterNavigationEffectHandler]
 * without needing to map every effect to a [NavigationEffect].
 *
 * @see com.breadwallet.ui.BaseMobiusController.handleNavEffects
 */
interface NavEffectHolder {
    val navigationEffect: NavigationEffect
}

@MobiusHandlerSpec
sealed class NavigationEffect {
    data class GoToSend(
        val currencyId: String,
        val cryptoRequest: com.cryptron.legacy.presenter.entities.CryptoRequest? = null,
        val cryptoRequestUrl: Link.CryptoRequestUrl? = null
    ) : NavigationEffect()

    data class GoToReceive(val currencyCode: String) : NavigationEffect()
    data class GoToTransaction(
        val currencyId: String,
        val txHash: String
    ) : NavigationEffect()

    object GoBack : NavigationEffect()
    object GoToBrdRewards : NavigationEffect()
    object GoToReview : NavigationEffect()
    object GoToQrScan : NavigationEffect()

    data class GoToDeepLink(
        val url: String? = null,
        val authenticated: Boolean,
        val link: Link? = null
    ) : NavigationEffect()

    data class GoToInAppMessage(val inAppMessage: InAppMessage) : NavigationEffect()
    data class GoToWallet(val currencyCode: String) : NavigationEffect()
    data class GoToFaq(
        val articleId: String,
        val currencyCode: String? = null
    ) : NavigationEffect()

    data class GoToSetPin(
        val onboarding: Boolean = false,
        val skipWriteDownKey: Boolean = false,
        val onComplete: OnCompleteAction = OnCompleteAction.GO_HOME
    ) : NavigationEffect()

    data class GoToErrorDialog(
        val title: String,
        val message: String
    ) : NavigationEffect()

    object GoToLogin : NavigationEffect()
    object GoToAuthentication : NavigationEffect()
    object GoToHome : NavigationEffect()
    object GoToBuy : NavigationEffect()
    object GoToTrade : NavigationEffect()
    object GoToAddWallet : NavigationEffect()
    object GoToDisabledScreen : NavigationEffect()
    object GoToNativeApiExplorer : NavigationEffect()

    data class GoToWriteDownKey(
        val onComplete: OnCompleteAction,
        val requestAuth: Boolean = true
    ) : NavigationEffect()

    data class GoToPaperKey(
        @Redacted val phrase: List<String>,
        val onComplete: OnCompleteAction?
    ) : NavigationEffect()

    data class GoToPaperKeyProve(
        @Redacted val phrase: List<String>,
        val onComplete: OnCompleteAction
    ) : NavigationEffect()

    data class GoToMenu(val settingsOption: SettingsSection) : NavigationEffect()

    object GoToTransactionComplete : NavigationEffect()
    object GoToGooglePlay : NavigationEffect()
    object GoToAbout : NavigationEffect()
    object GoToDisplayCurrency : NavigationEffect()
    object GoToNotificationsSettings : NavigationEffect()
    object GoToShareData : NavigationEffect()
    object GoToFingerprintAuth : NavigationEffect()
    object GoToWipeWallet : NavigationEffect()
    object GoToOnboarding : NavigationEffect()
    object GoToImportWallet : NavigationEffect()
    object GoToBitcoinNodeSelector : NavigationEffect()
    object GoToEnableSegWit : NavigationEffect()
    object GoToLegacyAddress : NavigationEffect()
    data class GoToSyncBlockchain(
        val currencyCode: String
    ) : NavigationEffect()

    data class GoToFastSync(
        val currencyCode: String
    ) : NavigationEffect()

    data class GoToATMMap(
        val url: String,
        val mapJson: String
    ) : NavigationEffect()
}
