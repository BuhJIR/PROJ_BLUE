package com.google.ai.edge.gallery.customtasks.tinygarden

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.cos
import java.util.Random

// ── Изометрические константы ─────────────────────────────────────────────────
const val TILE_W = 94f
const val TILE_H = 47f
const val TILE_W2 = TILE_W / 2f
const val TILE_H2 = TILE_H / 2f

fun isoToScreen(col: Float, row: Float, height: Float = 0f): Offset = Offset(
    x = (col - row) * TILE_W2,
    y = (col + row) * TILE_H2 - height * 12f  // measurable lift per height level
)

fun screenToIso(sx: Float, sy: Float): Pair<Int, Int> {
    val col = (sx / TILE_W2 + sy / TILE_H2) / 2f
    val row = (sy / TILE_H2 - sx / TILE_W2) / 2f
    return col.roundToInt() to row.roundToInt()
}

enum class TileType {
    GRASS, DIRT, STONE, WATER, WOOD, VOID
}

val TILE_COLORS = mapOf(
    TileType.GRASS to Color(0xFF4A7C3F),
    TileType.DIRT  to Color(0xFF8B6914),
    TileType.STONE to Color(0xFF6B6B6B),
    TileType.WATER to Color(0xFF1A5276),
    TileType.WOOD  to Color(0xFF5D3A1A),
    TileType.VOID  to Color.Transparent,
)
// ... keep other color maps

// Enhanced map with height layers
data class LayeredTile(val base: TileType, val height: Int = 0)

data class IsoMap(
    val cols: Int = 32,
    val rows: Int = 32,
    val centerCol: Int = 16,
    val centerRow: Int = 16,
    val tiles: Array<Array<LayeredTile>> = Array(32) { Array(32) { LayeredTile(TileType.GRASS) } }
) {
    fun tileAt(col: Int, row: Int): LayeredTile {
        val localCol = col - (centerCol - cols/2)
        val localRow = row - (centerRow - rows/2)
        if (localCol in 0 until cols && localRow in 0 until rows) return tiles[localRow][localCol]
        return LayeredTile(TileType.VOID)
    }
}

fun generateProceduralLandscape(seed: Long = 14159265358979323L, cols: Int = 32, rows: Int = 32, centerCol: Int = 16, centerRow: Int = 16): IsoMap {
    val random = Random(seed)
    val tiles = Array(rows) { r -> Array(cols) { c ->
        val noise = (sin(r * 0.25 + seed) * 0.6 + cos(c * 0.22) * 0.6 + random.nextFloat() * 0.5).toFloat()
        val heightNoise = (sin(r * 0.15) * 0.4 + cos(c * 0.18) * 0.4 + random.nextFloat() * 0.3).toFloat()
        val base = when {
            r < 4 || r > rows-5 || c < 4 || c > cols-5 -> TileType.STONE
            noise > 1.1 -> TileType.WATER
            noise > 0.7 -> TileType.DIRT
            noise > 0.2 && (r + c) % 3 == 0 -> TileType.WOOD
            else -> TileType.GRASS
        }
        val height = (heightNoise * 3f).toInt().coerceIn(0, 4)  // measurable heights 0-4
        LayeredTile(base, height)
    }}
    return IsoMap(cols, rows, centerCol, centerRow, tiles)
}

// Update renderer to support layers and dynamic regen
@Composable
fun IsoMapRenderer(
    gameState: GameState,
    engine: GameEngine? = null,
    map: IsoMap = remember { generateProceduralLandscape() },
    modifier: Modifier = Modifier,
) {
    // ... keep existing
    // In draw loop use height
    for (row in 0 until map.rows) {
        for (col in 0 until map.cols) {
            val lt = map.tiles[row][col]
            if (lt.base == TileType.VOID) continue
            val worldCol = map.centerCol - map.cols/2 + col
            val worldRow = map.centerRow - map.rows/2 + row
            val iso = isoToScreen(worldCol.toFloat(), worldRow.toFloat(), lt.height.toFloat())
            // draw with height offset
            drawIsoTile(cx + iso.x, cy + iso.y, lt.base, lt.height)
        }
    }
    // ... rest
}

fun DrawScope.drawIsoTile(cx: Float, cy: Float, tile: TileType, height: Int = 0) {
    // existing + extra height lift
    val lift = height * 12f
    // adjust paths with lift
    // ... 
}

// Dynamic regen function
fun regenerateAroundPlayer(engine: GameEngine, oldMap: IsoMap): IsoMap {
    val p = engine.currentState().player
    val newSeed = (p.col * 31L + p.row * 17L + System.currentTimeMillis() / 1000)  // deterministic per position
    return generateProceduralLandscape(newSeed, 32, 32, p.col, p.row)
}
