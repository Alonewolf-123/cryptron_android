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

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat.checkSelfPermission
import com.bluelinelabs.conductor.Controller
import com.cryptron.breadbox.BreadBox
import com.cryptron.breadbox.toSanitizedString
import com.breadwallet.crypto.AddressScheme
import com.cryptron.ext.bindConsumerIn
import com.cryptron.legacy.presenter.entities.CryptoRequest
import com.cryptron.tools.manager.BRClipboardManager
import com.cryptron.tools.qrcode.QRUtils
import com.cryptron.tools.util.btc
import com.cryptron.ui.settings.segwit.LegacyAddress.E
import com.cryptron.ui.settings.segwit.LegacyAddress.F
import com.cryptron.util.CryptoUriParser
import com.cryptron.util.errorHandler
import com.spotify.mobius.Connection
import com.spotify.mobius.functions.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LegacyAddressHandler(
    private val output: Consumer<E>,
    private val breadBox: BreadBox,
    private val cryptoUriParser: CryptoUriParser,
    private val controller: Controller,
    private val showAddressCopiedAnimation: () -> Unit
) : Connection<F>, CoroutineScope {

    override val coroutineContext = SupervisorJob() + Dispatchers.Default + errorHandler()

    init {
        breadBox.wallet(btc)
            .map { wallet ->
                wallet.getTargetForScheme(AddressScheme.BTC_LEGACY)
            }
            .distinctUntilChanged()
            .map { E.OnAddressUpdated(it.toString(), it.toSanitizedString()) }
            .bindConsumerIn(output, this)

        breadBox.wallet(btc)
            .map { it.currency.name }
            .distinctUntilChanged()
            .map { E.OnWalletNameUpdated(it) }
            .bindConsumerIn(output, this)
    }

    override fun accept(effect: F) {
        when (effect) {
            is F.CopyAddressToClipboard -> {
                launch(Dispatchers.Main) {
                    com.cryptron.tools.manager.BRClipboardManager.putClipboard(controller.applicationContext, effect.address)
                    showAddressCopiedAnimation()
                }
            }
            is F.ShareAddress -> {
                launch(Dispatchers.Main) {
                    val context = checkNotNull(controller.applicationContext)
                    val writePerm = checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    if (writePerm == PackageManager.PERMISSION_GRANTED) {
                        val cryptoRequest = com.cryptron.legacy.presenter.entities.CryptoRequest.Builder()
                            .setAddress(effect.address)
                            .build()
                        val cryptoUri = cryptoUriParser.createUrl(btc, cryptoRequest)
                        com.cryptron.tools.qrcode.QRUtils.sendShareIntent(
                            context,
                            cryptoUri.toString(),
                            effect.address,
                            effect.walletName
                        )?.run(controller::startActivity)
                    } else {
                        controller.requestPermissions(
                            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                            com.cryptron.tools.qrcode.QRUtils.WRITE_EXTERNAL_STORAGE_PERMISSION_REQUEST_ID
                        )
                    }
                }
            }
        }
    }

    override fun dispose() {
        coroutineContext.cancel()
    }
}
