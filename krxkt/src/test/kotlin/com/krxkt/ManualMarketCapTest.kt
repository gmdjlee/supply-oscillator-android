package com.krxkt

import com.krxkt.api.KrxClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.test.Test

/**
 * Manual integration test to verify MarketCap API response
 *
 * This test calls the real KRX API to check what sharesOutstanding value is returned
 */
class ManualMarketCapTest {

    @Test
    fun testRealMarketCapApi() = runTest {
    val client = KrxClient(
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    )
    val krxStock = KrxStock(client)

    // Use last Friday (2026-02-14) or a specific business day
    val lastFriday = LocalDate.of(2026, 2, 14)
    val dateStr = lastFriday.format(DateTimeFormatter.ofPattern("yyyyMMdd"))

    println("Testing getMarketCap for date: $dateStr")
    println("Market: ALL")
    println("=" .repeat(60))

    try {
        println("Calling krxStock.getMarketCap...")
        val marketCaps = krxStock.getMarketCap(dateStr, com.krxkt.model.Market.ALL)
        println("API call successful!")
        println("Total records returned: ${marketCaps.size}")
        println()

        // Find Samsung Electronics (005930)
        val samsung = marketCaps.find { it.ticker == "005930" }

        if (samsung != null) {
            println("Found Samsung Electronics (005930):")
            println("  ticker: ${samsung.ticker}")
            println("  name: ${samsung.name}")
            println("  close: ${samsung.close}")
            println("  changeRate: ${samsung.changeRate}")
            println("  marketCap: ${samsung.marketCap}")
            println("  sharesOutstanding: ${samsung.sharesOutstanding}")
            println()

            if (samsung.sharesOutstanding == 0L) {
                println("⚠️  WARNING: sharesOutstanding is 0!")
                println("   This will cause marketCap calculation to fail.")
                println()
                println("   Fallback calculation:")
                if (samsung.marketCap > 0 && samsung.close > 0) {
                    val calculated = samsung.marketCap / samsung.close
                    println("   marketCap / close = $calculated")
                } else {
                    println("   Cannot calculate: marketCap=${samsung.marketCap}, close=${samsung.close}")
                }
            } else {
                println("✓  sharesOutstanding has valid value")
                println()
                println("   Verification:")
                val calculatedMarketCap = samsung.close * samsung.sharesOutstanding
                println("   close * sharesOutstanding = $calculatedMarketCap")
                println("   API marketCap = ${samsung.marketCap}")
                val diff = kotlin.math.abs(calculatedMarketCap - samsung.marketCap)
                val diffPercent = (diff.toDouble() / samsung.marketCap) * 100
                println("   Difference: $diff (${String.format("%.2f", diffPercent)}%)")
            }
        } else {
            println("❌ Samsung Electronics (005930) not found in results")
            println()
            println("Sample tickers from response:")
            marketCaps.take(10).forEach {
                println("  ${it.ticker} - ${it.name}")
            }
        }

    } catch (e: Exception) {
        println("❌ Error calling getMarketCap:")
        println("   ${e.javaClass.simpleName}: ${e.message}")
        e.printStackTrace()
    } finally {
        krxStock.close()
    }

        println()
        println("=" .repeat(60))
        println("Test completed")
    }
}
