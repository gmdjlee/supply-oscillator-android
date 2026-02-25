package com.stock.oscillator.domain.usecase

import com.stock.oscillator.domain.model.*
import com.stock.oscillator.domain.model.OscillatorConfig.Companion.MARKET_CAP_DIVISOR

/**
 * 수급 오실레이터 계산 UseCase
 *
 * 엑셀 전체 데이터 흐름을 Kotlin으로 1:1 매핑:
 *
 * Step 1: 원천 데이터 (시가총액, 외국인/기관 순매수)
 * Step 2: 5일 누적 순매수 계산 (rolling window = 5, 개장일 기준)
 * Step 3: 수급 비율 = (외국인5일합 + 기관5일합) / 시가총액
 * Step 4: EMA 12일, 26일 계산
 * Step 5: MACD = EMA12 - EMA26
 * Step 6: 시그널 = EMA(MACD, 9일)
 * Step 7: 오실레이터 = MACD - 시그널
 */
class CalcOscillatorUseCase(
    private val config: OscillatorConfig = OscillatorConfig()
) {
    /**
     * 전체 오실레이터 계산 파이프라인
     *
     * @param dailyData 일별 거래 원시 데이터 (날짜순 정렬 필수)
     * @return 오실레이터 계산 결과 리스트
     */
    fun execute(dailyData: List<DailyTrading>): List<OscillatorRow> {
        require(dailyData.isNotEmpty()) { "일별 데이터가 비어있습니다" }

        // Step 2: 5일 누적 순매수 (개장일 기준 rolling)
        val cumData = calc5DayRolling(dailyData)

        // Step 3: 수급 비율
        val supplyRatios = cumData.map { (daily, f5d, i5d) ->
            if (daily.marketCap == 0L) 0.0
            else (f5d + i5d).toDouble() / daily.marketCap.toDouble()
        }

        // Step 4: EMA 12일, 26일
        val ema12 = calcEma(supplyRatios, config.emaFast)
        val ema26 = calcEma(supplyRatios, config.emaSlow)

        // Step 5: MACD = EMA12 - EMA26
        val macd = ema12.zip(ema26) { e12, e26 -> e12 - e26 }

        // Step 6: 시그널 = EMA(MACD, 9일)
        val signal = calcEma(macd, config.emaSignal)

        // Step 7: 오실레이터 = MACD - 시그널
        return cumData.indices.map { i ->
            val (daily, f5d, i5d) = cumData[i]
            OscillatorRow(
                date = daily.date,
                marketCap = daily.marketCap,
                marketCapTril = daily.marketCap / MARKET_CAP_DIVISOR,
                foreign5d = f5d,
                inst5d = i5d,
                supplyRatio = supplyRatios[i],
                ema12 = ema12[i],
                ema26 = ema26[i],
                macd = macd[i],
                signal = signal[i],
                oscillator = macd[i] - signal[i]
            )
        }
    }

    /**
     * Step 2: 5일 누적 순매수 계산 (개장일 기준)
     *
     * 엑셀 매핑: 외국인매수데이터/기관매수데이터 시트
     * - Python: inv['외국인합계'].rolling(5).sum()
     * - Kotlin: 직접 슬라이딩 윈도우 구현
     *
     * @return Triple(원본데이터, 외국인5일합, 기관5일합)
     */
    private fun calc5DayRolling(
        data: List<DailyTrading>
    ): List<Triple<DailyTrading, Long, Long>> {
        val window = config.rollingWindow
        return data.indices.map { i ->
            val startIdx = maxOf(0, i - window + 1) // 현재일 포함 이전 4일
            val foreignSum = (startIdx..i).sumOf { data[it].foreignNetBuy }
            val instSum = (startIdx..i).sumOf { data[it].instNetBuy }
            Triple(data[i], foreignSum, instSum)
        }
    }

    /**
     * EMA (지수이동평균) 계산
     *
     * 엑셀 수식 정확 매핑:
     * - 첫 값: 시기외!C15 (= 시계열의 첫 번째 값)
     * - 이후: α × 현재값 + (1 - α) × 이전EMA
     * - α = 2 / (period + 1)
     *
     * pandas.ewm(alpha=..., adjust=False) 와 동일
     */
    fun calcEma(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()

        val alpha = 2.0 / (period + 1)
        val result = mutableListOf<Double>()
        result.add(values[0]) // 첫 값 = 시계열 첫 번째 값

        for (i in 1 until values.size) {
            val ema = alpha * values[i] + (1.0 - alpha) * result[i - 1]
            result.add(ema)
        }
        return result
    }

    /**
     * 매매 신호 분석
     *
     * - 골든크로스: 오실레이터가 음 → 양 전환 (MACD가 시그널 상향 돌파)
     * - 데드크로스: 오실레이터가 양 → 음 전환 (MACD가 시그널 하향 돌파)
     */
    fun analyzeSignals(rows: List<OscillatorRow>): List<SignalAnalysis> {
        return rows.mapIndexed { i, row ->
            val cross = if (i > 0) {
                val prevOsc = rows[i - 1].oscillator
                when {
                    prevOsc <= 0 && row.oscillator > 0 -> CrossSignal.GOLDEN_CROSS
                    prevOsc >= 0 && row.oscillator < 0 -> CrossSignal.DEAD_CROSS
                    else -> null
                }
            } else null

            val trend = when {
                row.oscillator > 0 && row.macd > 0 -> Trend.BULLISH
                row.oscillator < 0 && row.macd < 0 -> Trend.BEARISH
                else -> Trend.NEUTRAL
            }

            SignalAnalysis(
                date = row.date,
                marketCapTril = row.marketCapTril,
                oscillator = row.oscillator,
                macd = row.macd,
                signal = row.signal,
                trend = trend,
                crossSignal = cross
            )
        }
    }
}
