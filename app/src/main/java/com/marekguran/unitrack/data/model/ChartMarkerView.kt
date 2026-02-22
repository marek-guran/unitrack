package com.marekguran.unitrack.data.model

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import com.marekguran.unitrack.R

class ChartMarkerView(context: Context) : MarkerView(context, R.layout.chart_marker_view) {
    private val tvContent: TextView = findViewById(R.id.tvContent)
    var customLabel: String = "Count"

    override fun refreshContent(e: com.github.mikephil.charting.data.Entry?, highlight: Highlight?) {
        when (e) {
            is BarEntry -> {
                tvContent.text = "${xAxisLabels.getOrNull(e.x.toInt()) ?: ""}\n$customLabel: ${e.y.toInt()}"
            }
            is PieEntry -> {
                tvContent.text = "Známka: ${e.label}\nPočet: ${e.value.toInt()}"
            }
            else -> {
                tvContent.text = "Value: ${e?.y}"
            }
        }
        super.refreshContent(e, highlight)
    }

    // You must set this from your fragment for correct mapping!
    var xAxisLabels: List<String> = emptyList()
    private fun getXAxisLabel(x: Int): String {
        return if (x in xAxisLabels.indices) xAxisLabels[x] else x.toString()
    }

    override fun getOffset(): MPPointF {
        return MPPointF(-(width / 2f), -height.toFloat())
    }
}