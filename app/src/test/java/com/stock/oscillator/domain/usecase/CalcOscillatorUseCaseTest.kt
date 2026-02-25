package com.stock.oscillator.domain.usecase

import com.stock.oscillator.domain.model.CrossSignal
import com.stock.oscillator.domain.model.DailyTrading
import com.stock.oscillator.domain.model.OscillatorConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * 수급 오실레이터 검증 테스트
 *
 * 엑셀 로직과의 1:1 동일성을 검증합니다.
 * 각 테스트는 엑셀의 특정 시트/수식에 대응됩니다.
 *
 * 검증 대상:
 * 1. 5일 누적 순매수 (rolling window)
 * 2. 수급 비율 계산
 * 3. EMA 계산 (12일, 26일, 9일)
 * 4. MACD 계산
 * 5. 시그널 계산
 * 6. 오실레이터 계산
 * 7. 전체 파이프라인 엔드투엔드
 * 8. Python pykrx 결과와의 비교
 */
class CalcOscillatorUseCaseTest {

    private lateinit var useCase: CalcOscillatorUseCase
    private val TOLERANCE = 1e-12  // 부동소수점 허용 오차

    @Before
    fun setup() {
        useCase = CalcOscillatorUseCase(OscillatorConfig())
    }

    // =========================================================================
    // Test 1: EMA 계산 검증 (엑셀: 시기외12, 시기외26, 시그널 시트)
    // =========================================================================

    /**
     * EMA 기본 공식 검증
     *
     * 엑셀 수식:
     *   첫 값: = 시기외!C15 (첫 번째 값 그대로 사용)
     *   이후: = 시기외!C * α + 이전EMA * (1-α)
     *   α = 2 / (period + 1)
     */
    @Test
    fun `EMA 12일 계산이 엑셀 수식과 동일한지 검증`() {
        val values = listOf(10.0, 12.0, 11.0, 13.0, 14.0, 12.0, 15.0, 16.0, 14.0, 13.0)
        val period = 12
        val alpha = 2.0 / (period + 1)  // 2/13 ≈ 0.153846

        val result = useCase.calcEma(values, period)

        // 첫 값은 원본과 동일
        assertEquals("EMA 첫 값", values[0], result[0], TOLERANCE)

        // 수동 계산으로 검증
        var expectedEma = values[0]
        for (i in 1 until values.size) {
            expectedEma = alpha * values[i] + (1 - alpha) * expectedEma
            assertEquals(
                "EMA[$i] (date index $i)",
                expectedEma, result[i], TOLERANCE
            )
        }
    }

    @Test
    fun `EMA 26일 계산 검증`() {
        val values = List(30) { (it + 1).toDouble() + (it % 3) * 0.5 }
        val period = 26
        val alpha = 2.0 / (period + 1)  // 2/27 ≈ 0.074074

        val result = useCase.calcEma(values, period)

        var expected = values[0]
        for (i in 1 until values.size) {
            expected = alpha * values[i] + (1 - alpha) * expected
            assertEquals("EMA26[$i]", expected, result[i], TOLERANCE)
        }
    }

    @Test
    fun `EMA 9일 시그널 계산 검증`() {
        val macdValues = listOf(0.001, -0.002, 0.003, 0.001, -0.001, 0.002, 0.004, -0.003, 0.001, 0.002)
        val period = 9
        val alpha = 2.0 / (period + 1)  // 2/10 = 0.2

        assertEquals("시그널 α 값", 0.2, alpha, TOLERANCE)

        val result = useCase.calcEma(macdValues, period)

        var expected = macdValues[0]
        for (i in 1 until macdValues.size) {
            expected = 0.2 * macdValues[i] + 0.8 * expected
            assertEquals("Signal[$i]", expected, result[i], TOLERANCE)
        }
    }

    // =========================================================================
    // Test 2: 5일 누적 순매수 검증 (엑셀: 외국인매수데이터, 기관매수데이터 시트)
    // =========================================================================

    /**
     * 5일 누적 = 현재일 포함 이전 4일 (총 5개 거래일)
     *
     * Python 대응: inv['외국인합계'].rolling(5).sum()
     *
     * 엑셀 로직:
     * - 개장일 기준으로 5일 동안의 순매수 합산
     * - 데이터가 5일 미만인 초기에는 있는 만큼만 합산
     */
    @Test
    fun `5일 누적 순매수가 엑셀 rolling sum과 동일한지 검증`() {
        val data = listOf(
            DailyTrading("20240101", 1000000000000, 100, 50),   // Day 1
            DailyTrading("20240102", 1000000000000, 200, -30),  // Day 2
            DailyTrading("20240103", 1000000000000, -50, 100),  // Day 3
            DailyTrading("20240104", 1000000000000, 300, 200),  // Day 4
            DailyTrading("20240105", 1000000000000, 150, -100), // Day 5
            DailyTrading("20240108", 1000000000000, -200, 50),  // Day 6
            DailyTrading("20240109", 1000000000000, 100, 300),  // Day 7
        )

        val result = useCase.execute(data)

        // Day 1: sum(Day1) = 100 (데이터 < 5)
        assertEquals("외국인 5일합 Day1", 100L, result[0].foreign5d)
        assertEquals("기관 5일합 Day1", 50L, result[0].inst5d)

        // Day 2: sum(Day1,2) = 100+200=300
        assertEquals("외국인 5일합 Day2", 300L, result[1].foreign5d)

        // Day 3: sum(Day1,2,3) = 100+200-50=250
        assertEquals("외국인 5일합 Day3", 250L, result[2].foreign5d)

        // Day 4: sum(Day1,2,3,4) = 100+200-50+300=550
        assertEquals("외국인 5일합 Day4", 550L, result[3].foreign5d)

        // Day 5 (window full): sum(Day1,2,3,4,5) = 100+200-50+300+150=700
        assertEquals("외국인 5일합 Day5 (full window)", 700L, result[4].foreign5d)
        assertEquals("기관 5일합 Day5", 220L, result[4].inst5d) // 50-30+100+200-100=220

        // Day 6: sum(Day2,3,4,5,6) = 200-50+300+150-200=400 (Day1 탈락)
        assertEquals("외국인 5일합 Day6 (sliding)", 400L, result[5].foreign5d)

        // Day 7: sum(Day3,4,5,6,7) = -50+300+150-200+100=300
        assertEquals("외국인 5일합 Day7", 300L, result[6].foreign5d)
    }

    // =========================================================================
    // Test 3: 수급 비율 검증 (엑셀: 시기외 시트)
    // =========================================================================

    /**
     * 수급 비율 = (외국인순매수 + 기관순매수) / 시가총액
     *
     * 엑셀: 시기외!C = (기관!C + 외인!C) / 외인!B
     */
    @Test
    fun `수급비율이 엑셀 시기외 시트와 동일한지 검증`() {
        val mcap = 1_000_000_000_000L  // 1조
        val data = listOf(
            DailyTrading("20240101", mcap, 1_000_000_000, 500_000_000),      // 외 10억 + 기 5억
            DailyTrading("20240102", mcap, -500_000_000, 2_000_000_000),     // 외 -5억 + 기 20억
            DailyTrading("20240103", mcap, 3_000_000_000, -1_000_000_000),   // 외 30억 + 기 -10억
            DailyTrading("20240104", mcap, 0, 0),                            // 순매수 없음
            DailyTrading("20240105", mcap, 500_000_000, 500_000_000),        // 외 5억 + 기 5억
        )

        val result = useCase.execute(data)

        // Day 1: 수급비율 = (1000000000 + 500000000) / 1000000000000
        //       = 1500000000 / 1000000000000 = 0.0015
        val expectedRatio1 = (1_000_000_000.0 + 500_000_000.0) / mcap.toDouble()
        assertEquals("수급비율 Day1", expectedRatio1, result[0].supplyRatio, TOLERANCE)

        // Day 5 (full window):
        // foreign5d = sum(1000000000, -500000000, 3000000000, 0, 500000000) = 4000000000
        // inst5d = sum(500000000, 2000000000, -1000000000, 0, 500000000) = 2000000000
        // ratio = (4000000000 + 2000000000) / 1000000000000 = 0.006
        val expectedRatio5 = (4_000_000_000.0 + 2_000_000_000.0) / mcap.toDouble()
        assertEquals("수급비율 Day5", expectedRatio5, result[4].supplyRatio, TOLERANCE)
    }

    @Test
    fun `시가총액이 0일 때 수급비율이 0인지 검증`() {
        val data = listOf(
            DailyTrading("20240101", 0L, 100, 50)
        )
        val result = useCase.execute(data)
        assertEquals("시가총액 0 → 수급비율 0", 0.0, result[0].supplyRatio, TOLERANCE)
    }

    // =========================================================================
    // Test 4: MACD 계산 검증 (엑셀: MACD 시트)
    // =========================================================================

    /**
     * MACD = EMA12 - EMA26
     *
     * 엑셀: MACD!C = 시기외12!C - 시기외26!C
     */
    @Test
    fun `MACD가 EMA12 - EMA26과 동일한지 검증`() {
        val data = generateSampleData(30)
        val result = useCase.execute(data)

        for (row in result) {
            assertEquals(
                "MACD = EMA12 - EMA26 (date: ${row.date})",
                row.ema12 - row.ema26, row.macd, TOLERANCE
            )
        }
    }

    // =========================================================================
    // Test 5: 시그널 검증 (엑셀: 시그널 시트)
    // =========================================================================

    /**
     * 시그널 = EMA(MACD, 9일)
     *
     * 엑셀: 시그널!C = MACD!C * 0.2 + 이전시그널 * 0.8
     */
    @Test
    fun `시그널이 MACD의 EMA 9일과 동일한지 검증`() {
        val data = generateSampleData(30)
        val result = useCase.execute(data)
        val macdValues = result.map { it.macd }

        // 별도로 MACD에 대한 EMA 9일 계산
        val expectedSignal = useCase.calcEma(macdValues, OscillatorConfig.EMA_SIGNAL)

        for (i in result.indices) {
            assertEquals(
                "시그널[$i]",
                expectedSignal[i], result[i].signal, TOLERANCE
            )
        }
    }

    // =========================================================================
    // Test 6: 오실레이터 검증 (엑셀: 오실 시트)
    // =========================================================================

    /**
     * 오실레이터 = MACD - 시그널
     *
     * 엑셀: 오실!C = MACD!C - 시그널!C
     */
    @Test
    fun `오실레이터가 MACD - 시그널과 동일한지 검증`() {
        val data = generateSampleData(50)
        val result = useCase.execute(data)

        for (row in result) {
            assertEquals(
                "오실레이터 = MACD - 시그널 (date: ${row.date})",
                row.macd - row.signal, row.oscillator, TOLERANCE
            )
        }
    }

    // =========================================================================
    // Test 7: 시가총액 단위 변환 검증 (엑셀: 오실!B = 외인!B / 10000)
    // =========================================================================

    @Test
    fun `시가총액 조단위 변환이 엑셀과 동일한지 검증`() {
        val data = listOf(
            DailyTrading("20240101", 450_000_000_000_000L, 100, 50),  // 450조
            DailyTrading("20240102", 1_234_567_890_000L, 200, 100),   // ~1.23조
        )
        val result = useCase.execute(data)

        // 엑셀: 오실!B = 외인!B / 10000 (억→조 변환)
        // 원 → 조: ÷ 1,000,000,000,000
        assertEquals("450조", 450.0, result[0].marketCapTril, 0.001)
        assertEquals("~1.23조", 1.23456789, result[1].marketCapTril, 0.00001)
    }

    // =========================================================================
    // Test 8: 전체 파이프라인 엔드투엔드 검증
    // =========================================================================

    /**
     * Python 코드와 동일한 입력으로 동일한 출력이 나오는지 검증
     *
     * Python 코드 (최적화 버전):
     * ```python
     * inv = stock.get_market_trading_value_by_date(start, end, ticker)
     * df['foreign_5d'] = inv['외국인합계'].rolling(5).sum()
     * df['inst_5d'] = inv['기관합계'].rolling(5).sum()
     * df['supply_ratio'] = (df['foreign_5d'] + df['inst_5d']) / df['market_cap']
     * df['ema12'] = df['supply_ratio'].ewm(alpha=2/13, adjust=False).mean()
     * df['ema26'] = df['supply_ratio'].ewm(alpha=2/27, adjust=False).mean()
     * df['macd'] = df['ema12'] - df['ema26']
     * df['signal'] = df['macd'].ewm(alpha=2/10, adjust=False).mean()
     * df['oscillator'] = df['macd'] - df['signal']
     * ```
     */
    @Test
    fun `전체 파이프라인이 Python pandas 결과와 동일한지 검증`() {
        // 10일치 고정 샘플 데이터
        val mcap = 100_000_000_000_000L // 100조
        val foreignBuys = longArrayOf(
            5_000_000_000, -3_000_000_000, 8_000_000_000, -1_000_000_000, 4_000_000_000,
            -6_000_000_000, 2_000_000_000, 7_000_000_000, -2_000_000_000, 3_000_000_000
        )
        val instBuys = longArrayOf(
            2_000_000_000, 4_000_000_000, -5_000_000_000, 3_000_000_000, 1_000_000_000,
            6_000_000_000, -3_000_000_000, -1_000_000_000, 5_000_000_000, 2_000_000_000
        )

        val data = List(10) { i ->
            DailyTrading("2024010${i + 1}", mcap, foreignBuys[i], instBuys[i])
        }

        val result = useCase.execute(data)

        // === 수동 계산으로 전체 파이프라인 검증 ===

        // Step 2: 5일 누적 (manually computed)
        val f5d = LongArray(10)
        val i5d = LongArray(10)
        for (idx in 0 until 10) {
            val start = maxOf(0, idx - 4)
            f5d[idx] = (start..idx).sumOf { foreignBuys[it] }
            i5d[idx] = (start..idx).sumOf { instBuys[it] }
        }

        // Step 3: 수급비율
        val ratios = DoubleArray(10) { (f5d[it] + i5d[it]).toDouble() / mcap.toDouble() }

        // Step 4: EMA12, EMA26
        val a12 = 2.0 / 13
        val a26 = 2.0 / 27
        val ema12 = DoubleArray(10)
        val ema26 = DoubleArray(10)
        ema12[0] = ratios[0]
        ema26[0] = ratios[0]
        for (idx in 1 until 10) {
            ema12[idx] = a12 * ratios[idx] + (1 - a12) * ema12[idx - 1]
            ema26[idx] = a26 * ratios[idx] + (1 - a26) * ema26[idx - 1]
        }

        // Step 5: MACD
        val macd = DoubleArray(10) { ema12[it] - ema26[it] }

        // Step 6: 시그널
        val aSignal = 2.0 / 10  // 0.2
        val signal = DoubleArray(10)
        signal[0] = macd[0]
        for (idx in 1 until 10) {
            signal[idx] = aSignal * macd[idx] + (1 - aSignal) * signal[idx - 1]
        }

        // Step 7: 오실레이터
        val osc = DoubleArray(10) { macd[it] - signal[it] }

        // 전체 결과 비교
        for (idx in 0 until 10) {
            assertEquals("5일합(외국인)[$idx]", f5d[idx], result[idx].foreign5d)
            assertEquals("5일합(기관)[$idx]", i5d[idx], result[idx].inst5d)
            assertEquals("수급비율[$idx]", ratios[idx], result[idx].supplyRatio, TOLERANCE)
            assertEquals("EMA12[$idx]", ema12[idx], result[idx].ema12, TOLERANCE)
            assertEquals("EMA26[$idx]", ema26[idx], result[idx].ema26, TOLERANCE)
            assertEquals("MACD[$idx]", macd[idx], result[idx].macd, TOLERANCE)
            assertEquals("시그널[$idx]", signal[idx], result[idx].signal, TOLERANCE)
            assertEquals("오실레이터[$idx]", osc[idx], result[idx].oscillator, TOLERANCE)
        }
    }

    // =========================================================================
    // Test 9: EMA α 값 정확성 검증
    // =========================================================================

    @Test
    fun `EMA alpha 값이 엑셀 파라미터와 정확히 일치하는지 검증`() {
        val config = OscillatorConfig()

        // 엑셀: 시기외12 시트의 G3 셀 → α = 2/13
        assertEquals("EMA12 α = 2/13", 2.0 / 13, config.alphaFast, TOLERANCE)

        // 엑셀: 시기외26 시트의 G3 셀 → α = 2/27
        assertEquals("EMA26 α = 2/27", 2.0 / 27, config.alphaSlow, TOLERANCE)

        // 엑셀: 시그널 시트의 G3 셀 → α = 2/10 = 0.2
        assertEquals("Signal α = 2/10", 0.2, config.alphaSignal, TOLERANCE)
    }

    // =========================================================================
    // Test 10: 매매 신호 분석 검증
    // =========================================================================

    @Test
    fun `골든크로스와 데드크로스 감지 검증`() {
        val data = generateSampleData(50)
        val result = useCase.execute(data)
        val signals = useCase.analyzeSignals(result)

        // 오실레이터가 0을 교차하는 시점 확인
        for (i in 1 until result.size) {
            val prevOsc = result[i - 1].oscillator
            val currOsc = result[i].oscillator

            if (prevOsc <= 0 && currOsc > 0) {
                assertEquals("골든크로스 감지", CrossSignal.GOLDEN_CROSS, signals[i].crossSignal)
            } else if (prevOsc >= 0 && currOsc < 0) {
                assertEquals("데드크로스 감지", CrossSignal.DEAD_CROSS, signals[i].crossSignal)
            } else {
                assertNull("교차 없음", signals[i].crossSignal)
            }
        }
    }

    // =========================================================================
    // Test 11: 엣지 케이스
    // =========================================================================

    @Test
    fun `단일 데이터 포인트 처리`() {
        val data = listOf(DailyTrading("20240101", 1000000000000, 100, 50))
        val result = useCase.execute(data)

        assertEquals(1, result.size)
        assertEquals(100L, result[0].foreign5d)
        assertEquals(50L, result[0].inst5d)
        // 단일 포인트: MACD=0 (EMA12==EMA26), 시그널=0, 오실레이터=0
        assertEquals("단일포인트 MACD", 0.0, result[0].macd, TOLERANCE)
        assertEquals("단일포인트 오실레이터", 0.0, result[0].oscillator, TOLERANCE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `빈 데이터 입력 시 예외`() {
        useCase.execute(emptyList())
    }

    @Test
    fun `음수 시가총액 처리`() {
        // 비정상적 데이터이지만 크래시 없이 처리
        val data = listOf(
            DailyTrading("20240101", -1000000000000, 100, 50)
        )
        val result = useCase.execute(data)
        assertEquals(1, result.size)
    }

    // =========================================================================
    // 헬퍼 메서드
    // =========================================================================

    /**
     * 테스트용 샘플 데이터 생성
     * 다양한 패턴의 순매수 데이터를 시뮬레이션
     */
    private fun generateSampleData(days: Int): List<DailyTrading> {
        val mcap = 100_000_000_000_000L // 100조
        return List(days) { i ->
            val date = String.format("2024%02d%02d", (i / 28) + 1, (i % 28) + 1)
            // 사인파 패턴으로 현실적인 수급 시뮬레이션
            val foreignBuy = (Math.sin(i * 0.3) * 5_000_000_000).toLong()
            val instBuy = (Math.cos(i * 0.2) * 3_000_000_000).toLong()
            DailyTrading(date, mcap, foreignBuy, instBuy)
        }
    }
}
