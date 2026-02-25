package com.stock.oscillator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stock.oscillator.domain.model.CrossSignal
import com.stock.oscillator.domain.model.SignalAnalysis
import com.stock.oscillator.domain.model.Trend
import com.stock.oscillator.presentation.chart.MacdDetailChart
import com.stock.oscillator.presentation.chart.OscillatorChart
import com.stock.oscillator.presentation.viewmodel.OscillatorUiState
import com.stock.oscillator.presentation.viewmodel.OscillatorViewModel
import com.stock.oscillator.ui.theme.SupplyOscillatorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SupplyOscillatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OscillatorScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OscillatorScreen(viewModel: OscillatorViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selectedStockName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("수급 오실레이터") })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 검색 입력
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        viewModel.searchStock(it)
                    },
                    label = { Text("종목명 또는 종목코드") },
                    placeholder = { Text("예: 삼성전자, 005930") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        if (searchResults.isNotEmpty()) {
                            val first = searchResults.first()
                            selectedStockName = first.name
                            viewModel.analyze(first.ticker, first.name)
                            query = first.name
                        }
                    }),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 검색 결과
            if (searchResults.isNotEmpty() && uiState is OscillatorUiState.Idle) {
                items(searchResults.take(5)) { result ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedStockName = result.name
                                query = result.name
                                viewModel.analyze(result.ticker, result.name)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(result.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${result.ticker} (${result.market})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 상태별 UI
            when (val state = uiState) {
                is OscillatorUiState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(state.message)
                            }
                        }
                    }
                }

                is OscillatorUiState.Error -> {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                is OscillatorUiState.Success -> {
                    // 최신 신호 카드
                    state.latestSignal?.let { signal ->
                        item { SignalCard(signal) }
                    }

                    // 시가총액 & 오실레이터 차트
                    item {
                        OscillatorChart(chartData = state.chartData)
                    }

                    // MACD 상세 차트
                    item {
                        MacdDetailChart(chartData = state.chartData)
                    }
                }

                is OscillatorUiState.Idle -> { /* 초기 상태 */ }
            }
        }
    }
}

@Composable
private fun SignalCard(signal: SignalAnalysis) {
    val trendText = when (signal.trend) {
        Trend.BULLISH -> "강세"
        Trend.BEARISH -> "약세"
        Trend.NEUTRAL -> "중립"
    }
    val trendColor = when (signal.trend) {
        Trend.BULLISH -> MaterialTheme.colorScheme.primary
        Trend.BEARISH -> MaterialTheme.colorScheme.error
        Trend.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val crossText = when (signal.crossSignal) {
        CrossSignal.GOLDEN_CROSS -> "골든크로스 (매수 신호)"
        CrossSignal.DEAD_CROSS -> "데드크로스 (매도 신호)"
        null -> null
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("최신 분석", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("추세")
                Text(trendText, color = trendColor)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("시가총액")
                Text("${String.format("%.2f", signal.marketCapTril)}조")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("오실레이터")
                Text(String.format("%.6e", signal.oscillator))
            }
            crossText?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (signal.crossSignal == CrossSignal.GOLDEN_CROSS)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
