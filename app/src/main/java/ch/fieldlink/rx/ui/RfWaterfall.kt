package ch.fieldlink.rx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fieldlink.rx.model.SpectrumFrame

@Composable
fun RfWaterfall(
    frames: List<SpectrumFrame>,
    modifier: Modifier = Modifier,
) {
    val latest = frames.lastOrNull()
    Box(
        modifier = modifier
            .height(240.dp)
            .background(Color(0xFF030A11)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (frames.isNotEmpty()) {
                val rowHeight = size.height / frames.size.coerceAtLeast(1)
                frames.forEachIndexed { rowIndex, frame ->
                    val cellWidth = size.width / frame.bins.size.coerceAtLeast(1)
                    frame.bins.forEachIndexed { bin, power ->
                        drawRect(
                            color = rfColor(power),
                            topLeft = Offset(bin * cellWidth, rowIndex * rowHeight),
                            size = androidx.compose.ui.geometry.Size(cellWidth + 1f, rowHeight + 1f),
                        )
                    }
                }
            }
            for (index in 0..4) {
                val x = size.width * index / 4f
                drawLine(Color.White.copy(alpha = 0.20f), Offset(x, 0f), Offset(x, size.height), 1f)
            }
            drawLine(
                Color.Red.copy(alpha = 0.85f),
                Offset(size.width / 2f, 0f),
                Offset(size.width / 2f, size.height),
                2f,
            )
        }
        Text(
            text = if (latest == null) {
                "RTL-SDR · 2.4 MHz"
            } else {
                val center = (latest.minFrequencyHz.toLong() + latest.maxFrequencyHz.toLong()) / 2L
                "${formatRf(latest.minFrequencyHz.toLong())}   ${formatRf(center)}   ${formatRf(latest.maxFrequencyHz.toLong())}"
            },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun formatRf(frequencyHz: Long): String = when {
    frequencyHz >= 1_000_000_000L -> "%.4f GHz".format(frequencyHz / 1_000_000_000.0)
    frequencyHz >= 1_000_000L -> "%.4f MHz".format(frequencyHz / 1_000_000.0)
    else -> "%.1f kHz".format(frequencyHz / 1_000.0)
}

private fun rfColor(db: Float): Color {
    val value = ((db + 115f) / 95f).coerceIn(0f, 1f)
    return when {
        value < 0.25f -> Color(0f, value * 0.8f, 0.20f + value * 1.4f)
        value < 0.55f -> Color(0f, 0.35f + value * 0.8f, 0.9f - value * 0.7f)
        value < 0.80f -> Color((value - 0.5f) * 2f, 0.95f, 0.12f)
        else -> Color(1f, (1.0f - value) * 3f + 0.1f, 0.05f)
    }
}
