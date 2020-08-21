/**
 * BreadWallet
 *
 * Created by Ahsan Butt <ahsan.butt@breadwallet.com> on 10/11/19.
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
package com.cryptron.ui.addwallets

import androidx.recyclerview.widget.LinearLayoutManager
import com.cryptron.R
import com.cryptron.tools.util.Utils
import com.cryptron.ui.BaseMobiusController
import com.cryptron.ui.addwallets.AddWallets.E
import com.cryptron.ui.addwallets.AddWallets.F
import com.cryptron.ui.addwallets.AddWallets.M
import com.cryptron.ui.flowbind.clicks
import com.cryptron.ui.flowbind.textChanges
import kotlinx.android.synthetic.main.controller_add_wallets.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import org.kodein.di.direct
import org.kodein.di.erased.instance

class AddWalletsController : BaseMobiusController<M, E, F>() {

    override val layoutId: Int = R.layout.controller_add_wallets

    override val defaultModel = M.createDefault()
    override val init = AddWalletsInit
    override val update = AddWalletsUpdate
    override val flowEffectHandler
        get() = AddWalletsHandler.create(
            checkNotNull(applicationContext),
            direct.instance(),
            direct.instance(),
            direct.instance()
        )

    override fun bindView(modelFlow: Flow<M>): Flow<E> {
        token_list.layoutManager = LinearLayoutManager(checkNotNull(activity))
        search_edit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                com.cryptron.tools.util.Utils.hideKeyboard(activity)
            }
        }

        return merge(
            search_edit.textChanges().map { E.OnSearchQueryChanged(it) },
            back_arrow.clicks().map { E.OnBackClicked },
            bindTokenList(modelFlow)
        ).onCompletion {
            com.cryptron.tools.util.Utils.hideKeyboard(activity)
        }
    }

    private fun bindTokenList(
        modelFlow: Flow<M>
    ) = callbackFlow<E> {
        AddTokenListAdapter(
            context = checkNotNull(activity),
            tokensFlow = modelFlow
                .map { model -> model.tokens }
                .distinctUntilChanged(),
            sendChannel = channel
        ).also(token_list::setAdapter)

        awaitClose { token_list.adapter = null }
    }
}
