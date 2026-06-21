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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.abs

// ── Константы ──────────────────────────────────────────────────────────────────
const val TILE_W    = 94f
const val TILE_H    = 47f
const val TILE_W2   = TILE_W / 2f
const val TILE_H2   = TILE_H / 2f
const val TILE_LIFT = 12f   // подъём на 1 единицу высоты

fun isoToScreen(col: Float, row: Float, height: Float = 0f): Offset = Offset(
    x = (col - row) * TILE_W2,
    y = (col + row) * TILE_H2 - height * TILE_LIFT
)

fun screenToIso(sx: Float, sy: Float): Pair<Int, Int> {
    val col = (sx / TILE_W2 + sy / TILE_H2) / 2f
    val row = (sy / TILE_H2 - sx / TILE_W2) / 2f
    return col.roundToInt() to row.roundToInt()
}

// ── Тайлы ──────────────────────────────────────────────────────────────────────
enum class TileType { GRASS, DIRT, STONE, WATER, WOOD, VOID }

data class LayeredTile(val base: TileType, val height: Int = 0)

val TILE_TOP = mapOf(
    TileType.GRASS to Color(0xFF5D9B50), TileType.DIRT  to Color(0xFFA07820),
    TileType.STONE to Color(0xFF858585), TileType.WATER to Color(0xFF2E86C1),
    TileType.WOOD  to Color(0xFF7B4F2A), TileType.VOID  to Color(0xFF2A2A3A),
)
val TILE_LEFT = mapOf(
    TileType.GRASS to Color(0xFF4A7C3F), TileType.DIRT  to Color(0xFF8B6914),
    TileType.STONE to Color(0xFF6B6B6B), TileType.WATER to Color(0xFF1A5276),
    TileType.WOOD  to Color(0xFF5D3A1A), TileType.VOID  to Color(0xFF1A1A2A),
)
val TILE_RIGHT = mapOf(
    TileType.GRASS to Color(0xFF2E5A28), TileType.DIRT  to Color(0xFF5C4510),
    TileType.STONE to Color(0xFF444444), TileType.WATER to Color(0xFF0E3A5C),
    TileType.WOOD  to Color(0xFF3D2210), TileType.VOID  to Color(0xFF111122),
)

// ── Карта ──────────────────────────────────────────────────────────────────────
data class IsoMap(
    val cols: Int = 48,
    val rows: Int = 48,
    val centerCol: Int = 0,
    val centerRow: Int = 0,
    val tiles: Array<Array<LayeredTile>> = Array(48) { Array(48) { LayeredTile(TileType.GRASS) } },
) {
    // localCol/localRow → индекс в массиве
    private fun local(worldCol: Int, worldRow: Int): Pair<Int,Int> {
        val lc = worldCol - centerCol + cols / 2
        val lr = worldRow - centerRow + rows / 2
        return lc to lr
    }

    fun tileAt(worldCol: Int, worldRow: Int): LayeredTile {
        val (lc, lr) = local(worldCol, worldRow)
        if (lc in 0 until cols && lr in 0 until rows) return tiles[lr][lc]
        // За пределами — генерируем на лету из глобального noise (без обрыва)
        return generateTile(worldCol, worldRow, WORLD_SEED)
    }

    fun isWalkable(col: Int, row: Int): Boolean {
        val t = tileAt(col, row).base
        return t != TileType.WATER
    }

    companion object {
        // Единый глобальный seed — не меняется никогда
        const val WORLD_SEED = 14159265358979323L
    }
}

// ── Процедурная генерация тайла по мировым координатам (детерминированно) ─────
fun generateTile(worldCol: Int, worldRow: Int, seed: Long): LayeredTile {
    val nx = worldCol * 0.18
    val ny = worldRow * 0.21
    val s  = seed * 0.000000001

    val noise = (sin(ny + s)          * 0.55 +
                 cos(nx * 1.3 + s)    * 0.45 +
                 sin(nx + ny)         * 0.40 +
                 sin(nx * 2.1 - ny)   * 0.20).toFloat()

    val hn    = (sin(worldRow * 0.12 + s) * 0.4 +
                 cos(worldCol * 0.15 + s) * 0.4 +
                 sin((worldCol + worldRow) * 0.08) * 0.2).toFloat()

    val base = when {
        noise >  1.1f -> TileType.WATER
        noise >  0.8f -> TileType.STONE
        noise >  0.5f -> TileType.DIRT
        (worldCol + worldRow) % 7 == 0 && noise > 0f -> TileType.WOOD
        else          -> TileType.GRASS
    }
    val height = if (base == TileType.WATER) 0
                 else ((hn + 1f) * 2f).toInt().coerceIn(0, 4)
    return LayeredTile(base, height)
}

// ── Генерация буфера карты вокруг центра ──────────────────────────────────────
fun generateMapAround(centerCol: Int, centerRow: Int, cols: Int = 48, rows: Int = 48): IsoMap {
    val tiles = Array(rows) { r ->
        Array(cols) { c ->
            val wc = c - cols / 2 + centerCol
            val wr = r - rows / 2 + centerRow
            generateTile(wc, wr, IsoMap.WORLD_SEED)
        }
    }
    return IsoMap(cols, rows, centerCol, centerRow, tiles)
}

// ── Sprite cache ───────────────────────────────────────────────────────────────
object SpriteCache {
    private val cache = HashMap<String, ImageBitmap?>()
    fun load(context: Context, assetPath: String): ImageBitmap? {
        cache[assetPath]?.let { return it }
        return try {
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                .also { cache[assetPath] = it }
        } catch (e: Exception) { null }
    }
}

// ── Направления ───────────────────────────────────────────────────────────────
enum class Direction { SOUTH, NORTH, WEST, EAST }

fun spritePath(charName: String, dir: Direction): String {
    val base = when {
        charName.contains("skull",    ignoreCase = true) -> "sprites/enemy_skull"
        charName.contains("red",      ignoreCase = true) -> "sprites/enemy_red"
        charName.contains("ice",      ignoreCase = true) -> "sprites/hero_ice"
        charName.contains("sister_4", ignoreCase = true) -> "sprites/sister_4"
        charName.contains("sister_5", ignoreCase = true) -> "sprites/sister_5"
        charName.contains("sister_6", ignoreCase = true) -> "sprites/sister_6"
        charName.contains("sister_3", ignoreCase = true) ||
        charName.equals("Hero",       ignoreCase = true) -> "sprites/sister_3"
        else -> "sprites/hero_white"
    }
    return "${base}_${dir.name.lowercase()}.png"
}

// ── Анимация ───────────────────────────────────────────────────────────────────
@Composable
fun rememberSpriteFrame(totalFrames: Int, fps: Int = 12): State<Int> {
    val frame = remember { mutableIntStateOf(0) }
    val msPerFrame = 1000L / fps
    LaunchedEffect(totalFrames, fps) {
        while (true) {
            withFrameMillis { ms ->
                frame.intValue = ((ms / msPerFrame) % totalFrames).toInt()
            }
        }
    }
    return frame
}

// ── Главный рендерер ──────────────────────────────────────────────────────────
@Composable
fun IsoMapRenderer(
    gameState: GameState,
    engine: GameEngine? = null,
    map: IsoMap = remember { generateMapAround(0, 0) },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var camOffset by remember { mutableStateOf(Offset(0f, -180f)) }
    val spriteFrame by rememberSpriteFrame(45, fps = 12)

    // Карта регенерируется когда игрок близко к краю — ОДНИМ seed'ом
    var liveMap by remember { mutableStateOf(map) }
    val player = gameState.player
    LaunchedEffect(player.col, player.row) {
        val halfC = liveMap.cols / 2
        val halfR = liveMap.rows / 2
        val dc = player.col - liveMap.centerCol
        val dr = player.row - liveMap.centerRow
        // Рестартуем буфер если игрок ближе 8 тайлов к краю
        if (abs(dc) > halfC - 8 || abs(dr) > halfR - 8) {
            liveMap = generateMapAround(player.col, player.row)
        }
    }

    val onTileClick: (Int, Int) -> Unit = { col, row -> engine?.selectTile(col, row, liveMap) }

    Canvas(
        modifier = modifier
            .background(Color(0xFF0A0A1A))
            .pointerInput(Unit)  { detectDragGestures { _, d -> camOffset += d } }
            .pointerInput(onTileClick) {
                detectTapGestures { tap ->
                    val cx2 = size.width  / 2f + camOffset.x
                    val cy2 = size.height / 3f + camOffset.y
                    val (c, r) = screenToIso(tap.x - cx2, tap.y - cy2)
                    onTileClick(c, r)
                }
            }
    ) {
        val cx = size.width  / 2f + camOffset.x
        val cy = size.height / 3f + camOffset.y

        val pathSet = engine?.currentPath?.map { it.col to it.row }?.toSet() ?: emptySet()
        val selTile = engine?.selectedTile

        // ── Тайлы ─────────────────────────────────────────────────────────────
        val halfC = liveMap.cols / 2
        val halfR = liveMap.rows / 2
        val wStartC = liveMap.centerCol - halfC
        val wStartR = liveMap.centerRow - halfR

        for (lr in 0 until liveMap.rows) {
            for (lc in 0 until liveMap.cols) {
                val wc = lc + wStartC
                val wr = lr + wStartR
                val lt = liveMap.tiles[lr][lc]
                val iso = isoToScreen(wc.toFloat(), wr.toFloat(), lt.height.toFloat())
                val sx = cx + iso.x
                val sy = cy + iso.y

                // Не рисуем то что точно за экраном
                if (sx < -TILE_W || sx > size.width + TILE_W) continue
                if (sy < -TILE_H * 6 || sy > size.height + TILE_H * 6) continue

                drawIsoTile(sx, sy, lt.base, lt.height)

                if (wc to wr in pathSet) {
                    val p = isoRhombus(sx, sy)
                    drawPath(p, Color(0x5500CFFF))
                    drawPath(p, Color(0xAA00CFFF), style = Stroke(1.5f))
                }
                if (selTile != null && wc == selTile.first && wr == selTile.second) {
                    val p = isoRhombus(sx, sy)
                    drawPath(p, Color(0x6600FFAA))
                    drawPath(p, Color.White, style = Stroke(2f))
                }
            }
        }

        // ── Entity ─────────────────────────────────────────────────────────────
        val allEntities = (gameState.entities.values + gameState.player).sortedBy { it.col + it.row }

        for (entity in allEntities) {
            val lt    = liveMap.tileAt(entity.col, entity.row)
            val iso   = isoToScreen(entity.col.toFloat(), entity.row.toFloat(), lt.height.toFloat())
            val sx    = cx + iso.x
            val sy    = cy + iso.y
            val isPlayer = entity.id == gameState.player.id

            val dirStr = entity.memory["direction"] as? String ?: "SOUTH"
            val dir    = runCatching { Direction.valueOf(dirStr) }.getOrDefault(Direction.SOUTH)
            val key    = spritePath(if (isPlayer) "sister_3" else entity.name, dir)
            val sheet  = SpriteCache.load(context, key)

            if (sheet != null) {
                val frameCount = if (isPlayer) 45 else 2
                val frameW     = sheet.width / frameCount
                val frameH     = sheet.height
                val scale      = if (isPlayer) 0.45f else 1f
                val fi         = if (isPlayer) spriteFrame else (spriteFrame % 2)
                // ── Правильный drawSprite: translate ПЕРВЫМ, потом scale ──────
                translate(sx - frameW * scale / 2f, sy - frameH * scale * 0.95f) {
                    scale(scale, scale, pivot = Offset.Zero) {
                        drawImage(
                            image       = sheet,
                            srcOffset   = IntOffset(fi * frameW, 0),
                            srcSize     = IntSize(frameW, frameH),
                            dstOffset   = IntOffset.Zero,
                            dstSize     = IntSize(frameW, frameH),
                            filterQuality = FilterQuality.None,
                        )
                    }
                }
            } else {
                val color = when {
                    isPlayer               -> Color(0xFF44AAFF)
                    entity.hasFlag("ENEMY")-> Color(0xFFCC2222)
                    else                   -> Color(0xFF888888)
                }
                drawOval(Color(0x33000000), Offset(sx-18f, sy-6f), Size(36f, 12f))
                drawRect(color, Offset(sx-12f, sy-48f), Size(24f, 36f))
                drawCircle(color, 10f, Offset(sx, sy-56f))
            }

            if (entity.maxHp > 0) drawSegmentedHpBar(sx, sy - 64f, entity.hp, entity.maxHp)
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────
fun isoRhombus(cx: Float, cy: Float): Path = Path().apply {
    moveTo(cx,           cy - TILE_H2)
    lineTo(cx + TILE_W2, cy)
    lineTo(cx,           cy + TILE_H2)
    lineTo(cx - TILE_W2, cy)
    close()
}

fun DrawScope.drawIsoTile(cx: Float, cy: Float, tile: TileType, height: Int = 0) {
    val top   = TILE_TOP[tile]   ?: return
    val left  = TILE_LEFT[tile]  ?: return
    val right = TILE_RIGHT[tile] ?: return
    val sideH = 8f + height * TILE_LIFT

    drawPath(isoRhombus(cx, cy), top)
    drawPath(isoRhombus(cx, cy), Color(0x22000000), style = Stroke(0.8f))

    val leftPath = Path().apply {
        moveTo(cx - TILE_W2, cy)
        lineTo(cx,           cy + TILE_H2)
        lineTo(cx,           cy + TILE_H2 + sideH)
        lineTo(cx - TILE_W2, cy + sideH)
        close()
    }
    drawPath(leftPath, left)

    val rightPath = Path().apply {
        moveTo(cx,           cy + TILE_H2)
        lineTo(cx + TILE_W2, cy)
        lineTo(cx + TILE_W2, cy + sideH)
        lineTo(cx,           cy + TILE_H2 + sideH)
        close()
    }
    drawPath(rightPath, right)
}

fun DrawScope.drawSegmentedHpBar(cx: Float, cy: Float, hp: Int, maxHp: Int) {
    val segments = (maxHp / 6).coerceIn(1, 12)
    val hpPerSeg = maxHp.toFloat() / segments
    val segW = 7f; val segH = 5f; val gap = 1.5f
    val totalW = segments * segW + (segments - 1) * gap
    val startX = cx - totalW / 2f
    drawRect(Color(0xAA000000), Offset(startX - 2f, cy - 2f), Size(totalW + 4f, segH + 4f))
    for (i in 0 until segments) {
        val fill = ((hp - i * hpPerSeg) / hpPerSeg).coerceIn(0f, 1f)
        val x = startX + i * (segW + gap)
        drawRect(Color(0xFF333333), Offset(x, cy), Size(segW, segH))
        if (fill > 0f) {
            val color = if (hp.toFloat()/maxHp > 0.5f) Color(0xFF44DD44)
                        else if (hp.toFloat()/maxHp > 0.25f) Color(0xFFDDCC00)
                        else Color(0xFFDD2222)
            drawRect(color, Offset(x, cy), Size(segW * fill, segH))
            drawRect(Color(0x55FFFFFF), Offset(x, cy), Size(segW * fill, 1.5f))
        }
    }
}
