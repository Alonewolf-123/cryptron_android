/**
 * BreadWallet
 *
 * Created by Drew Carlson <drew.carlson@breadwallet.com> on 8/13/19.
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
package com.cryptron.ui.recovery

import android.app.ActivityManager
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.Editable
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import com.cryptron.R
import com.cryptron.app.BreadApp
import com.cryptron.legacy.presenter.customviews.BRDialogView
import com.cryptron.legacy.presenter.customviews.BREdit
import com.cryptron.mobius.CompositeEffectHandler
import com.cryptron.mobius.nestedConnectable
import com.cryptron.tools.animation.BRDialog
import com.cryptron.tools.animation.SpringAnimator
import com.cryptron.tools.manager.BRClipboardManager
import com.cryptron.tools.util.BRConstants
import com.cryptron.tools.util.Utils
import com.cryptron.ui.BaseMobiusController
import com.cryptron.ui.navigation.NavigationEffect
import com.cryptron.ui.navigation.RouterNavigationEffectHandler
import com.cryptron.ui.recovery.RecoveryKey.E
import com.cryptron.ui.recovery.RecoveryKey.F
import com.cryptron.ui.recovery.RecoveryKey.M
import com.cryptron.util.DefaultTextWatcher
import com.spotify.mobius.Connectable
import com.spotify.mobius.disposables.Disposable
import com.spotify.mobius.functions.Consumer
import kotlinx.android.synthetic.main.controller_recovery_key.*
import org.kodein.di.direct
import org.kodein.di.erased.instance

class RecoveryKeyController(
    args: Bundle? = null
) : BaseMobiusController<M, E, F>(args) {

    constructor(
        mode: RecoveryKey.Mode,
        phrase: String? = null
    ) : this(
        bundleOf("mode" to mode.name)
    ) {
        launchPhrase = phrase
        if (launchPhrase != null) {
            eventConsumer.accept(E.OnNextClicked)
        }
    }

    private var launchPhrase: String? = null
    private val mode = arg("mode", RecoveryKey.Mode.RECOVER.name)

    override val layoutId: Int = R.layout.controller_recovery_key

    override val defaultModel
        get() = M.createWithOptionalPhrase(
            mode = RecoveryKey.Mode.valueOf(mode),
            phrase = launchPhrase
        )
    override val update = RecoveryKeyUpdate
    override val effectHandler = CompositeEffectHandler.from<F, E>(
        Connectable { output ->
            val resources = resources!!
            RecoveryKeyHandler(
                output,
                applicationContext as BreadApp,
                direct.instance(),
                direct.instance(),
                viewCreatedScope,
                { eventConsumer }, {
                    // unlink
                    com.cryptron.tools.animation.BRDialog.showCustomDialog(
                        activity!!,
                        resources.getString(R.string.WipeWallet_alertTitle),
                        resources.getString(R.string.WipeWallet_alertMessage),
                        resources.getString(R.string.WipeWallet_wipe),
                        resources.getString(R.string.Button_cancel),
                        {
                            it.context
                                .getSystemService(ActivityManager::class.java)
                                .clearApplicationUserData()
                        },
                        { brDialogView -> brDialogView.dismissWithAnimation() },
                        { eventConsumer.accept(E.OnPhraseSaveFailed) },
                        0
                    )
                },
                {
                    // error dialog
                    com.cryptron.tools.animation.BRDialog.showCustomDialog(
                        activity!!,
                        "",
                        resources.getString(R.string.RecoverWallet_invalid),
                        resources.getString(R.string.AccessibilityLabels_close),
                        null,
                        com.cryptron.legacy.presenter.customviews.BRDialogView.BROnClickListener { brDialogView -> brDialogView.dismissWithAnimation() },
                        null,
                        DialogInterface.OnDismissListener {
                            eventConsumer.accept(E.OnPhraseSaveFailed)
                        },
                        0
                    )
                },
                {
                    com.cryptron.tools.animation.SpringAnimator.failShakeAnimation(applicationContext, view)
                })
        },
        nestedConnectable({ direct.instance<RouterNavigationEffectHandler>() }, { effect ->
            when (effect) {
                F.GoToRecoveryKeyFaq -> NavigationEffect.GoToFaq(BRConstants.FAQ_PAPER_KEY)
                F.SetPinForRecovery -> NavigationEffect.GoToSetPin(
                    onboarding = true,
                    skipWriteDownKey = true
                )
                F.GoToLoginForReset -> NavigationEffect.GoToLogin
                F.SetPinForReset -> NavigationEffect.GoToSetPin()
                else -> null
            }
        })
    )

    private val wordInputs: List<com.cryptron.legacy.presenter.customviews.BREdit>
        get() = listOf(
            word1, word2, word3,
            word4, word5, word6,
            word7, word8, word9,
            word10, word11, word12
        )

    private val inputTextColorValue = TypedValue()
    private var errorTextColor: Int = -1
    private var normalTextColor: Int = -1

    override fun onCreateView(view: View) {
        super.onCreateView(view)
        val theme = view.context.theme
        val resources = resources!!

        theme.resolveAttribute(R.attr.input_words_text_color, inputTextColorValue, true)
        errorTextColor = resources.getColor(R.color.red_text, theme)
        normalTextColor = resources.getColor(inputTextColorValue.resourceId, theme)

        // TODO: This needs a better home
        if (com.cryptron.tools.util.Utils.isUsingCustomInputMethod(applicationContext)) {
            com.cryptron.tools.animation.BRDialog.showCustomDialog(
                activity!!,
                resources.getString(R.string.JailbreakWarnings_title),
                resources.getString(R.string.Alert_customKeyboard_android),
                resources.getString(R.string.Button_ok),
                resources.getString(R.string.JailbreakWarnings_close),
                com.cryptron.legacy.presenter.customviews.BRDialogView.BROnClickListener { brDialogView ->
                    val imeManager =
                        applicationContext!!.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imeManager.showInputMethodPicker()
                    brDialogView.dismissWithAnimation()
                },
                com.cryptron.legacy.presenter.customviews.BRDialogView.BROnClickListener { brDialogView -> brDialogView.dismissWithAnimation() },
                null,
                0
            )
        }
    }

    override fun bindView(output: Consumer<E>): Disposable {
        val resources = resources!!
        when (currentModel.mode) {
            RecoveryKey.Mode.WIPE -> {
                title.text = resources.getString(R.string.RecoveryKeyFlow_enterRecoveryKey)
                description.text = resources.getString(R.string.WipeWallet_instruction)
            }
            RecoveryKey.Mode.RESET_PIN -> {
                title.text = resources.getString(R.string.RecoverWallet_header_reset_pin)
                description.text =
                    resources.getString(R.string.RecoverWallet_subheader_reset_pin)
            }
            RecoveryKey.Mode.RECOVER -> Unit
        }

        faq_button.setOnClickListener {
            output.accept(E.OnFaqClicked)
        }
        send_button.setOnClickListener {
            output.accept(E.OnNextClicked)
        }

        // Bind paste event
        wordInputs.first().addEditTextEventListener { event ->
            if (event == com.cryptron.legacy.presenter.customviews.BREdit.EditTextEvent.PASTE) {
                val clipboardText = com.cryptron.tools.manager.BRClipboardManager.getClipboard(activity)
                output.accept(E.OnTextPasted(clipboardText))

                val phrase = clipboardText.split("\\s+".toRegex())
                if (phrase.isNotEmpty()) {
                    wordInputs.zip(phrase)
                        .forEach { (input, word) ->
                            input.setText(word, TextView.BufferType.EDITABLE)
                        }
                }
            }
        }

        // Bind keyboard enter event
        wordInputs.last().setOnEditorActionListener { _, actionId, event ->
            if (event?.keyCode == KeyEvent.KEYCODE_ENTER || actionId == EditorInfo.IME_ACTION_DONE) {
                output.accept(E.OnNextClicked)
            }
            false
        }

        // Bind word input focus event
        wordInputs.forEachIndexed { index, input ->
            input.setOnFocusChangeListener { _, focused ->
                if (focused)
                    output.accept(E.OnFocusedWordChanged(index))
            }
        }

        wordInputs.zip(currentModel.phrase)
            .forEach { (input, word) ->
                input.setText(word, TextView.BufferType.EDITABLE)
            }

        // Bind word input text event
        val watchers = wordInputs.mapIndexed { index, input ->
            createTextWatcher(output, index, input)
        }

        return Disposable {
            wordInputs.zip(watchers)
                .forEach { (input, watcher) ->
                    input.removeTextChangedListener(watcher)
                }
        }
    }

    override fun M.render() {
        ifChanged(M::isLoading) {
            // TODO: Show loading msg
            loading_view.isVisible = it
        }

        ifChanged(M::errors) { errors ->
            wordInputs.zip(errors)
                .forEach { (input, error) ->
                    if (error) {
                        if (input.currentTextColor != errorTextColor)
                            input.setTextColor(errorTextColor)
                    } else {
                        if (input.currentTextColor != normalTextColor)
                            input.setTextColor(normalTextColor)
                    }
                }
        }
    }

    /** Creates a recovery word input text watcher and attaches it to [input]. */
    private fun createTextWatcher(
        output: Consumer<E>,
        index: Int,
        input: EditText
    ) = object : DefaultTextWatcher() {
        override fun afterTextChanged(s: Editable?) {
            val word = s?.toString() ?: ""
            output.accept(E.OnWordChanged(index, word))
        }
    }.also(input::addTextChangedListener)

    override fun handleBack(): Boolean = currentModel.isLoading
}
