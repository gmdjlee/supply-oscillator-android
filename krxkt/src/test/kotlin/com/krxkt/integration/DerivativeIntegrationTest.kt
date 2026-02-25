package com.krxkt.integration

import com.krxkt.KrxIndex
import kotlinx.coroutines.runBlocking

/**
 * 파생상품 API 통합 테스트
 *
 * MDCSTAT13102 (옵션 거래량), MDCSTAT01201 (파생 지수) 실제 동작 확인.
 * 한국 네트워크 또는 VPN 필요.
 *
 * 실행: ./gradlew runIntegrationTest -PmainClass=com.krxkt.integration.DerivativeIntegrationTestKt
 */
fun main() = runBlocking {
    println("=".repeat(60))
    println("파생상품 API 통합 테스트")
    println("=".repeat(60))

    val krxIndex = KrxIndex()
    val startDate = "20210101"
    val endDate = "20210131"
    var passed = 0
    var failed = 0

    // ====================================================
    // Test 1: VKOSPI (MDCSTAT01201)
    // ====================================================
    print("\n[Test 1] VKOSPI 조회 (MDCSTAT01201)... ")
    try {
        val vkospi = krxIndex.getVkospi(startDate, endDate)
        if (vkospi.isNotEmpty()) {
            println("OK (${vkospi.size}건)")
            println("  첫 번째: date=${vkospi.first().date}, close=${vkospi.first().close}")
            println("  마지막:  date=${vkospi.last().date}, close=${vkospi.last().close}")
            check(vkospi.first().close > 0) { "VKOSPI 종가가 0" }
            check(vkospi.first().date.length == 8) { "날짜 형식 오류" }
            passed++
        } else {
            println("WARN - 빈 응답 (공휴일 또는 세션 문제)")
            failed++
        }
    } catch (e: Exception) {
        println("FAIL - ${e.message}")
        failed++
    }

    // ====================================================
    // Test 2: 5년국채 (MDCSTAT01201)
    // ====================================================
    print("\n[Test 2] 5년국채 조회 (MDCSTAT01201)... ")
    try {
        val bond5y = krxIndex.getBond5y(startDate, endDate)
        if (bond5y.isNotEmpty()) {
            println("OK (${bond5y.size}건)")
            println("  첫 번째: date=${bond5y.first().date}, close=${bond5y.first().close}")
            println("  마지막:  date=${bond5y.last().date}, close=${bond5y.last().close}")
            passed++
        } else {
            println("WARN - 빈 응답")
            failed++
        }
    } catch (e: Exception) {
        println("FAIL - ${e.message}")
        failed++
    }

    // ====================================================
    // Test 3: 10년국채 (MDCSTAT01201)
    // ====================================================
    print("\n[Test 3] 10년국채 조회 (MDCSTAT01201)... ")
    try {
        val bond10y = krxIndex.getBond10y(startDate, endDate)
        if (bond10y.isNotEmpty()) {
            println("OK (${bond10y.size}건)")
            println("  첫 번째: date=${bond10y.first().date}, close=${bond10y.first().close}")
            println("  마지막:  date=${bond10y.last().date}, close=${bond10y.last().close}")
            passed++
        } else {
            println("WARN - 빈 응답")
            failed++
        }
    } catch (e: Exception) {
        println("FAIL - ${e.message}")
        failed++
    }

    // ====================================================
    // Test 4: 콜 옵션 거래량 (MDCSTAT13102)
    // ====================================================
    print("\n[Test 4] 콜 옵션 거래량 (MDCSTAT13102)... ")
    try {
        val callVol = krxIndex.getCallOptionVolume(startDate, endDate)
        if (callVol.isNotEmpty()) {
            println("OK (${callVol.size}건)")
            println("  첫 번째: date=${callVol.first().date}, volume=${callVol.first().totalVolume}")
            println("  마지막:  date=${callVol.last().date}, volume=${callVol.last().totalVolume}")
            check(callVol.first().totalVolume > 0) { "콜 옵션 거래량이 0" }
            passed++
        } else {
            println("WARN - 빈 응답")
            failed++
        }
    } catch (e: Exception) {
        println("FAIL - ${e.message}")
        failed++
    }

    // ====================================================
    // Test 5: 풋 옵션 거래량 (MDCSTAT13102)
    // ====================================================
    print("\n[Test 5] 풋 옵션 거래량 (MDCSTAT13102)... ")
    try {
        val putVol = krxIndex.getPutOptionVolume(startDate, endDate)
        if (putVol.isNotEmpty()) {
            println("OK (${putVol.size}건)")
            println("  첫 번째: date=${putVol.first().date}, volume=${putVol.first().totalVolume}")
            println("  마지막:  date=${putVol.last().date}, volume=${putVol.last().totalVolume}")
            check(putVol.first().totalVolume > 0) { "풋 옵션 거래량이 0" }
            passed++
        } else {
            println("WARN - 빈 응답")
            failed++
        }
    } catch (e: Exception) {
        println("FAIL - ${e.message}")
        failed++
    }

    // ====================================================
    // Test 6: KOSPI/KOSDAQ (기존 API - 비교용)
    // ====================================================
    print("\n[Test 6] KOSPI 지수 (MDCSTAT00301, 기존 API)... ")
    try {
        val kospi = krxIndex.getKospi(startDate, endDate)
        if (kospi.isNotEmpty()) {
            println("OK (${kospi.size}건)")
            println("  첫 번째: date=${kospi.first().date}, close=${kospi.first().close}")
            passed++
        } else {
            println("WARN - 빈 응답")
            failed++
        }
    } catch (e: Exception) {
        println("FAIL - ${e.message}")
        failed++
    }

    // ====================================================
    // 결과 요약
    // ====================================================
    println("\n" + "=".repeat(60))
    println("결과: ${passed}개 통과, ${failed}개 실패 (총 6개)")
    println("=".repeat(60))

    krxIndex.close()

    if (failed > 0) {
        println("\n⚠️ 실패한 테스트가 있습니다.")
        println("  - 한국 네트워크 또는 VPN 연결을 확인하세요.")
        println("  - KRX API 서버 상태를 확인하세요.")
    }
}
