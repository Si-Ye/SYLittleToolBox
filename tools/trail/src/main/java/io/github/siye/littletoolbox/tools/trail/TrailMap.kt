package io.github.siye.littletoolbox.tools.trail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos

@Composable
internal fun TrailMap(points: List<TrailPoint>, parking: TrailFix?, current: TrailFix?, routeKey: Long?) {
    val origin = points.firstOrNull()?.fix ?: parking ?: current
    if (origin == null) {
        TrailDetail("获得有效定位或标记停车点后，这里会显示位置。")
        return
    }
    val route = remember(points, origin) {
        points.map { PlottedPoint(project(it.fix, origin), it.breakBefore) }
    }
    val parkingPosition = parking?.let { project(it, origin) }
    val currentPosition = current?.let { project(it, origin) }
    val coordinates = remember(route, parkingPosition, currentPosition) {
        route.map { it.position } + listOfNotNull(parkingPosition, currentPosition)
    }
    val gaps = remember(points) { points.drop(1).count { it.breakBefore } }
    var zoom by remember(routeKey) { mutableFloatStateOf(1f) }
    var pan by remember(routeKey) { mutableStateOf(Offset.Zero) }
    val gestures = rememberTransformableState { factor, translation, _ ->
        zoom = (zoom * factor).coerceIn(1f, 16f)
        pan = if (zoom > 1f) pan + translation else Offset.Zero
    }
    val colors = MaterialTheme.colorScheme
    val description = buildString {
        append("离线轨迹图，${points.size} 个记录点")
        if (gaps > 0) append("，$gaps 处定位缺口")
        if (parkingPosition != null) append("，已标出停车点")
        if (currentPosition != null) append("，已标出当前位置")
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surface)
            .transformable(state = gestures, canPan = { zoom > 1f })
            .semantics { contentDescription = description },
    ) {
        val padding = 24.dp.toPx()
        val gridSize = 24.dp.toPx()
        val line = colors.outlineVariant.copy(alpha = .64f)
        for (column in 1..(size.width / gridSize).toInt()) {
            val x = column * gridSize
            drawLine(line, Offset(x, 0f), Offset(x, size.height))
        }
        for (row in 1..(size.height / gridSize).toInt()) {
            val y = row * gridSize
            drawLine(line, Offset(0f, y), Offset(size.width, y))
        }

        val west = coordinates.minOf { it.x }
        val east = coordinates.maxOf { it.x }
        val north = coordinates.minOf { it.y }
        val south = coordinates.maxOf { it.y }
        val middle = Offset((west + east) / 2, (north + south) / 2)
        val fit = minOf(
            (size.width - padding * 2).coerceAtLeast(1f) / (east - west).coerceAtLeast(14f),
            (size.height - padding * 2).coerceAtLeast(1f) / (south - north).coerceAtLeast(14f),
        )
        fun screen(position: Offset) = center + pan + (position - middle) * (fit * zoom)

        val path = Path()
        route.forEachIndexed { index, point ->
            val plotted = screen(point.position)
            if (index == 0 || point.breakBefore) path.moveTo(plotted.x, plotted.y)
            else path.lineTo(plotted.x, plotted.y)
        }
        drawPath(path, colors.primary, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        route.forEachIndexed { index, point ->
            if (index == 0 || point.breakBefore || route.getOrNull(index + 1)?.breakBefore == true) {
                val plotted = screen(point.position)
                drawCircle(colors.surface, 4.dp.toPx(), plotted)
                drawCircle(colors.primary, 4.dp.toPx(), plotted, style = Stroke(2.dp.toPx()))
            }
        }
        if (parkingPosition != null) {
            val parked = screen(parkingPosition)
            drawCircle(colors.surface, 10.dp.toPx(), parked)
            drawCircle(colors.tertiary, 8.dp.toPx(), parked, style = Stroke(3.dp.toPx()))
        }
        if (currentPosition != null) {
            val here = screen(currentPosition)
            drawCircle(colors.surface, 6.dp.toPx(), here)
            drawCircle(colors.primary, 4.dp.toPx(), here)
        }
    }
    if (parkingPosition != null || currentPosition != null || gaps > 0) {
        val legend = listOfNotNull(
            "○ 停车点".takeIf { parkingPosition != null },
            "● 当前位置".takeIf { currentPosition != null },
            "定位缺口 $gaps 处".takeIf { gaps > 0 },
        )
        TrailDetail(legend.joinToString("　"))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = {
                zoom = (zoom / 1.5f).coerceAtLeast(1f)
                if (zoom == 1f) pan = Offset.Zero
            },
            enabled = zoom > 1f,
        ) { Text("缩小") }
        TextButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(16f) }, enabled = zoom < 16f) {
            Text("放大")
        }
        TextButton(
            onClick = {
                zoom = 1f
                pan = Offset.Zero
            },
            enabled = zoom > 1f,
        ) { Text("适应视图") }
    }
}

private data class PlottedPoint(val position: Offset, val breakBefore: Boolean)

private fun project(fix: TrailFix, origin: TrailFix): Offset {
    val latitudeScale = 111_195.0
    val longitudeScale = latitudeScale * cos(Math.toRadians(origin.latitude))
    val longitudeDelta = ((fix.longitude - origin.longitude + 540) % 360) - 180
    return Offset(
        (longitudeDelta * longitudeScale).toFloat(),
        ((origin.latitude - fix.latitude) * latitudeScale).toFloat(),
    )
}
