/**
 * BreadWallet
 * <p/>
 * Created by Drew Carlson <drew.carlson@breadwallet.com> on 6/2/2019.
 * Copyright (c) 2019 breadwallet LLC
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cryptron.legacy.pricealert

import android.app.Activity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import com.cryptron.R
import com.cryptron.ext.bindCreated
import com.cryptron.ext.viewModel
import com.cryptron.legacy.presenter.activities.util.BRActivity
import com.cryptron.legacy.pricealert.SelectAlertCryptoActivity.Companion.OUTPUT_TOKEN_SYMBOL
import com.cryptron.util.DefaultTextWatcher
import kotlinx.android.synthetic.main.activity_select_alert_crypto.*
import org.kodein.di.android.closestKodein
import org.kodein.di.direct
import org.kodein.di.erased.instance

/**
 * Displays a list of the user's wallets and finishes with
 * a result containing [OUTPUT_TOKEN_SYMBOL] upon user
 * selection.
 */
class SelectAlertCryptoActivity : BRActivity() {

    companion object {
        private val TAG = SelectAlertCryptoActivity::class.java.simpleName

        const val OUTPUT_TOKEN_SYMBOL = "TOKEN_SYMBOL"

        fun openForResult(activity: Activity, requestCode: Int) {
            val intent = Intent(activity, SelectAlertCryptoActivity::class.java)
            activity.startActivityForResult(intent, requestCode)
        }
    }

    private val kodein by closestKodein { applicationContext }

    private val viewModel by viewModel<SelectAlertCryptoViewModel>(kodein.direct.instance())

    private val adapter by bindCreated {
        SelectAlertTokenAdapter(this, viewModel::setSelectedCrypto)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_alert_crypto)

        token_list.adapter = adapter
        token_list.layoutManager = LinearLayoutManager(this)

        back_arrow.setOnClickListener { onBackPressed() }
        search_edit.addTextChangedListener(object : DefaultTextWatcher() {
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setFilterQuery(search_edit.text?.toString())
            }
        })
    }

    override fun onResume() {
        super.onResume()

        val owner: LifecycleOwner = this
        viewModel.apply {
            getTokenItems().observe(owner, Observer { tokenItems ->
                adapter.tokenItems = tokenItems!!
            })
            getSelectedCrypto().observe(owner, Observer { token ->
                setResult(RESULT_OK, Intent().apply {
                    putExtra(OUTPUT_TOKEN_SYMBOL, token!!.symbol)
                })
                finish()
                overridePendingTransition(R.anim.enter_from_left, R.anim.exit_to_right)
            })
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.enter_from_left, R.anim.exit_to_right)
    }
}
