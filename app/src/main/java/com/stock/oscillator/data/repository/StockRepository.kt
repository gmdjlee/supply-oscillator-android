package com.stock.oscillator.data.repository

import com.stock.oscillator.domain.model.DailyTrading
import com.krxkt.KrxStock
import com.krxkt.model.AskBidType
import com.krxkt.model.Market
import com.krxkt.model.TradingValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * KRX 데이터 수집 Repository
 *
 * pykrx → kotlin_krx 매핑:
 * - stock.get_market_cap()           → krxStock.getMarketCap()
 * - stock.get_market_trading_value_by_date() → krxStock.getTradingByInvestor()
 *
 * 엑셀 원천 데이터 시트 대응:
 * - 시가총액 시트         → getMarketCap (일별)
 * - 외국인매수데이터 시트  → getTradingByInvestor (외국인 순매수)
 * - 기관매수데이터 시트    → getTradingByInvestor (기관합계 순매수)
 */
class StockRepository(
    private val krxStock: KrxStock = KrxStock()
) {
    private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    /**
     * 종목 검색 (종목명 → 종목코드)
     *
     * pykrx 대응: stock.get_market_ticker_list() + get_market_ticker_name()
     */
    suspend fun searchStock(query: String, market: Market = Market.ALL): List<StockSearchResult> =
        withContext(Dispatchers.IO) {
            val today = LocalDate.now().format(fmt)
            val tickers = krxStock.getTickerList(date = today, market = market)
            tickers.filter { info ->
                query.uppercase() in info.name.uppercase() ||
                info.name.uppercase() in query.uppercase() ||
                info.ticker == query
            }.map { info ->
                StockSearchResult(
                    ticker = info.ticker,
                    name = info.name,
                    market = info.marketName
                )
            }
        }

    /**
     * 일별 거래 데이터 수집
     *
     * pykrx 코드 대응:
     * ```python
     * mcap = stock.get_market_cap(start, end, ticker)
     * inv = stock.get_market_trading_value_by_date(start, end, ticker)
     * foreign = inv['외국인합계']
     * inst = inv['기관합계']
     * ```
     *
     * kotlin_krx 전략:
     * 1. getTradingByInvestor로 투자자 거래 데이터 수집 (기간 조회)
     * 2. getMarketCap(endDate)로 상장주식수 확보
     * 3. getOhlcvByTicker로 일별 종가 확보
     * 4. 시가총액 = 종가 × 상장주식수
     *
     * @param ticker 종목코드 (예: "005930")
     * @param startDate 시작일 "yyyyMMdd"
     * @param endDate 종료일 "yyyyMMdd"
     */
    suspend fun getDailyTradingData(
        ticker: String,
        startDate: String,
        endDate: String
    ): List<DailyTrading> = withContext(Dispatchers.IO) {
        // 병렬 데이터 수집
        val tradingDeferred = async {
            krxStock.getTradingByInvestor(
                startDate = startDate,
                endDate = endDate,
                ticker = ticker,
                valueType = TradingValueType.VALUE,
                askBidType = AskBidType.NET_BUY
            )
        }

        val ohlcvDeferred = async {
            krxStock.getOhlcvByTicker(
                startDate = startDate,
                endDate = endDate,
                ticker = ticker
            )
        }

        val sharesDeferred = async {
            // 최근 거래일의 상장주식수 조회
            val allCaps = krxStock.getMarketCap(date = endDate)
            allCaps.find { it.ticker == ticker }?.sharesOutstanding ?: 0L
        }

        val tradingData = tradingDeferred.await()
        val ohlcvData = ohlcvDeferred.await()
        val sharesOutstanding = sharesDeferred.await()

        // OHLCV 데이터를 날짜별 종가 맵으로 변환
        val closePriceMap = ohlcvData.associate { it.date to it.close }

        // 투자자 거래일 기준으로 데이터 병합
        tradingData.mapNotNull { inv ->
            val closePrice = closePriceMap[inv.date] ?: return@mapNotNull null
            val marketCap = closePrice * sharesOutstanding
            DailyTrading(
                date = inv.date,
                marketCap = marketCap,
                foreignNetBuy = inv.foreigner,
                instNetBuy = inv.institutionalTotal
            )
        }.sortedBy { it.date }
    }

    fun close() {
        krxStock.close()
    }
}

data class StockSearchResult(
    val ticker: String,
    val name: String,
    val market: String
)
