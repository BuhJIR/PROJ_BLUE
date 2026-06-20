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
import java.util.Random

// ── Изометрические константы ──────────────────────────────────────────────────
const val TILE_W  = 94f
const val TILE_H  = 47f
const val TILE_W2 = TILE_W / 2f
const val TILE_H2 = TILE_H / 2f
const val TILE_LIFT = 14f   // пикселей подъёма на единицу высоты

fun isoToScreen(col: Float, row: Float, height: Float = 0f): Offset = Offset(
    x = (col - row) * TILE_W2,
    y = (col + row) * TILE_H2 - height * TILE_LIFT
)

fun screenToIso(sx: Float, sy: Float): Pair<Int, Int> {
    val col = (sx / TILE_W2 + sy / TILE_H2) / 2f
    val row = (sy / TILE_H2 - sx / TILE_W2) / 2f
    return col.roundToInt() to row.roundToInt()
}

// ── Типы тайлов ───────────────────────────────────────────────────────────────
enum class TileType { GRASS, DIRT, STONE, WATER, WOOD, VOID }

data class LayeredTile(val base: TileType, val height: Int = 0)

val TILE_TOP = mapOf(
    TileType.GRASS to Color(0xFF5D9B50),
    TileType.DIRT  to Color(0xFFA07820),
    TileType.STONE to Color(0xFF858585),
    TileType.WATER to Color(0xFF2E86C1),
    TileType.WOOD  to Color(0xFF7B4F2A),
    TileType.VOID  to Color.Transparent,
)
val TILE_LEFT = mapOf(
    TileType.GRASS to Color(0xFF4A7C3F),
    TileType.DIRT  to Color(0xFF8B6914),
    TileType.STONE to Color(0xFF6B6B6B),
    TileType.WATER to Color(0xFF1A5276),
    TileType.WOOD  to Color(0xFF5D3A1A),
    TileType.VOID  to Color.Transparent,
)
val TILE_RIGHT = mapOf(
    TileType.GRASS to Color(0xFF2E5A28),
    TileType.DIRT  to Color(0xFF5C4510),
    TileType.STONE to Color(0xFF444444),
    TileType.WATER to Color(0xFF0E3A5C),
    TileType.WOOD  to Color(0xFF3D2210),
    TileType.VOID  to Color.Transparent,
)

// ── Карта ─────────────────────────────────────────────────────────────────────
data class IsoMap(
    val cols: Int = 32,
    val rows: Int = 32,
    val centerCol: Int = 16,
    val centerRow: Int = 16,
    val tiles: Array<Array<LayeredTile>> = Array(32) { Array(32) { LayeredTile(TileType.GRASS) } },
) {
    fun tileAt(col: Int, row: Int): LayeredTile {
        val lc = col - (centerCol - cols / 2)
        val lr = row - (centerRow - rows / 2)
        if (lc in 0 until cols && lr in 0 until rows) return tiles[lr][lc]
        return LayeredTile(TileType.VOID)
    }
    fun tileTypeAt(col: Int, row: Int) = tileAt(col, row).base

    // Для Pathfinder совместимость
    fun isWalkable(col: Int, row: Int): Boolean {
        val t = tileAt(col, row).base
        return t != TileType.VOID && t != TileType.WATER
    }
}

// ── Процедурная генерация ──────────────────────────────────────────────────────
fun generateProceduralLandscape(
    seed: Long = 14159265358979323L,
    cols: Int = 32,
    rows: Int = 32,
    centerCol: Int = 16,
    centerRow: Int = 16,
): IsoMap {
    val rng = Random(seed)
    val tiles = Array(rows) { r ->
        Array(cols) { c ->
            val nx = c * 0.22
            val ny = r * 0.25
            val noise = (sin(ny + seed * 0.0001) * 0.6 +
                         cos(nx * 1.3)            * 0.5 +
                         sin(nx + ny)             * 0.4 +
                         rng.nextFloat()          * 0.35).toFloat()
            val hn    = (sin(r * 0.15) * 0.4 +
                         cos(c * 0.18) * 0.4 +
                         rng.nextFloat() * 0.3).toFloat()

            val border = r < 3 || r > rows - 4 || c < 3 || c > cols - 4
            val base = when {
                border          -> TileType.STONE
                noise > 1.1f    -> TileType.WATER
                noise > 0.75f   -> TileType.DIRT
                noise > 0.3f && (r + c) % 4 == 0 -> TileType.WOOD
                else            -> TileType.GRASS
            }
            val height = if (base == TileType.WATER) 0
                         else (hn * 4f).toInt().coerceIn(0, 4)
            LayeredTile(base, height)
        }
    }
    return IsoMap(cols, rows, centerCol, centerRow, tiles)
}

fun regenerateAroundPlayer(engine: GameEngine, oldMap: IsoMap): IsoMap {
    val p = engine.currentState().player
    val seed = p.col * 31L + p.row * 17L + 14159265358979323L
    return generateProceduralLandscape(seed, oldMap.cols, oldMap.rows, p.col, p.row)
}

// ── Sprite cache ───────────────────────────────────────────────────────────────
object SpriteCache {
    private val cache = HashMap<String, ImageBitmap?>()
    fun load(context: Context, assetPath: String): ImageBitmap? {
        cache[assetPath]?.let { return it }
        return try {
            context.assets.open(assetPath).use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }.also { cache[assetPath] = it }
        } catch (e: Exception) { null }
    }
}

// ── Направления ───────────────────────────────────────────────────────────────
enum class Direction { SOUTH, NORTH, WEST, EAST }

fun spritePath(charName: String, dir: Direction): String {
    val base = when {
        charName.contains("skull", ignoreCase = true) -> "sprites/enemy_skull"
        charName.contains("red",   ignoreCase = true) -> "sprites/enemy_red"
        charName.contains("ice",   ignoreCase = true) -> "sprites/hero_ice"
        charName.contains("sister_3", ignoreCase = true) ||
        charName.equals("Hero", ignoreCase = true)    -> "sprites/sister_3"
        charName.contains("sister_4", ignoreCase = true) -> "sprites/sister_4"
        charName.contains("sister_5", ignoreCase = true) -> "sprites/sister_5"
        charName.contains("sister_6", ignoreCase = true) -> "sprites/sister_6"
        else -> "sprites/hero_white"
    }
    return "${base}_${dir.name.lowercase()}.png"
}

// ── Анимация спрайта ───────────────────────────────────────────────────────────
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
    map: IsoMap = remember { generateProceduralLandscape() },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var camOffset by remember { mutableStateOf(Offset(0f, -180f)) }
    val spriteFrame by rememberSpriteFrame(45, fps = 12)
    val textMeasurer = rememberTextMeasurer()

    // Динамическая регенерация карты при движении к краю
    var liveMap by remember { mutableStateOf(map) }
    val player = gameState.player
    LaunchedEffect(player.col, player.row) {
        val distToEdgeCol = minOf(
            player.col - (liveMap.centerCol - liveMap.cols / 2),
            (liveMap.centerCol + liveMap.cols / 2) - player.col,
        )
        val distToEdgeRow = minOf(
            player.row - (liveMap.centerRow - liveMap.rows / 2),
            (liveMap.centerRow + liveMap.rows / 2) - player.row,
        )
        if (distToEdgeCol < 6 || distToEdgeRow < 6) {
            engine?.let { liveMap = regenerateAroundPlayer(it, liveMap) }
        }
    }

    val onTileClick: (Int, Int) -> Unit = { col, row ->
        engine?.selectTile(col, row, liveMap)
    }

    Canvas(
        modifier = modifier
            .background(Color(0xFF0A0A1A))
            .pointerInput(Unit) {
                detectDragGestures { _, drag -> camOffset += drag }
            }
            .pointerInput(onTileClick) {
                detectTapGestures { tap ->
                    val cx2 = size.width  / 2f + camOffset.x
                    val cy2 = size.height / 3f + camOffset.y
                    val (col, row) = screenToIso(tap.x - cx2, tap.y - cy2)
                    onTileClick(col, row)
                }
            }
    ) {
        val cx = size.width  / 2f + camOffset.x
        val cy = size.height / 3f + camOffset.y

        // ── Тайлы (сортировка: сначала дальние — col+row по возрастанию) ──────
        val pathSet   = engine?.currentPath?.map { it.col to it.row }?.toSet() ?: emptySet()
        val selTile   = engine?.selectedTile
        val colOffset = liveMap.centerCol - liveMap.cols / 2
        val rowOffset = liveMap.centerRow - liveMap.rows / 2

        for (r in 0 until liveMap.rows) {
            for (c in 0 until liveMap.cols) {
                val lt = liveMap.tiles[r][c]
                if (lt.base == TileType.VOID) continue
                val wc = c + colOffset
                val wr = r + rowOffset
                val iso = isoToScreen(wc.toFloat(), wr.toFloat(), lt.height.toFloat())
                val sx = cx + iso.x
                val sy = cy + iso.y
                drawIsoTile(sx, sy, lt.base, lt.height)

                // Подсветка пути
                if (wc to wr in pathSet) {
                    val pp = Path().apply {
                        moveTo(sx,           sy - TILE_H2)
                        lineTo(sx + TILE_W2, sy)
                        lineTo(sx,           sy + TILE_H2)
                        lineTo(sx - TILE_W2, sy)
                        close()
                    }
                    drawPath(pp, Color(0x5500CFFF))
                    drawPath(pp, Color(0xAA00CFFF), style = Stroke(1.5f))
                }

                // Выбранный тайл
                if (selTile != null && wc == selTile.first && wr == selTile.second) {
                    val sp = Path().apply {
                        moveTo(sx,           sy - TILE_H2)
                        lineTo(sx + TILE_W2, sy)
                        lineTo(sx,           sy + TILE_H2)
                        lineTo(sx - TILE_W2, sy)
                        close()
                    }
                    drawPath(sp, Color(0x6600FFAA))
                    drawPath(sp, Color.White, style = Stroke(2f))
                }
            }
        }

        // ── Entity (z-order: col+row) ─────────────────────────────────────────
        val allEntities = (gameState.entities.values + gameState.player)
            .sortedBy { it.col + it.row }

        for (entity in allEntities) {
            val lt    = liveMap.tileAt(entity.col, entity.row)
            val iso   = isoToScreen(entity.col.toFloat(), entity.row.toFloat(), lt.height.toFloat())
            val sx    = cx + iso.x
            val sy    = cy + iso.y
            val isPlayer = entity.id == gameState.player.id

            val dirStr = entity.memory["direction"] as? String ?: "SOUTH"
            val dir    = try { Direction.valueOf(dirStr) } catch (e: Exception) { Direction.SOUTH }
            val key    = spritePath(if (isPlayer) "sister_3" else entity.name, dir)
            val sheet  = SpriteCache.load(context, key)

            if (sheet != null) {
                val frameCount = if (isPlayer) 45 else 2
                val frameW     = sheet.width / frameCount
                val frameH     = sheet.height
                val scale      = if (isPlayer) 0.45f else 1f
                val fi         = if (isPlayer) spriteFrame else (spriteFrame % 2)
                drawSprite(sheet, fi, frameW, frameH,
                    sx - frameW * scale / 2f,
                    sy - frameH * scale * 0.95f,
                    scale)
            } else {
                // Fallback прямоугольник
                val color = when {
                    isPlayer                               -> Color(0xFF44AAFF)
                    entity.hasFlag("ENEMY")                -> Color(0xFFCC2222)
                    entity.hasFlag("PEASANT")              -> Color(0xFFDDCC55)
                    else                                   -> Color(0xFF888888)
                }
                drawOval(Color(0x33000000), Offset(sx - 18f, sy - 6f),
                    androidx.compose.ui.geometry.Size(36f, 12f))
                drawRect(color, Offset(sx - 12f, sy - 48f),
                    androidx.compose.ui.geometry.Size(24f, 36f))
                drawCircle(color, 10f, Offset(sx, sy - 56f))
            }

            // HP bar секционный
            if (entity.maxHp > 0) {
                drawSegmentedHpBar(sx, sy - 64f, entity.hp, entity.maxHp)
            }
        }
    }
}

// ── Рисование изометрического тайла с высотой ────────────────────────────────
fun DrawScope.drawIsoTile(cx: Float, cy: Float, tile: TileType, height: Int = 0) {
    val top   = TILE_TOP[tile]   ?: return
    val left  = TILE_LEFT[tile]  ?: return
    val right = TILE_RIGHT[tile] ?: return
    val sideH = 8f + height * TILE_LIFT

    // Верхняя грань (ромб)
    val topPath = Path().apply {
        moveTo(cx,            cy - TILE_H2)
        lineTo(cx + TILE_W2,  cy)
        lineTo(cx,            cy + TILE_H2)
        lineTo(cx - TILE_W2,  cy)
        close()
    }
    drawPath(topPath, top)
    drawPath(topPath, Color(0x22000000), style = Stroke(0.8f))

    // Боковые грани только если есть высота
    if (sideH > 8f || height > 0) {
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
    } else {
        // Минимальные грани всегда
        val leftPath = Path().apply {
            moveTo(cx - TILE_W2, cy)
            lineTo(cx,           cy + TILE_H2)
            lineTo(cx,           cy + TILE_H2 + 8f)
            lineTo(cx - TILE_W2, cy + 8f)
            close()
        }
        drawPath(leftPath, left)
        val rightPath = Path().apply {
            moveTo(cx,           cy + TILE_H2)
            lineTo(cx + TILE_W2, cy)
            lineTo(cx + TILE_W2, cy + 8f)
            lineTo(cx,           cy + TILE_H2 + 8f)
            close()
        }
        drawPath(rightPath, right)
    }
}

// ── Спрайт из спрайтшита ─────────────────────────────────────────────────────
fun DrawScope.drawSprite(
    sheet: ImageBitmap,
    frameIndex: Int,
    frameW: Int, frameH: Int,
    screenX: Float, screenY: Float,
    scale: Float = 1f,
) {
    val srcLeft = frameIndex * frameW
    if (srcLeft + frameW > sheet.width) return
    withTransform({ scale(scale, scale, Offset.Zero); translate(screenX / scale, screenY / scale) }) {
        drawImage(
            image = sheet,
            srcOffset = IntOffset(srcLeft, 0),
            srcSize   = IntSize(frameW, frameH),
            dstOffset = IntOffset.Zero,
            dstSize   = IntSize(frameW, frameH),
            filterQuality = FilterQuality.None,
        )
    }
}

// ── Секционный HP bar ─────────────────────────────────────────────────────────
fun DrawScope.drawSegmentedHpBar(cx: Float, cy: Float, hp: Int, maxHp: Int) {
    val segments = (maxHp / 6).coerceIn(1, 12)
    val hpPerSeg = maxHp.toFloat() / segments
    val segW = 7f; val segH = 5f; val gap = 1.5f
    val totalW = segments * segW + (segments - 1) * gap
    val startX = cx - totalW / 2f

    drawRect(Color(0xAA000000), Offset(startX - 2f, cy - 2f),
        androidx.compose.ui.geometry.Size(totalW + 4f, segH + 4f))

    for (i in 0 until segments) {
        val segStart = i * hpPerSeg
        val fill = when {
            hp >= segStart + hpPerSeg -> 1f
            hp <= segStart            -> 0f
            else                      -> (hp - segStart) / hpPerSeg
        }
        val x = startX + i * (segW + gap)
        drawRect(Color(0xFF333333), Offset(x, cy),
            androidx.compose.ui.geometry.Size(segW, segH))
        if (fill > 0f) {
            val color = when {
                hp.toFloat() / maxHp > 0.5f  -> Color(0xFF44DD44)
                hp.toFloat() / maxHp > 0.25f -> Color(0xFFDDCC00)
                else                          -> Color(0xFFDD2222)
            }
            drawRect(color, Offset(x, cy),
                androidx.compose.ui.geometry.Size(segW * fill, segH))
            drawRect(Color(0x55FFFFFF), Offset(x, cy),
                androidx.compose.ui.geometry.Size(segW * fill, 1.5f))
        }
    }
}
