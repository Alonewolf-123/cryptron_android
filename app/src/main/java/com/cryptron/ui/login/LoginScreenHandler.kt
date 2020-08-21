/**
 * BreadWallet
 *
 * Created by Pablo Budelli <pablo.budelli@breadwallet.com> on 10/25/19.
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
package com.cryptron.ui.login

import android.content.Context
import com.cryptron.tools.manager.BRSharedPrefs
import com.cryptron.tools.security.BrdUserManager
import com.cryptron.tools.security.isFingerPrintAvailableAndSetup
import com.cryptron.tools.util.EventUtils
import com.cryptron.ui.login.LoginScreen.E
import com.cryptron.ui.login.LoginScreen.F
import com.cryptron.util.errorHandler
import com.spotify.mobius.Connection
import com.spotify.mobius.functions.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LoginScreenHandler(
    private val output: Consumer<E>,
    private val context: Context,
    private val brdUserManager: BrdUserManager,
    private val shakeKeyboard: () -> Unit,
    private val unlockWalletAnimation: () -> Unit,
    private val showFingerprintPrompt: () -> Unit
) : Connection<F>, CoroutineScope {

    override val coroutineContext = SupervisorJob() + Dispatchers.Default + errorHandler()

    override fun accept(value: F) {
        when (value) {
            F.AuthenticationFailed -> launch(Dispatchers.Main) { shakeKeyboard() }
            F.CheckFingerprintEnable -> checkFingerprintEnable()
            F.LoadLoginPreferences -> loadLoginPreferences()
            F.AuthenticationSuccess -> launch(Dispatchers.Main) {
                brdUserManager.unlock() // Required for biometric auth acceptance
                unlockWalletAnimation()
            }
            F.ShowFingerprintController -> launch(Dispatchers.Main) { showFingerprintPrompt() }
            is F.TrackEvent -> trackEvent(value)
        }
    }

    override fun dispose() {
        coroutineContext.cancel()
    }

    private fun checkFingerprintEnable() {
        val fingerprintEnable =
            isFingerPrintAvailableAndSetup(context) && BRSharedPrefs.unlockWithFingerprint

        output.accept(E.OnFingerprintEnabled(fingerprintEnable))

        if (fingerprintEnable) {
            launch(Dispatchers.Main) { showFingerprintPrompt() }
        }
    }

    private fun loadLoginPreferences() {
        output.accept(
            E.OnLoginPreferencesLoaded(
                BRSharedPrefs.getCurrentWalletCurrencyCode()
            )
        )
    }

    private fun trackEvent(event: F.TrackEvent) {
        com.cryptron.tools.util.EventUtils.pushEvent(
            event.eventName,
            event.attributes
        )
    }
}
