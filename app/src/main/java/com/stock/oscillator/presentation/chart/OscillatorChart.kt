package com.stock.oscillator.presentation.chart

import android.graphics.Color
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.stock.oscillator.domain.model.ChartData

/**
 * 수급 오실레이터 차트 Composable
 *
 * 엑셀 '수급오실레이터' 시트 차트 구성 재현:
 * - 왼쪽 Y축: 시가총액 (조원) — LineChart
 * - 오른쪽 Y축: 오실레이터 — BarChart (양수:녹색, 음수:빨간색)
 * - X축: 날짜
 */
@Composable
fun OscillatorChart(
    chartData: ChartData,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 차트 제목 (엑셀: C7 = 종목명 & " 시가총액", D7 = 종목명 & " 수급오실레이터")
        Text(
            text = "${chartData.stockName} 시가총액 & 수급오실레이터",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // 이중 Y축 차트
        AndroidView(
            factory = { context ->
                CombinedChart(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setupChart(this)
                }
            },
            update = { chart ->
                bindData(chart, chartData)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
        )
    }
}

private fun setupChart(chart: CombinedChart) {
    chart.apply {
        description.isEnabled = false
        setDrawGridBackground(false)
        setDrawBarShadow(false)
        isHighlightFullBarEnabled = false
        setDrawOrder(arrayOf(
            CombinedChart.DrawOrder.BAR,
            CombinedChart.DrawOrder.LINE
        ))

        // 왼쪽 Y축: 시가총액 (조)
        axisLeft.apply {
            setDrawGridLines(true)
            textColor = Color.parseColor("#1976D2")
            axisMinimum = 0f
        }

        // 오른쪽 Y축: 오실레이터
        axisRight.apply {
            setDrawGridLines(false)
            textColor = Color.parseColor("#388E3C")
        }

        // X축
        xAxis.apply {
            granularity = 1f
            setDrawGridLines(false)
            labelRotationAngle = -45f
        }

        legend.isEnabled = true
        setTouchEnabled(true)
        isDragEnabled = true
        setScaleEnabled(true)
        setPinchZoom(true)
    }
}

private fun bindData(chart: CombinedChart, chartData: ChartData) {
    val rows = chartData.rows

    // X축 라벨 (날짜)
    val labels = rows.map { row ->
        // "yyyyMMdd" → "MM/dd" 변환
        if (row.date.length >= 8) {
            "${row.date.substring(4, 6)}/${row.date.substring(6, 8)}"
        } else row.date
    }
    chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)

    // === 라인 데이터: 시가총액 (조원) — 왼쪽 Y축 ===
    val mcapEntries = rows.mapIndexed { i, row ->
        Entry(i.toFloat(), row.marketCapTril.toFloat())
    }
    val mcapDataSet = LineDataSet(mcapEntries, "${chartData.stockName} 시가총액(조)").apply {
        color = Color.parseColor("#1976D2")       // 파란색
        lineWidth = 2f
        setDrawCircles(false)
        setDrawValues(false)
        axisDependency = YAxis.AxisDependency.LEFT
    }

    // === 바 데이터: 오실레이터 — 오른쪽 Y축 ===
    val oscEntries = rows.mapIndexed { i, row ->
        BarEntry(i.toFloat(), row.oscillator.toFloat())
    }
    val oscDataSet = BarDataSet(oscEntries, "수급오실레이터").apply {
        // 양수: 녹색, 음수: 빨간색
        colors = rows.map { row ->
            if (row.oscillator >= 0) Color.parseColor("#4CAF50")
            else Color.parseColor("#F44336")
        }
        setDrawValues(false)
        axisDependency = YAxis.AxisDependency.RIGHT
    }

    // CombinedData 조합
    val combinedData = CombinedData().apply {
        setData(LineData(mcapDataSet))
        setData(BarData(oscDataSet).apply {
            barWidth = 0.6f
        })
    }

    chart.data = combinedData
    chart.invalidate()
}

/**
 * MACD 상세 차트 (선택적 하단 차트)
 *
 * - MACD 선 (파란색)
 * - 시그널 선 (주황색)
 * - 오실레이터 히스토그램 (녹색/빨간색)
 */
@Composable
fun MacdDetailChart(
    chartData: ChartData,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "${chartData.stockName} MACD 상세",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        AndroidView(
            factory = { context ->
                CombinedChart(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setupChart(this)
                    axisLeft.axisMinimum = Float.MIN_VALUE  // 음수 허용
                }
            },
            update = { chart ->
                bindMacdData(chart, chartData)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
        )
    }
}

private fun bindMacdData(chart: CombinedChart, chartData: ChartData) {
    val rows = chartData.rows

    val labels = rows.map { row ->
        if (row.date.length >= 8) "${row.date.substring(4, 6)}/${row.date.substring(6, 8)}"
        else row.date
    }
    chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)

    // MACD 선
    val macdEntries = rows.mapIndexed { i, row -> Entry(i.toFloat(), row.macd.toFloat()) }
    val macdDataSet = LineDataSet(macdEntries, "MACD").apply {
        color = Color.parseColor("#1976D2")
        lineWidth = 1.5f
        setDrawCircles(false)
        setDrawValues(false)
        axisDependency = YAxis.AxisDependency.LEFT
    }

    // 시그널 선
    val signalEntries = rows.mapIndexed { i, row -> Entry(i.toFloat(), row.signal.toFloat()) }
    val signalDataSet = LineDataSet(signalEntries, "시그널").apply {
        color = Color.parseColor("#FF9800")
        lineWidth = 1.5f
        setDrawCircles(false)
        setDrawValues(false)
        axisDependency = YAxis.AxisDependency.LEFT
    }

    // 오실레이터 바
    val oscEntries = rows.mapIndexed { i, row -> BarEntry(i.toFloat(), row.oscillator.toFloat()) }
    val oscDataSet = BarDataSet(oscEntries, "오실레이터").apply {
        colors = rows.map { row ->
            if (row.oscillator >= 0) Color.parseColor("#4CAF50")
            else Color.parseColor("#F44336")
        }
        setDrawValues(false)
        axisDependency = YAxis.AxisDependency.LEFT
    }

    chart.data = CombinedData().apply {
        setData(LineData(macdDataSet, signalDataSet))
        setData(BarData(oscDataSet).apply { barWidth = 0.6f })
    }
    chart.invalidate()
}
