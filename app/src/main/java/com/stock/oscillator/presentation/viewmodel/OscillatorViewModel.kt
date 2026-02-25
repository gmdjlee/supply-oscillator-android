package com.stock.oscillator.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.oscillator.data.repository.StockRepository
import com.stock.oscillator.data.repository.StockSearchResult
import com.stock.oscillator.domain.model.*
import com.stock.oscillator.domain.usecase.CalcOscillatorUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 수급 오실레이터 ViewModel
 *
 * 전체 파이프라인:
 * 1. 종목 검색 (searchStock)
 * 2. 데이터 수집 (StockRepository → kotlin_krx)
 * 3. 오실레이터 계산 (CalcOscillatorUseCase)
 * 4. UI State 업데이트 (ChartData + SignalAnalysis)
 */
class OscillatorViewModel(
    private val repository: StockRepository = StockRepository(),
    private val calcOscillator: CalcOscillatorUseCase = CalcOscillatorUseCase(),
    private val config: OscillatorConfig = OscillatorConfig()
) : ViewModel() {

    private val _uiState = MutableStateFlow<OscillatorUiState>(OscillatorUiState.Idle)
    val uiState: StateFlow<OscillatorUiState> = _uiState.asStateFlow()

    private val _searchResults = MutableStateFlow<List<StockSearchResult>>(emptyList())
    val searchResults: StateFlow<List<StockSearchResult>> = _searchResults.asStateFlow()

    private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var searchJob: Job? = null

    /** 종목 검색 (300ms 디바운스) */
    fun searchStock(query: String) {
        if (query.isBlank()) {
            searchJob?.cancel()
            _searchResults.value = emptyList()
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            try {
                _searchResults.value = repository.searchStock(query)
            } catch (e: Exception) {
                _searchResults.value = emptyList()
            }
        }
    }

    /** 오실레이터 분석 실행 */
    fun analyze(
        ticker: String,
        stockName: String,
        analysisDays: Int = OscillatorConfig.DEFAULT_ANALYSIS_DAYS,
        displayDays: Int = OscillatorConfig.DEFAULT_DISPLAY_DAYS
    ) {
        viewModelScope.launch {
            _uiState.value = OscillatorUiState.Loading("데이터 수집 중...")

            try {
                // 기간 설정
                val endDate = LocalDate.now()
                val startDate = endDate.minusDays(analysisDays.toLong())

                // Step 1: 데이터 수집 (kotlin_krx)
                _uiState.value = OscillatorUiState.Loading("KRX 데이터 수집 중...")
                val dailyData = repository.getDailyTradingData(
                    ticker = ticker,
                    startDate = startDate.format(fmt),
                    endDate = endDate.format(fmt)
                )

                if (dailyData.isEmpty()) {
                    _uiState.value = OscillatorUiState.Error("데이터가 없습니다. 종목코드를 확인해주세요.")
                    return@launch
                }

                // Step 2: 오실레이터 계산
                // 5일 롤링은 전체 이력 사용, EMA는 표시 기간부터 새로 시작
                _uiState.value = OscillatorUiState.Loading("오실레이터 계산 중...")
                val warmupCount = maxOf(0, dailyData.size - displayDays)
                val oscillatorRows = calcOscillator.execute(dailyData, warmupCount)

                // Step 3: 신호 분석
                val signals = calcOscillator.analyzeSignals(oscillatorRows)

                // Step 4: 결과 전달
                _uiState.value = OscillatorUiState.Success(
                    chartData = ChartData(
                        stockName = stockName,
                        ticker = ticker,
                        rows = oscillatorRows
                    ),
                    signals = signals,
                    latestSignal = signals.lastOrNull()
                )
            } catch (e: Exception) {
                _uiState.value = OscillatorUiState.Error(
                    "분석 실패: ${e.message ?: "알 수 없는 오류"}"
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}

/** UI 상태 */
sealed class OscillatorUiState {
    data object Idle : OscillatorUiState()
    data class Loading(val message: String) : OscillatorUiState()
    data class Success(
        val chartData: ChartData,
        val signals: List<SignalAnalysis>,
        val latestSignal: SignalAnalysis?
    ) : OscillatorUiState()
    data class Error(val message: String) : OscillatorUiState()
}
