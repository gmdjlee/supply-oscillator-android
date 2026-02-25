package com.krxkt.integration

import com.krxkt.KrxIndex
import com.krxkt.model.IndexMarket
import kotlinx.coroutines.runBlocking

/**
 * 신규 구현 함수 통합 테스트
 *
 * 실제 KRX API 호출 - 한국 네트워크 필요
 * 실행: ./gradlew runIntegrationTest -PmainClass=com.krxkt.integration.IndexExtensionTestKt
 */
fun main() = runBlocking {
    val krxIndex = KrxIndex()

    try {
        println("=" .repeat(60))
        println(" KrxIndex 신규 함수 통합 테스트")
        println("=" .repeat(60))

        // ─────────────────────────────────────────────
        // 1. getIndexOhlcv - 전종목 지수 OHLCV (특정일)
        // pykrx: get_index_ohlcv_by_ticker("20210122", "KOSPI")
        // ─────────────────────────────────────────────
        println("\n[1] getIndexOhlcv(\"20210122\", KOSPI)")
        println("-".repeat(60))
        val indexOhlcv = krxIndex.getIndexOhlcv("20210122", IndexMarket.KOSPI)
        if (indexOhlcv.isEmpty()) {
            println("  ⚠ 빈 응답 (공휴일이거나 네트워크 문제)")
        } else {
            println("  총 ${indexOhlcv.size}개 지수")
            indexOhlcv.take(5).forEach { idx ->
                println("  ${idx.name}: 종가=${idx.close}, 시가=${idx.open}, 고가=${idx.high}, 저가=${idx.low}, 거래량=${idx.volume}, 등락=${idx.change ?: "N/A"} (${idx.changeRate ?: "N/A"}%)")
            }
            if (indexOhlcv.size > 5) println("  ... 외 ${indexOhlcv.size - 5}개")
        }

        // ─────────────────────────────────────────────
        // 2. getIndexOhlcv - KOSDAQ 시장
        // ─────────────────────────────────────────────
        println("\n[2] getIndexOhlcv(\"20210122\", KOSDAQ)")
        println("-".repeat(60))
        val kosdaqOhlcv = krxIndex.getIndexOhlcv("20210122", IndexMarket.KOSDAQ)
        if (kosdaqOhlcv.isEmpty()) {
            println("  ⚠ 빈 응답")
        } else {
            println("  총 ${kosdaqOhlcv.size}개 지수")
            kosdaqOhlcv.take(3).forEach { idx ->
                println("  ${idx.name}: 종가=${idx.close}, 등락=${idx.change ?: "N/A"}")
            }
        }

        // ─────────────────────────────────────────────
        // 3. getIndexPortfolio - 지수 구성종목
        // pykrx: get_index_portfolio_deposit_file("1028", "20210122")
        // ─────────────────────────────────────────────
        println("\n[3] getIndexPortfolio(\"20210122\", \"1028\") — KOSPI 200 구성종목")
        println("-".repeat(60))
        val portfolio = krxIndex.getIndexPortfolio("20210122", "1028")
        if (portfolio.isEmpty()) {
            println("  ⚠ 빈 응답")
        } else {
            println("  총 ${portfolio.size}개 구성종목")
            portfolio.take(10).forEach { p ->
                println("  ${p.ticker} ${p.name}: 종가=${p.close}, 등락률=${p.changeRate ?: "N/A"}%, 시총=${p.marketCap}")
            }
            if (portfolio.size > 10) println("  ... 외 ${portfolio.size - 10}개")
        }

        // ─────────────────────────────────────────────
        // 4. getIndexPortfolioTickers - 티커만 추출
        // ─────────────────────────────────────────────
        println("\n[4] getIndexPortfolioTickers(\"20210122\", \"1028\") — 티커 리스트")
        println("-".repeat(60))
        val tickers = krxIndex.getIndexPortfolioTickers("20210122", "1028")
        if (tickers.isEmpty()) {
            println("  ⚠ 빈 응답")
        } else {
            println("  총 ${tickers.size}개 종목")
            println("  상위 10개: ${tickers.take(10)}")
        }

        // ─────────────────────────────────────────────
        // 5. getNearestBusinessDay - 최근 영업일
        // pykrx: get_nearest_business_day_in_a_week("20210123")
        // 2021-01-23 = 토요일 → 이전 영업일 = 2021-01-22 (금)
        // ─────────────────────────────────────────────
        println("\n[5] getNearestBusinessDay(\"20210123\", prev=true) — 토요일의 이전 영업일")
        println("-".repeat(60))
        try {
            val nearestPrev = krxIndex.getNearestBusinessDay("20210123", prev = true)
            println("  20210123 (토) → 이전 영업일: $nearestPrev")
        } catch (e: Exception) {
            println("  에러: ${e.message}")
        }

        println("\n[6] getNearestBusinessDay(\"20210123\", prev=false) — 토요일의 다음 영업일")
        println("-".repeat(60))
        try {
            val nearestNext = krxIndex.getNearestBusinessDay("20210123", prev = false)
            println("  20210123 (토) → 다음 영업일: $nearestNext")
        } catch (e: Exception) {
            println("  에러: ${e.message}")
        }

        println("\n[7] getNearestBusinessDay(\"20210122\", prev=true) — 금요일(영업일) 자기 자신")
        println("-".repeat(60))
        try {
            val nearestSelf = krxIndex.getNearestBusinessDay("20210122", prev = true)
            println("  20210122 (금) → 영업일: $nearestSelf")
        } catch (e: Exception) {
            println("  에러: ${e.message}")
        }

        // ─────────────────────────────────────────────
        // 8. getBusinessDays - 기간 내 영업일 목록
        // pykrx: get_previous_business_days(fromdate="20210118", todate="20210124")
        // ─────────────────────────────────────────────
        println("\n[8] getBusinessDays(\"20210118\", \"20210124\") — 1주일 영업일")
        println("-".repeat(60))
        val bizDays = krxIndex.getBusinessDays("20210118", "20210124")
        println("  영업일 ${bizDays.size}일: $bizDays")

        // ─────────────────────────────────────────────
        // 9. getBusinessDaysByMonth - 월별 영업일
        // pykrx: get_previous_business_days(year=2021, month=1)
        // ─────────────────────────────────────────────
        println("\n[9] getBusinessDaysByMonth(2021, 1) — 2021년 1월 영업일")
        println("-".repeat(60))
        val janDays = krxIndex.getBusinessDaysByMonth(2021, 1)
        println("  영업일 ${janDays.size}일")
        println("  첫째 주: ${janDays.take(5)}")
        println("  마지막 주: ${janDays.takeLast(5)}")

        // ─────────────────────────────────────────────
        // 10. 기존 함수 회귀 테스트
        // ─────────────────────────────────────────────
        println("\n[10] getOhlcvByTicker(\"20210118\", \"20210122\", \"1001\") — KOSPI 기간조회 (기존)")
        println("-".repeat(60))
        val kospiOhlcv = krxIndex.getOhlcvByTicker("20210118", "20210122", "1001")
        kospiOhlcv.forEach { o ->
            println("  ${o.date}: 종가=${o.close}, 거래량=${o.volume}")
        }

        println("\n" + "=".repeat(60))
        println(" 테스트 완료!")
        println("=".repeat(60))

    } catch (e: Exception) {
        println("\n❌ 테스트 실패: ${e.javaClass.simpleName}: ${e.message}")
        e.printStackTrace()
    } finally {
        krxIndex.close()
    }
}
