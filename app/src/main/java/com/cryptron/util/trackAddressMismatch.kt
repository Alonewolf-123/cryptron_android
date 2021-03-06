/**
 * BreadWallet
 *
 * Created by Drew Carlson <drew.carlson@breadwallet.com> on 2/27/20.
 * Copyright (c) 2020 breadwallet LLC
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
package com.cryptron.util

import com.cryptron.app.BreadApp
import com.cryptron.breadbox.BreadBox
import com.cryptron.breadbox.addressFor
import com.cryptron.breadbox.currencyId
import com.breadwallet.crypto.blockchaindb.BlockchainDb
import com.breadwallet.crypto.blockchaindb.errors.QueryError
import com.cryptron.logger.logError
import com.cryptron.tools.crypto.CryptoHelper.hexEncode
import com.cryptron.tools.crypto.CryptoHelper.keccak256
import com.cryptron.tools.crypto.CryptoHelper.sha256
import com.cryptron.tools.manager.BRSharedPrefs
import com.cryptron.tools.security.BrdUserManager
import com.cryptron.tools.security.CryptoUserManager
import com.cryptron.tools.util.EventUtils
import com.cryptron.tools.util.eth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.platform.interfaces.AccountMetaDataProvider
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transform
import org.kodein.di.erased.instance

private const val ETH_ADDRESS_BYTES = 20

fun ByteArray?.pubKeyToEthAddress(): String? = when {
    this == null || isEmpty() -> null
    else -> {
        val addressBytes = keccak256(sliceArray(1..lastIndex))
            ?.takeLast(ETH_ADDRESS_BYTES)
            ?.toByteArray()
        if (addressBytes?.size == ETH_ADDRESS_BYTES) {
            "0x${hexEncode(addressBytes)}"
        } else null
    }
}

@Suppress("LongMethod", "ReturnCount")
suspend fun BreadApp.trackAddressMismatch(breadBox: BreadBox) {
    val userManager by instance<BrdUserManager>()
    val oldAddressString =
        (userManager as CryptoUserManager).getEthPublicKey().pubKeyToEthAddress() ?: return
    val ethWallet = breadBox.wallet(eth).first()
    val coreAddressOld = ethWallet.addressFor(oldAddressString)
    if (coreAddressOld == null) {
        logError("Failed to get core Address for old eth address.")
        return
    }

    if (!coreAddressOld.toString().equals(ethWallet.target.toString(), true)) {
        val oldEthAddress = coreAddressOld.toString()
        val bdb by instance<BlockchainDb>()
        val metaDataManager by instance<AccountMetaDataProvider>()

        val ethBalance = try {
            asyncApiCall<String, QueryError> {
                bdb.getBalanceAsEth("mainnet", oldEthAddress, this)
            }
        } catch (e: QueryError) {
            logError("Failed to fetch eth balance.", e)
            "unknown"
        }

        val tokenBalances = metaDataManager.enabledWallets()
            .take(1)
            .flatMapMerge { currencyIds ->
                currencyIds.asFlow().transform { currencyId ->
                    val tokenAddress = currencyId.removePrefix("ethereum-mainnet:")
                    val balance = try {
                        asyncApiCall<String, QueryError> {
                            bdb.getBalanceAsTok("mainnet", oldEthAddress, tokenAddress, this)
                        }
                    } catch (e: QueryError) {
                        logError("Failed to fetch token balance", e)
                        "unknown"
                    }
                    val wallet = breadBox.wallets()
                        .mapNotNull { wallets ->
                            wallets.firstOrNull { wallet ->
                                wallet.currencyId.contains(tokenAddress)
                            }
                        }
                        .first()
                    emit(wallet.currency.code to balance)
                }
            }
            .toList()

        val oldAddressHash = hexEncode(sha256(oldEthAddress.toByteArray()) ?: byteArrayOf())
        val rewardsId = BRSharedPrefs.getWalletRewardId()
        val rewardsIdHash = hexEncode(sha256(rewardsId?.toByteArray()) ?: byteArrayOf())

        sendMismatchEvent(oldAddressHash, rewardsIdHash, ethBalance, tokenBalances)
    }
}

private fun sendMismatchEvent(
    ethAddressHash: String,
    rewardsIdHash: String,
    ethBalance: String,
    tokenBalances: List<Pair<String, String>>
) {
    val tokens = tokenBalances.map { (currencyCode, balance) ->
        "has_balance_$currencyCode" to balanceString(balance)
    }
    com.cryptron.tools.util.EventUtils.pushEvent(
        com.cryptron.tools.util.EventUtils.EVENT_PUB_KEY_MISMATCH,
        mapOf(
            com.cryptron.tools.util.EventUtils.EVENT_ATTRIBUTE_REWARDS_ID_HASH to rewardsIdHash,
            com.cryptron.tools.util.EventUtils.EVENT_ATTRIBUTE_ADDRESS_HASH to ethAddressHash,
            "has_balance_eth" to balanceString(ethBalance)
        ) + tokens
    )

    FirebaseCrashlytics.getInstance().apply {
        log("rewards_id_hash = $rewardsIdHash")
        log("old_address_hash = $ethAddressHash")
        log("has_balance_eth = ${balanceString(ethBalance)}")
        tokens.forEach { (key, balance) ->
            log("$key = $balance")
        }
        recordException(IllegalStateException("eth address mismatch"))
    }
}

private fun balanceString(string: String) = when (string) {
    "unknown" -> "unknown"
    "0x0", "0" -> "no"
    else -> "yes"
}
