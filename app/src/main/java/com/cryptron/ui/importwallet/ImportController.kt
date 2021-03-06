/**
 * BreadWallet
 *
 * Created by Drew Carlson <drew.carlson@breadwallet.com> on 12/3/19.
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
package com.cryptron.ui.importwallet

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.bluelinelabs.conductor.RouterTransaction
import com.cryptron.R
import com.cryptron.tools.util.Link
import com.cryptron.ui.BaseMobiusController
import com.cryptron.ui.controllers.AlertDialogController
import com.cryptron.ui.flowbind.clicks
import com.cryptron.ui.importwallet.Import.E
import com.cryptron.ui.importwallet.Import.F
import com.cryptron.ui.importwallet.Import.M
import com.cryptron.ui.importwallet.Import.M.LoadingState
import com.cryptron.ui.scanner.ScannerController
import kotlinx.android.synthetic.main.controller_import_wallet.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import org.kodein.di.Kodein
import org.kodein.di.direct
import org.kodein.di.erased.bind
import org.kodein.di.erased.instance
import org.kodein.di.erased.singleton

private const val PRIVATE_KEY = "private_key"
private const val PASSWORD_PROTECTED = "password_protected"

private const val CONFIRM_IMPORT_DIALOG = "confirm_import"
private const val IMPORT_SUCCESS_DIALOG = "import_success"

@Suppress("TooManyFunctions")
class ImportController(
    args: Bundle? = null
) : BaseMobiusController<M, E, F>(args),
    ScannerController.Listener,
    AlertDialogController.Listener,
    PasswordController.Listener,
    ImportViewActions {

    constructor(
        privateKey: String,
        isPasswordProtected: Boolean
    ) : this(
        bundleOf(
            PRIVATE_KEY to privateKey,
            PASSWORD_PROTECTED to isPasswordProtected
        )
    )

    override val layoutId = R.layout.controller_import_wallet

    override val init = ImportInit
    override val update = ImportUpdate
    override val defaultModel = Import.M.createDefault(
        privateKey = argOptional(PRIVATE_KEY),
        isPasswordProtected = arg(PASSWORD_PROTECTED, false)
    )

    override val kodein by Kodein.lazy {
        extend(super.kodein)

        bind<WalletImporter>() with singleton { WalletImporter() }
    }

    override val flowEffectHandler
        get() = ImportHandler.create(
            direct.instance(),
            direct.instance(),
            direct.instance(),
            view = this
        )

    override fun bindView(modelFlow: Flow<M>): Flow<E> {
        modelFlow
            .map { it.loadingState }
            .distinctUntilChanged()
            .onEach { state ->
                val isLoading = state != LoadingState.IDLE
                // Disable navigation
                scan_button.isEnabled = !isLoading
                faq_button.isEnabled = !isLoading
                close_button.isEnabled = !isLoading

                // Set loading visibility
                scan_button.isGone = isLoading
                progressBar.isVisible = isLoading
                label_import_status.isVisible = isLoading

                // Set loading message
                val messageId = when (state) {
                    LoadingState.ESTIMATING,
                    LoadingState.VALIDATING -> R.string.Import_checking
                    LoadingState.SUBMITTING -> R.string.Import_importing
                    else -> null
                }
                messageId?.let(label_import_status::setText)
            }
            .launchIn(uiBindScope)

        return merge(
            close_button.clicks().map { E.OnCloseClicked },
            faq_button.clicks().map { E.OnFaqClicked },
            scan_button.clicks().map { E.OnScanClicked }
        )
    }

    override fun handleBack(): Boolean {
        return currentModel.isLoading
    }

    override fun onPasswordConfirmed(password: String) {
        E.OnPasswordEntered(password)
            .run(eventConsumer::accept)
    }

    override fun onPasswordCancelled() {
        E.OnImportCancel
            .run(eventConsumer::accept)
    }

    override fun onLinkScanned(link: Link) {
        if (link is Link.ImportWallet) {
            E.OnKeyScanned(link.privateKey, link.passwordProtected)
                .run(eventConsumer::accept)
        }
    }

    override fun onPositiveClicked(dialogId: String, controller: AlertDialogController) {
        when (dialogId) {
            CONFIRM_IMPORT_DIALOG -> E.OnImportConfirm
            IMPORT_SUCCESS_DIALOG -> E.OnCloseClicked
            else -> null
        }?.run(eventConsumer::accept)
    }

    override fun onDismissed(dialogId: String, controller: AlertDialogController) {
        when (dialogId) {
            CONFIRM_IMPORT_DIALOG -> E.OnImportCancel
            IMPORT_SUCCESS_DIALOG -> E.OnCloseClicked
            else -> null
        }?.run(eventConsumer::accept)
    }

    override fun showNoWalletsEnabled() {
        router.popController(this)
    }

    override fun showKeyInvalid() {
        val res = checkNotNull(resources)
        val controller = AlertDialogController(
            message = res.getString(R.string.Import_Error_notValid),
            positiveText = res.getString(R.string.Button_ok)
        )
        router.pushController(RouterTransaction.with(controller))
    }

    override fun showPasswordInvalid() {
        val res = checkNotNull(resources)
        val controller = AlertDialogController(
            message = res.getString(R.string.Import_wrongPassword),
            positiveText = res.getString(R.string.Button_ok)
        )
        router.pushController(RouterTransaction.with(controller))
    }

    override fun showNoBalance() {
        val res = checkNotNull(resources)
        val controller = AlertDialogController(
            title = res.getString(R.string.Import_title),
            message = res.getString(R.string.Import_Error_empty),
            positiveText = res.getString(R.string.Button_ok)
        )
        router.pushController(RouterTransaction.with(controller))
    }

    override fun showBalanceTooLow() {
        val res = checkNotNull(resources)
        val controller = AlertDialogController(
            title = res.getString(R.string.Import_title),
            message = res.getString(R.string.Import_Error_highFees),
            positiveText = res.getString(R.string.Button_ok)
        )
        router.pushController(RouterTransaction.with(controller))
    }

    override fun showConfirmImport(receiveAmount: String, feeAmount: String) {
        val res = checkNotNull(resources)
        val controller = AlertDialogController(
            title = res.getString(R.string.Import_title),
            message = res.getString(
                R.string.Import_confirm,
                receiveAmount,
                feeAmount
            ),
            positiveText = res.getString(R.string.Import_importButton),
            dialogId = CONFIRM_IMPORT_DIALOG
        )
        controller.targetController = this@ImportController
        router.pushController(RouterTransaction.with(controller))
    }

    override fun showImportSuccess() {
        val res = checkNotNull(resources)
        val controller = AlertDialogController(
            title = res.getString(R.string.Import_success),
            message = res.getString(R.string.Import_SuccessBody),
            positiveText = res.getString(R.string.Button_ok),
            dialogId = IMPORT_SUCCESS_DIALOG
        )
        controller.targetController = this@ImportController
        router.pushController(RouterTransaction.with(controller))
    }

    override fun showImportFailed() {
        val res = checkNotNull(resources)
        val controller = AlertDialogController(
            title = res.getString(R.string.Import_title),
            message = res.getString(R.string.Import_Error_signing),
            positiveText = res.getString(R.string.Button_ok)
        )
        router.pushController(RouterTransaction.with(controller))
    }

    override fun showPasswordInput() {
        val controller = PasswordController()
        controller.targetController = this@ImportController
        router.pushController(RouterTransaction.with(controller))
    }
}
