/**
 * BreadWallet
 *
 * Created by Pablo Budelli <pablo.budelli@breadwallet.com> on 6/7/19.
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
package com.cryptron.ui.notification

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.cryptron.R
import com.cryptron.ext.viewModel
import com.cryptron.legacy.presenter.activities.util.BRActivity
import com.cryptron.model.InAppMessage
import com.cryptron.tools.util.asLink
import com.cryptron.ui.MainActivity
import com.squareup.picasso.Picasso
import kotlinx.android.synthetic.main.activity_in_app_notification.*

/**
 * Screen used to display an in-app notification.
 */
class InAppNotificationActivity : BRActivity() {

    companion object {
        private const val EXT_NOTIFICATION = "com.cryptron.ui.notification.EXT_NOTIFICATION"

        fun start(context: Context, notification: InAppMessage) {
            val intent = Intent(context, InAppNotificationActivity::class.java).apply {
                putExtra(EXT_NOTIFICATION, notification)
            }
            context.startActivity(intent)
        }
    }

    private val viewModel by viewModel {
        InAppNotificationViewModel(intent.getParcelableExtra(EXT_NOTIFICATION))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_in_app_notification)

        close_button.setOnClickListener {
            onBackPressed()
        }
        notification_btn.setOnClickListener {
            viewModel.markAsRead(true)
            val actionUrl = viewModel.notification.actionButtonUrl
            if (!actionUrl.isNullOrEmpty()) {
                if (actionUrl.asLink() != null) {
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_DATA, actionUrl)
                        .run(this::startActivity)
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(actionUrl)))
                }
            }
            finish()
        }

        notification_title.text = viewModel.notification.title
        notification_body.text = viewModel.notification.body
        notification_btn.text = viewModel.notification.actionButtonText
        Picasso.get().load(viewModel.notification.imageUrl).into(notification_image)

        viewModel.markAsShown()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        viewModel.markAsRead(false)
    }
}
