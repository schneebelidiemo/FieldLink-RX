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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fieldlink.rx.model.SpectrumFrame

@Composable
fun Waterfall(
    frames: List<SpectrumFrame>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(220.dp)
            .background(Color(0xFF030A11)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (frames.isEmpty()) {
                drawFrequencyGrid()
                return@Canvas
            }
            val rowHeight = size.height / frames.size.coerceAtLeast(1)
            frames.forEachIndexed { rowIndex, frame ->
                val cellWidth = size.width / frame.bins.size.coerceAtLeast(1)
                frame.bins.forEachIndexed { bin, power ->
                    drawRect(
                        color = waterfallColor(power),
                        topLeft = Offset(bin * cellWidth, rowIndex * rowHeight),
                        size = androidx.compose.ui.geometry.Size(cellWidth + 1f, rowHeight + 1f),
                    )
                }
            }
            drawFrequencyGrid()
        }
        Text(
            text = "0          1 kHz          2 kHz          3 kHz",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun DrawScope.drawFrequencyGrid() {
    for (index in 0..3) {
        val x = size.width * index / 3f
        drawLine(Color.White.copy(alpha = 0.20f), Offset(x, 0f), Offset(x, size.height), 1f)
    }
}

private fun waterfallColor(db: Float): Color {
    val value = ((db + 105f) / 85f).coerceIn(0f, 1f)
    return when {
        value < 0.25f -> Color(0f, value * 0.8f, 0.20f + value * 1.4f)
        value < 0.55f -> Color(0f, 0.35f + value * 0.8f, 0.9f - value * 0.7f)
        value < 0.80f -> Color((value - 0.5f) * 2f, 0.95f, 0.12f)
        else -> Color(1f, (1.0f - value) * 3f + 0.1f, 0.05f)
    }
}

