package com.krxkt

import com.krxkt.model.Market
import com.krxkt.model.MarketCap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MarketCapComparisonTest {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    @Test
    fun compareSamsungMarketCap() = runBlocking {
        val krxStock = KrxStock()
        val ticker = "005930"

        println("=" + "=".repeat(79))
        println("Samsung 005930 MKTCAP Field vs Calculated Comparison")
        println("=" + "=".repeat(79))
        println()

        var currentDate = LocalDate.now()
        val businessDays = mutableListOf<String>()

        while (businessDays.size < 10) {
            currentDate = currentDate.minusDays(1)
            val dayOfWeek = currentDate.dayOfWeek.value
            if (dayOfWeek != 6 && dayOfWeek != 7) {
                businessDays.add(currentDate.format(dateFormatter))
            }
        }

        businessDays.reverse()

        println("Test dates (last 10 business days):")
        businessDays.forEachIndexed { index, date ->
            println("  ${index + 1}. $date")
        }
        println()
        println("-".repeat(80))
        println()

        var allMatch = true
        val results = mutableListOf<TestResult>()

        businessDays.forEachIndexed { index, date ->
            try {
                val capsList = krxStock.getMarketCap(date, Market.ALL)

                if (capsList.isEmpty()) {
                    println("WARNING Day ${index + 1} ($date): getMarketCap returned 0 records")
                    println()
                    return@forEachIndexed
                }

                val cap = capsList.firstOrNull { it.ticker == ticker }

                if (cap == null) {
                    println("WARNING Day ${index + 1} ($date): Ticker $ticker not found in ${capsList.size} records")
                    println()
                    return@forEachIndexed
                }

                val apiMarketCap = cap.marketCap
                val close = cap.close
                val shares = cap.sharesOutstanding
                val calculatedMarketCap = close * shares

                val matches = apiMarketCap == calculatedMarketCap
                val difference = apiMarketCap - calculatedMarketCap
                val diffPercent = if (apiMarketCap > 0) {
                    (difference.toDouble() / apiMarketCap * 100.0)
                } else {
                    0.0
                }

                results.add(TestResult(
                    date = date,
                    close = close,
                    sharesOutstanding = shares,
                    apiMarketCap = apiMarketCap,
                    calculatedMarketCap = calculatedMarketCap,
                    matches = matches,
                    difference = difference,
                    diffPercent = diffPercent
                ))

                if (!matches) {
                    allMatch = false
                }

                println("Day ${index + 1}: $date")
                println("   Close: ${formatNum(close)} KRW")
                println("   Shares Outstanding: ${formatNum(shares)}")
                println()
                println("   API MKTCAP:  ${formatNum(apiMarketCap)} KRW")
                println("   Calculated:  ${formatNum(calculatedMarketCap)} KRW")
                println()

                if (matches) {
                    println("   OK Match!")
                } else {
                    println("   ERROR Mismatch!")
                    println("   Difference: ${formatNum(difference)} KRW (${String.format("%.6f", diffPercent)}%)")
                }
                println()
                println("-".repeat(80))
                println()

            } catch (e: Exception) {
                println("ERROR Day ${index + 1} ($date): ${e.message}")
                println()
            }
        }

        println()
        println("=" + "=".repeat(79))
        println("Summary")
        println("=" + "=".repeat(79))
        println()

        val totalResults = results.size
        println("Verified dates: $totalResults")

        val matchCount = results.count { it.matches }
        val unmatchCount = totalResults - matchCount

        println("Matches: $matchCount")
        println("Mismatches: $unmatchCount")
        println()

        if (allMatch && totalResults > 0) {
            println("SUCCESS All dates match!")
            println("   API MKTCAP = Close x Shares Outstanding")
        } else if (totalResults == 0) {
            println("WARNING No data available for verification")
        } else {
            println("ERROR Some dates do not match")
            println()
            println("Mismatch details:")
            results.filter { !it.matches }.forEach { result ->
                println("  - ${result.date}: diff ${formatNum(result.difference)} KRW (${String.format("%.6f", result.diffPercent)}%)")
            }
        }
        println()
        println("=" + "=".repeat(79))

        krxStock.close()
    }

    private fun formatNum(num: Long): String {
        return String.format("%,d", num)
    }

    data class TestResult(
        val date: String,
        val close: Long,
        val sharesOutstanding: Long,
        val apiMarketCap: Long,
        val calculatedMarketCap: Long,
        val matches: Boolean,
        val difference: Long,
        val diffPercent: Double
    )
}
