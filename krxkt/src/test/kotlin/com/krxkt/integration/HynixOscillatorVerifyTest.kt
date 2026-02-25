package com.krxkt.integration

import com.krxkt.KrxStock
import com.krxkt.error.KrxError
import com.krxkt.model.AskBidType
import com.krxkt.model.TradingValueType
import kotlinx.coroutines.runBlocking

/**
 * SK하이닉스 수급 오실레이터 검증
 *
 * 실행:
 * cd kotlin_krx
 * ./gradlew runIntegrationTest -PmainClass=com.krxkt.integration.HynixOscillatorVerifyTestKt
 */

private const val EMA_FAST = 12
private const val EMA_SLOW = 26
private const val EMA_SIGNAL = 9
private const val ROLLING_WINDOW = 5
private const val MARKET_CAP_DIVISOR = 10_000_000_000_000.0

private const val TICKER = "000660"
private const val END_DATE = "20250116"
private const val DEFAULT_ANALYSIS_DAYS = 365L

data class HynixExpectedRow(val date: String, val mcapTril: Double, val oscPct: Double)

val HYNIX_EXPECTED = listOf(
    HynixExpectedRow("20241128", 11.7281, 0.0000),
    HynixExpectedRow("20241129", 11.6408, -0.0110),
    HynixExpectedRow("20241202", 11.5607, -0.0170),
    HynixExpectedRow("20241203", 12.0048, -0.0173),
    HynixExpectedRow("20241204", 12.2304, -0.0048),
    HynixExpectedRow("20241205", 12.5944, 0.0229),
    HynixExpectedRow("20241206", 12.1649, 0.0372),
    HynixExpectedRow("20241209", 12.2960, 0.0527),
    HynixExpectedRow("20241210", 12.4052, 0.0562),
    HynixExpectedRow("20241211", 12.5071, 0.0520),
    HynixExpectedRow("20241212", 12.8201, 0.0423),
    HynixExpectedRow("20241213", 12.7764, 0.0356),
    HynixExpectedRow("20241216", 13.0531, 0.0239),
    HynixExpectedRow("20241217", 13.3952, 0.0209),
    HynixExpectedRow("20241218", 13.3588, 0.0154),
    HynixExpectedRow("20241219", 12.7400, -0.0016),
    HynixExpectedRow("20241220", 12.2668, -0.0212),
    HynixExpectedRow("20241223", 12.3469, -0.0368),
    HynixExpectedRow("20241224", 12.2668, -0.0512),
    HynixExpectedRow("20241226", 12.3833, -0.0487),
    HynixExpectedRow("20241227", 12.7036, -0.0360),
    HynixExpectedRow("20241230", 12.6600, -0.0166),
    HynixExpectedRow("20250102", 12.4634, -0.0078),
    HynixExpectedRow("20250103", 13.2424, 0.0086),
    HynixExpectedRow("20250106", 14.5455, 0.0251),
    HynixExpectedRow("20250107", 14.1960, 0.0335),
    HynixExpectedRow("20250108", 14.1742, 0.0351),
    HynixExpectedRow("20250109", 14.9240, 0.0523),
    HynixExpectedRow("20250110", 14.8148, 0.0517),
    HynixExpectedRow("20250113", 14.1451, 0.0272),
    HynixExpectedRow("20250114", 14.1960, 0.0061),
    HynixExpectedRow("20250115", 14.4290, -0.0037),
    HynixExpectedRow("20250116", 15.2881, -0.0081),
)

data class HynixDailyTrading(
    val date: String,
    val marketCap: Long,
    val foreignNetBuy: Long,
    val instNetBuy: Long
)

data class HynixOscResult(
    val date: String,
    val marketCapTril: Double,
    val oscillator: Double
)

private fun calcEma(values: List<Double>, period: Int): List<Double> {
    if (values.isEmpty()) return emptyList()
    val alpha = 2.0 / (period + 1)
    val result = mutableListOf(values[0])
    for (i in 1 until values.size) {
        result.add(alpha * values[i] + (1.0 - alpha) * result[i - 1])
    }
    return result
}

fun main() = runBlocking {
    println("=" .repeat(80))
    println("SK Hynix Oscillator Verification (kotlin_krx)")
    println("=" .repeat(80))

    val krxStock = KrxStock()

    try {
        // 1. 데이터 수집
        println("\n[1] Fetching KRX data...")

        val allCaps = krxStock.getMarketCap(date = END_DATE)
        val hynixCap = allCaps.find { it.ticker == TICKER }
            ?: throw RuntimeException("SK Hynix ($TICKER) not found")
        val sharesOutstanding = hynixCap.sharesOutstanding
        println("  Shares outstanding: ${"%,d".format(sharesOutstanding)}")

        val endLocal = java.time.LocalDate.of(2025, 1, 16)
        val startLocal = endLocal.minusDays(DEFAULT_ANALYSIS_DAYS)
        val startDate = startLocal.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
        println("  Period: $startDate ~ $END_DATE")

        val ohlcvData = krxStock.getOhlcvByTicker(startDate, END_DATE, TICKER)
        println("  OHLCV: ${ohlcvData.size} days")
        val closePriceMap = ohlcvData.associate { it.date to it.close }

        val tradingData = krxStock.getTradingByInvestor(
            startDate = startDate, endDate = END_DATE, ticker = TICKER,
            valueType = TradingValueType.VALUE, askBidType = AskBidType.NET_BUY
        )
        println("  Investor trading: ${tradingData.size} days")

        // 2. 데이터 병합
        val dailyList = tradingData.mapNotNull { inv ->
            val closePrice = closePriceMap[inv.date] ?: return@mapNotNull null
            HynixDailyTrading(
                date = inv.date,
                marketCap = closePrice * sharesOutstanding,
                foreignNetBuy = inv.foreigner,
                instNetBuy = inv.institutionalTotal
            )
        }.sortedBy { it.date }
        println("  Merged: ${dailyList.size} days (${dailyList.first().date} ~ ${dailyList.last().date})")

        // 검증 시작일 인덱스
        val verifyStartIdx = dailyList.indexOfFirst { it.date >= "20241128" }
        println("  Verify start index: $verifyStartIdx (date: ${dailyList[verifyStartIdx].date})")

        // 3. 전체 기간 5일 롤링 수급비율 계산
        val n = dailyList.size
        val supplyRatios = (0 until n).map { i ->
            val start = maxOf(0, i - ROLLING_WINDOW + 1)
            val f5d = (start..i).sumOf { dailyList[it].foreignNetBuy }
            val i5d = (start..i).sumOf { dailyList[it].instNetBuy }
            val mcap = dailyList[i].marketCap
            if (mcap == 0L) 0.0 else (f5d + i5d).toDouble() / mcap.toDouble()
        }

        // 4. 검증 기간부터 EMA 시작
        val verifySR = supplyRatios.subList(verifyStartIdx, n)
        val verifyDates = dailyList.subList(verifyStartIdx, n).map { it.date }
        val verifyMcaps = dailyList.subList(verifyStartIdx, n).map { it.marketCap }

        val ema12 = calcEma(verifySR, EMA_FAST)
        val ema26 = calcEma(verifySR, EMA_SLOW)
        val macd = ema12.zip(ema26) { e12, e26 -> e12 - e26 }
        val signal = calcEma(macd, EMA_SIGNAL)

        val results = verifySR.indices.map { i ->
            HynixOscResult(
                date = verifyDates[i],
                marketCapTril = verifyMcaps[i] / MARKET_CAP_DIVISOR,
                oscillator = macd[i] - signal[i]
            )
        }
        val resultMap = results.associateBy { it.date }

        // 5. 비교 출력
        println("\n" + "=" .repeat(90))
        println("SK Hynix Verification Result")
        println("=" .repeat(90))

        println("\n  ${"Date".padEnd(12)} ${"ExpMcap".padStart(10)} ${"CalcMcap".padStart(10)} ${"M?".padStart(3)} " +
                "${"ExpOsc(%)".padStart(12)} ${"CalcOsc(%)".padStart(12)} ${"Diff".padStart(12)} ${"O?".padStart(3)}")
        println("  " + "-".repeat(85))

        var mcapMatch = 0; var oscMatch = 0; var total = 0

        for (exp in HYNIX_EXPECTED) {
            total++
            val r = resultMap[exp.date]
            if (r == null) { println("  ${exp.date}  -- no data --"); continue }

            val mcapOk = kotlin.math.abs(r.marketCapTril - exp.mcapTril) < 0.01
            if (mcapOk) mcapMatch++

            val calcOsc = r.oscillator * 100.0
            val diff = calcOsc - exp.oscPct
            val oscOk = kotlin.math.abs(diff) < 0.001
            if (oscOk) oscMatch++

            val mIcon = if (mcapOk) "OK" else "X"
            val oIcon = if (oscOk) "OK" else "X"

            println("  ${exp.date}   ${"%10.4f".format(exp.mcapTril)} ${"%10.4f".format(r.marketCapTril)} $mIcon  " +
                    "${"%12.4f".format(exp.oscPct)} ${"%12.6f".format(calcOsc)} ${"%12.6f".format(diff)} $oIcon")
        }
        println("  " + "-".repeat(85))
        println("  Mcap: $mcapMatch/$total | Osc: $oscMatch/$total")

    } catch (e: KrxError.NetworkError) {
        println("\nERROR: KRX API not accessible: ${e.message}")
    } catch (e: Exception) {
        println("\nERROR: ${e.message}")
        e.printStackTrace()
    } finally {
        krxStock.close()
    }
}
