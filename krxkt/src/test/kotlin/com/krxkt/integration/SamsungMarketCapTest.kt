package com.krxkt.integration

import com.krxkt.KrxStock
import com.krxkt.api.KrxClient
import com.krxkt.model.Market
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 삼성전자 최근 10 영업일 시가총액 + 종가 조회
 *
 * 실행:
 *   gradlew.bat runIntegrationTest -PmainClass=com.krxkt.integration.SamsungMarketCapTestKt
 */
fun main() = runBlocking {
    val okHttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val client = KrxClient(okHttpClient = okHttp)
    val krxStock = KrxStock(client)
    val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
    val ticker = "005930"

    try {
        val endDate = LocalDate.of(2026, 2, 18)
        val startDate = endDate.minusDays(20)
        val startStr = startDate.format(fmt)
        val endStr = endDate.format(fmt)

        println("======================================================================")
        println("  Samsung Electronics (005930) - Last 10 Business Days")
        println("  Close Price & Market Cap")
        println("  Period: $startStr ~ $endStr")
        println("======================================================================")
        println()

        // 1) OHLCV history for the date list + close prices
        val ohlcvHistory = krxStock.getOhlcvByTicker(startStr, endStr, ticker)
        val recent10 = ohlcvHistory.take(10)

        if (recent10.isEmpty()) {
            println("No data. Check Korean network / VPN access.")
            return@runBlocking
        }

        println(String.format("%-12s %15s %15s %20s %18s",
            "Date", "Close(KRW)", "Volume", "MarketCap(100M)", "Shares"))
        println("-".repeat(85))

        for (entry in recent10.reversed()) {
            val date = entry.date
            val formattedDate = "${date.substring(0,4)}-${date.substring(4,6)}-${date.substring(6,8)}"

            val marketCaps = krxStock.getMarketCap(date, Market.ALL)
            val samsung = marketCaps.find { it.ticker == ticker }

            if (samsung != null && samsung.marketCap > 0) {
                val mcapBillion = samsung.marketCap / 100_000_000
                println(String.format("%-12s %,15d %,15d %,20d %,18d",
                    formattedDate, samsung.close, entry.volume, mcapBillion, samsung.sharesOutstanding))
            } else {
                println(String.format("%-12s %,15d %,15d %20s %18s",
                    formattedDate, entry.close, entry.volume, "N/A", "N/A"))
            }
        }

        println("-".repeat(85))
        println()
        println("Total: ${recent10.size} business days")

    } catch (e: Exception) {
        println("Error: ${e.javaClass.simpleName}: ${e.message}")
        e.printStackTrace()
    } finally {
        krxStock.close()
    }
}
