/**
 * BreadWallet
 *
 * Created by Pablo Budelli <pablo.budelli@breadwallet.com> on 10/10/19.
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
package com.cryptron.ui.provekey

import android.os.Bundle
import android.text.Editable
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import com.bluelinelabs.conductor.RouterTransaction
import com.cryptron.R
import com.cryptron.mobius.CompositeEffectHandler
import com.cryptron.mobius.nestedConnectable
import com.cryptron.tools.animation.SpringAnimator
import com.cryptron.tools.util.Utils
import com.cryptron.ui.BaseMobiusController
import com.cryptron.ui.controllers.SignalController
import com.cryptron.ui.navigation.NavigationEffect
import com.cryptron.ui.navigation.OnCompleteAction
import com.cryptron.ui.navigation.RouterNavigationEffectHandler
import com.cryptron.ui.provekey.PaperKeyProve.E
import com.cryptron.ui.provekey.PaperKeyProve.F
import com.cryptron.ui.provekey.PaperKeyProve.M
import com.cryptron.util.DefaultTextWatcher
import com.cryptron.util.normalize
import com.spotify.mobius.Connectable
import com.spotify.mobius.disposables.Disposable
import com.spotify.mobius.functions.Consumer
import kotlinx.android.synthetic.main.controller_paper_key_prove.*
import org.kodein.di.direct
import org.kodein.di.erased.instance

private const val EXTRA_PHRASE = "phrase"
private const val EXTRA_ON_COMPLETE = "on-complete"

class PaperKeyProveController(args: Bundle) :
    BaseMobiusController<M, E, F>(args),
    SignalController.Listener {

    constructor(phrase: List<String>, onComplete: OnCompleteAction) : this(
        bundleOf(
            EXTRA_PHRASE to phrase,
            EXTRA_ON_COMPLETE to onComplete.name
        )
    )

    private val phrase: List<String> = arg(EXTRA_PHRASE)
    private val onComplete = OnCompleteAction.valueOf(arg(EXTRA_ON_COMPLETE))

    override val layoutId = R.layout.controller_paper_key_prove
    override val defaultModel = M.createDefault(phrase, onComplete)
    override val update = PaperKeyProveUpdate
    override val effectHandler =
        CompositeEffectHandler.from<F, E>(
            Connectable { output: Consumer<E> ->
                PaperKeyProveHandler(
                    output,
                    { com.cryptron.tools.animation.SpringAnimator.failShakeAnimation(activity!!, first_word) },
                    { com.cryptron.tools.animation.SpringAnimator.failShakeAnimation(activity!!, second_word) },
                    {
                        val res = router.activity!!.resources
                        val signal = SignalController(
                            title = res.getString(R.string.Alerts_paperKeySet),
                            description = res.getString(R.string.Alerts_paperKeySetSubheader),
                            iconResId = R.drawable.ic_check_mark_white
                        )
                        signal.targetController = this@PaperKeyProveController
                        router.pushController(RouterTransaction.with(signal))
                    }
                )
            },
            nestedConnectable(
                { direct.instance<RouterNavigationEffectHandler>() },
                { effect ->
                    when (effect) {
                        F.GoToBuy -> NavigationEffect.GoToBuy
                        F.GoToHome -> NavigationEffect.GoToHome
                        else -> null
                    }
                })
        )

    override fun bindView(output: Consumer<E>): Disposable {
        submit_btn.setOnClickListener { output.accept(E.OnSubmitClicked) }
        first_word.addTextChangedListener(object : DefaultTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                val word = s?.toString().orEmpty().normalize()
                output.accept(E.OnFirstWordChanged(word))
            }
        })
        second_word.addTextChangedListener(object : DefaultTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                val word = s?.toString().orEmpty().normalize()
                output.accept(E.OnSecondWordChanged(word))
            }
        })
        return Disposable { } // remove text watcher here
    }

    override fun onDetach(view: View) {
        super.onDetach(view)
        com.cryptron.tools.util.Utils.hideKeyboard(activity)
    }

    override fun M.render() {
        ifChanged(M::firstWordState) {
            first_word.setTextColor(
                activity!!.getColor(
                    if (firstWordState == M.WordState.VALID) R.color.light_gray
                    else R.color.red_text
                )
            )
            check_mark_1.isVisible = firstWordState == M.WordState.VALID
        }
        ifChanged(M::secondWordSate) {
            second_word.setTextColor(
                activity!!.getColor(
                    if (secondWordSate == M.WordState.VALID) R.color.light_gray
                    else R.color.red_text
                )
            )
            check_mark_2.isVisible = secondWordSate == M.WordState.VALID
        }

        ifChanged(M::firstWordIndex) {
            first_word_label.text =
                activity!!.getString(R.string.ConfirmPaperPhrase_word, firstWordIndex + 1)
        }
        ifChanged(M::secondWordIndex) {
            second_word_label.text =
                activity!!.getString(R.string.ConfirmPaperPhrase_word, secondWordIndex + 1)
        }
    }

    override fun onSignalComplete() {
        eventConsumer.accept(E.OnBreadSignalShown)
    }
}
