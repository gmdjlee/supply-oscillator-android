package com.krxkt.integration

import com.krxkt.KrxStock
import com.krxkt.error.KrxError
import com.krxkt.model.AskBidType
import com.krxkt.model.TradingValueType
import kotlinx.coroutines.runBlocking

/**
 * 삼성전자 수급 오실레이터 검증
 *
 * 핵심 시나리오:
 * E-1: 전체기간 5일롤링 + 2024-11-28부터 EMA 시작 (mcap 원본)
 * E-2: 전체기간 5일롤링 + 2024-11-28부터 EMA 시작 (mcap÷10)
 * E-3: E-2와 동일 + 오실레이터×100 비교
 *
 * 실행:
 * cd kotlin_krx
 * ./gradlew runIntegrationTest -PmainClass=com.krxkt.integration.SamsungOscillatorVerifyTestKt
 */

private const val EMA_FAST = 12
private const val EMA_SLOW = 26
private const val EMA_SIGNAL = 9
private const val ROLLING_WINDOW = 5
private const val MARKET_CAP_DIVISOR = 10_000_000_000_000.0  // KRX 시가총액 기준 (10^13)

private const val TICKER = "005930"
private const val END_DATE = "20241224"
private const val DEFAULT_ANALYSIS_DAYS = 365L

data class ExpectedRow(val date: String, val mcapTril: Double, val oscPct: Double)

val EXPECTED = listOf(
    ExpectedRow("20241128", 33.1323, 0.0000),
    ExpectedRow("20241129", 32.3562, -0.0028),
    ExpectedRow("20241202", 31.9980, -0.0090),
    ExpectedRow("20241203", 31.9980, -0.0131),
    ExpectedRow("20241204", 31.6995, -0.0109),
    ExpectedRow("20241205", 32.0577, -0.0051),
    ExpectedRow("20241206", 32.2965, 0.0055),
    ExpectedRow("20241209", 31.8786, 0.0151),
    ExpectedRow("20241210", 32.2368, 0.0208),
    ExpectedRow("20241211", 32.2368, 0.0238),
    ExpectedRow("20241212", 33.3711, 0.0262),
    ExpectedRow("20241213", 33.4905, 0.0229),
    ExpectedRow("20241216", 33.1920, 0.0153),
    ExpectedRow("20241217", 32.3562, 0.0070),
    ExpectedRow("20241218", 32.7741, 0.0036),
    ExpectedRow("20241219", 31.6995, -0.0107),
    ExpectedRow("20241220", 31.6398, -0.0243),
    ExpectedRow("20241223", 31.9383, -0.0287),
    ExpectedRow("20241224", 32.4756, -0.0237),
)

data class DailyTrading(
    val date: String,
    val marketCap: Long,
    val foreignNetBuy: Long,
    val instNetBuy: Long
)

data class OscillatorResult(
    val date: String,
    val marketCapTril: Double,
    val oscillator: Double,
    val supplyRatio: Double,
    val macd: Double,
    val signal: Double
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

/**
 * 전체 기간에 대해 5일 롤링합 + 수급비율 계산 (EMA 이전 단계)
 */
private fun calcSupplyRatios(
    dailyData: List<DailyTrading>,
    mcapDivisor: Long = 1  // market cap을 이 값으로 나눔
): List<Pair<DailyTrading, Double>> {
    val n = dailyData.size
    return (0 until n).map { i ->
        val start = maxOf(0, i - ROLLING_WINDOW + 1)
        val f5d = (start..i).sumOf { dailyData[it].foreignNetBuy }
        val i5d = (start..i).sumOf { dailyData[it].instNetBuy }
        val mcap = dailyData[i].marketCap / mcapDivisor
        val sr = if (mcap == 0L) 0.0 else (f5d + i5d).toDouble() / mcap.toDouble()
        Pair(dailyData[i], sr)
    }
}

/**
 * 수급비율 리스트로부터 EMA → MACD → Signal → Oscillator 계산
 */
private fun calcOscillatorFromRatios(
    supplyRatios: List<Double>,
    dates: List<String>,
    marketCaps: List<Long>,
    mcapDisplayDivisor: Double = MARKET_CAP_DIVISOR
): List<OscillatorResult> {
    val ema12 = calcEma(supplyRatios, EMA_FAST)
    val ema26 = calcEma(supplyRatios, EMA_SLOW)
    val macd = ema12.zip(ema26) { e12, e26 -> e12 - e26 }
    val signal = calcEma(macd, EMA_SIGNAL)

    return supplyRatios.indices.map { i ->
        OscillatorResult(
            date = dates[i],
            marketCapTril = marketCaps[i] / mcapDisplayDivisor,
            oscillator = macd[i] - signal[i],
            supplyRatio = supplyRatios[i],
            macd = macd[i],
            signal = signal[i]
        )
    }
}

fun main() = runBlocking {
    println("=" .repeat(80))
    println("Samsung Oscillator Verification (kotlin_krx)")
    println("=" .repeat(80))

    val krxStock = KrxStock()

    try {
        // ============================================================
        // 1. 데이터 수집
        // ============================================================
        println("\n[1] Fetching KRX data...")

        val allCaps = krxStock.getMarketCap(date = END_DATE)
        val samsungCap = allCaps.find { it.ticker == TICKER }
            ?: throw RuntimeException("Samsung ($TICKER) not found")
        val sharesOutstanding = samsungCap.sharesOutstanding
        println("  Shares outstanding: ${"%,d".format(sharesOutstanding)}")

        val endLocal = java.time.LocalDate.of(2024, 12, 24)
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

        // ============================================================
        // 2. 데이터 병합
        // ============================================================
        val dailyList = tradingData.mapNotNull { inv ->
            val closePrice = closePriceMap[inv.date] ?: return@mapNotNull null
            DailyTrading(
                date = inv.date,
                marketCap = closePrice * sharesOutstanding,
                foreignNetBuy = inv.foreigner,
                instNetBuy = inv.institutionalTotal
            )
        }.sortedBy { it.date }
        println("  Merged: ${dailyList.size} days (${dailyList.first().date} ~ ${dailyList.last().date})")

        // 검증 시작일 인덱스 찾기
        val verifyStartIdx = dailyList.indexOfFirst { it.date >= "20241128" }
        println("  Verify start index: $verifyStartIdx (date: ${dailyList[verifyStartIdx].date})")

        // ============================================================
        // 시나리오 E: 핵심 - 전체기간 5일롤링 + 특정일부터 EMA 시작
        // ============================================================
        println("\n" + "=" .repeat(80))
        println("SCENARIO E: Full 5-day rolling + EMA starts from 2024-11-28")
        println("  - 5-day rolling computed from FULL dataset (correct history)")
        println("  - EMA/MACD/Signal/Oscillator starts fresh from 2024-11-28")
        println("=" .repeat(80))

        // 전체 기간 5일롤링 수급비율 계산 (원본 mcap)
        val fullRatios = calcSupplyRatios(dailyList, mcapDivisor = 1)
        // 검증 기간만 추출
        val verifyRatios = fullRatios.subList(verifyStartIdx, fullRatios.size)
        val verifySR = verifyRatios.map { it.second }
        val verifyDates = verifyRatios.map { it.first.date }
        val verifyMcaps = verifyRatios.map { it.first.marketCap }

        // E-1: 원본 mcap
        println("\n--- E-1: Original mcap, osc×100 ---")
        val e1 = calcOscillatorFromRatios(verifySR, verifyDates, verifyMcaps)
        val e1Map = e1.associateBy { it.date }
        printDetailedComparison(e1Map, "E-1", mcapDivisor = MARKET_CAP_DIVISOR, oscMultiplier = 100.0)

        // E-2: mcap÷10으로 수급비율 재계산 + EMA 시작
        println("\n--- E-2: mcap÷10, osc×100 ---")
        val fullRatiosDiv10 = calcSupplyRatios(dailyList, mcapDivisor = 10)
        val verifyRatiosDiv10 = fullRatiosDiv10.subList(verifyStartIdx, fullRatiosDiv10.size)
        val verifySRDiv10 = verifyRatiosDiv10.map { it.second }
        val verifyMcapsDiv10 = verifyRatiosDiv10.map { it.first.marketCap / 10 }
        val e2 = calcOscillatorFromRatios(verifySRDiv10, verifyDates, verifyMcapsDiv10,
            mcapDisplayDivisor = MARKET_CAP_DIVISOR)
        val e2Map = e2.associateBy { it.date }
        printDetailedComparison(e2Map, "E-2", mcapDivisor = MARKET_CAP_DIVISOR, oscMultiplier = 100.0)

        // E-3: 원본 mcap이지만 오실레이터×1000으로 비교 (스케일 탐색)
        println("\n--- E-3: Original mcap, osc×1000 ---")
        printDetailedComparison(e1Map, "E-3", mcapDivisor = MARKET_CAP_DIVISOR, oscMultiplier = 1000.0)

        // E-4: 원본 mcap, EMA 전체기간 시작, 오실레이터×100 비교
        println("\n--- E-4: Full period EMA (original mcap), osc×100 ---")
        val fullSR = fullRatios.map { it.second }
        val fullDates = fullRatios.map { it.first.date }
        val fullMcaps = fullRatios.map { it.first.marketCap }
        val e4full = calcOscillatorFromRatios(fullSR, fullDates, fullMcaps)
        val e4Map = e4full.associateBy { it.date }
        printDetailedComparison(e4Map, "E-4", mcapDivisor = MARKET_CAP_DIVISOR, oscMultiplier = 100.0)

        // ============================================================
        // 수급비율 상세 비교
        // ============================================================
        println("\n" + "=" .repeat(80))
        println("Supply Ratio Detail (around verification period)")
        println("=" .repeat(80))
        println("\n  ${"Date".padEnd(12)} ${"Foreign5d".padStart(18)} ${"Inst5d".padStart(18)} " +
                "${"SR(orig)".padStart(18)} ${"SR(÷10)".padStart(18)} ${"mcap(T)".padStart(12)}")
        println("  " + "-".repeat(90))

        for (i in maxOf(0, verifyStartIdx - 3)..minOf(dailyList.size - 1, verifyStartIdx + 18)) {
            val origPair = fullRatios[i]
            val div10Pair = fullRatiosDiv10[i]
            val d = origPair.first
            val mark = if (d.date in EXPECTED.map { it.date }.toSet()) "*" else " "
            println("  $mark${d.date}  ${"%,18d".format(getF5d(dailyList, i))} ${"%,18d".format(getI5d(dailyList, i))} " +
                    "${"%18.12f".format(origPair.second)} ${"%18.10f".format(div10Pair.second)} " +
                    "${"%12.4f".format(d.marketCap / MARKET_CAP_DIVISOR)}")
        }

        // ============================================================
        // 역산: 기대 오실레이터로부터 수급비율 변화 추정
        // ============================================================
        println("\n" + "=" .repeat(80))
        println("Reverse Engineering: Expected oscillator implies these supply ratios")
        println("=" .repeat(80))
        reverseEngineerExpected()

        // ============================================================
        // Raw investor data for verification dates
        // ============================================================
        println("\n" + "=" .repeat(80))
        println("Raw daily investor data for verification dates")
        println("=" .repeat(80))
        println("\n  ${"Date".padEnd(12)} ${"ForeignDaily".padStart(18)} ${"InstDaily".padStart(18)} " +
                "${"Close".padStart(10)} ${"mcap(won)".padStart(22)}")
        println("  " + "-".repeat(85))
        val verifyDateSet = EXPECTED.map { it.date }.toSet()
        for (d in dailyList) {
            if (d.date in verifyDateSet) {
                val close = closePriceMap[d.date] ?: 0L
                println("  ${d.date}   ${"%,18d".format(d.foreignNetBuy)} ${"%,18d".format(d.instNetBuy)} " +
                        "${"%,10d".format(close)} ${"%,22d".format(d.marketCap)}")
            }
        }

    } catch (e: KrxError.NetworkError) {
        println("\nERROR: KRX API not accessible: ${e.message}")
    } catch (e: Exception) {
        println("\nERROR: ${e.message}")
        e.printStackTrace()
    } finally {
        krxStock.close()
    }
}

private fun getF5d(dailyList: List<DailyTrading>, i: Int): Long {
    val start = maxOf(0, i - ROLLING_WINDOW + 1)
    return (start..i).sumOf { dailyList[it].foreignNetBuy }
}

private fun getI5d(dailyList: List<DailyTrading>, i: Int): Long {
    val start = maxOf(0, i - ROLLING_WINDOW + 1)
    return (start..i).sumOf { dailyList[it].instNetBuy }
}

private fun printDetailedComparison(
    resultMap: Map<String, OscillatorResult>,
    label: String,
    mcapDivisor: Double,
    oscMultiplier: Double
) {
    println("\n  ${"Date".padEnd(12)} ${"ExpMcap".padStart(10)} ${"CalcMcap".padStart(10)} ${"M?".padStart(3)} " +
            "${"ExpOsc(%)".padStart(12)} ${"CalcOsc".padStart(12)} ${"Diff".padStart(12)} ${"O?".padStart(3)}")
    println("  " + "-".repeat(85))

    var mcapMatch = 0; var oscMatch = 0; var total = 0

    for (exp in EXPECTED) {
        total++
        val r = resultMap[exp.date]
        if (r == null) { println("  ${exp.date}  -- no data --"); continue }

        val mcapOk = kotlin.math.abs(r.marketCapTril - exp.mcapTril) < 0.01
        if (mcapOk) mcapMatch++

        val calcOsc = r.oscillator * oscMultiplier
        val diff = calcOsc - exp.oscPct
        val oscOk = kotlin.math.abs(diff) < 0.001
        if (oscOk) oscMatch++

        val mIcon = if (mcapOk) "OK" else "X"
        val oIcon = if (oscOk) "OK" else "X"

        println("  ${exp.date}   ${"%10.4f".format(exp.mcapTril)} ${"%10.4f".format(r.marketCapTril)} $mIcon  " +
                "${"%12.4f".format(exp.oscPct)} ${"%12.6f".format(calcOsc)} ${"%12.6f".format(diff)} $oIcon")
    }
    println("  " + "-".repeat(85))
    println("  [$label] Mcap: $mcapMatch/$total | Osc: $oscMatch/$total (multiplier: ×$oscMultiplier)")
}

/**
 * 기대 오실레이터 값으로부터 수급비율을 역산
 */
private fun reverseEngineerExpected() {
    val alpha12 = 2.0 / (EMA_FAST + 1)
    val alpha26 = 2.0 / (EMA_SLOW + 1)
    val alphaS = 2.0 / (EMA_SIGNAL + 1)

    val oscRaw = EXPECTED.map { it.oscPct / 100.0 }  // % → raw
    val n = oscRaw.size

    // 역산: oscillator → MACD, Signal
    val macd = DoubleArray(n)
    val signal = DoubleArray(n)
    macd[0] = 0.0
    signal[0] = 0.0

    for (i in 1 until n) {
        // signal[i] = alphaS * macd[i] + (1-alphaS) * signal[i-1]
        // osc[i] = macd[i] - signal[i]
        // osc[i] = macd[i] - alphaS * macd[i] - (1-alphaS) * signal[i-1]
        // osc[i] = (1-alphaS) * macd[i] - (1-alphaS) * signal[i-1]
        // osc[i] = (1-alphaS) * (macd[i] - signal[i-1])
        // macd[i] = osc[i] / (1-alphaS) + signal[i-1]
        macd[i] = oscRaw[i] / (1.0 - alphaS) + signal[i - 1]
        signal[i] = macd[i] - oscRaw[i]
    }

    // 역산: MACD → EMA12, EMA26 → supply ratio
    // ema12[0] = ema26[0] = sr[0]
    // MACD[i] = ema12[i] - ema26[i]
    // ema12[i] = alpha12 * sr[i] + (1-alpha12) * ema12[i-1]
    // ema26[i] = alpha26 * sr[i] + (1-alpha26) * ema26[i-1]
    // MACD[i] = (alpha12 - alpha26) * sr[i] + (1-alpha12) * ema12[i-1] - (1-alpha26) * ema26[i-1]
    // sr[i] = (MACD[i] - (1-alpha12)*ema12[i-1] + (1-alpha26)*ema26[i-1]) / (alpha12 - alpha26)

    // We can't determine sr[0] from MACD[0]=0 alone (any sr[0] works).
    // But we can determine sr differences.
    println("\n  Implied MACD/Signal from expected oscillator:")
    println("  ${"Date".padEnd(12)} ${"ExpOsc(%)".padStart(12)} ${"MACD(raw)".padStart(18)} ${"Signal(raw)".padStart(18)}")
    println("  " + "-".repeat(65))
    for (i in 0 until n) {
        println("  ${EXPECTED[i].date}   ${"%12.4f".format(EXPECTED[i].oscPct)} " +
                "${"%18.12f".format(macd[i])} ${"%18.12f".format(signal[i])}")
    }

    // sr[1] - sr[0] 계산
    // MACD[1] = (alpha12 - alpha26) * (sr[1] - sr[0])
    val srDiff01 = macd[1] / (alpha12 - alpha26)
    println("\n  Implied sr[1]-sr[0] = ${"%18.12f".format(srDiff01)}")
    println("  This means supply ratio should DECREASE if negative, INCREASE if positive")
}
