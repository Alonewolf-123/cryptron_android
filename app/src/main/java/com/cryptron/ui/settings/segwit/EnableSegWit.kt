/**
 * BreadWallet
 *
 * Created by Pablo Budelli <pablo.budelli@breadwallet.com> on 11/05/19.
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
package com.cryptron.ui.settings.segwit

import drewcarlson.switchboard.MobiusUpdateSpec

object EnableSegWit {

    data class M(val state: State = State.ENABLE) {
        enum class State {
            ENABLE, CONFIRMATION, DONE
        }
    }

    @MobiusUpdateSpec(
        prefix = "EnableSegWit",
        baseModel = M::class,
        baseEffect = F::class
    )
    sealed class E {
        object OnEnableClick : E()
        object OnContinueClicked : E()
        object OnCancelClicked : E()
        object OnBackClicked : E()
        object OnDoneClicked : E()
    }

    sealed class F {
        object GoBack : F()
        object GoToHome : F()
        object EnableSegWit : F()
    }
}
