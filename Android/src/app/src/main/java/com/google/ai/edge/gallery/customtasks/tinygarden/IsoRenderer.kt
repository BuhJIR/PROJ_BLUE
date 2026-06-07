package com.google.ai.edge.gallery.customtasks.tinygarden

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import kotlin.math.roundToInt

// ── Изометрические константы ─────────────────────────────────────────────────
const val TILE_W = 94f          // ширина ромба
const val TILE_H = 47f          // высота ромба (2:1)
const val TILE_W2 = TILE_W / 2f // полуширина
const val TILE_H2 = TILE_H / 2f // полувысота

/** Мировые координаты → экранные (изометрия). */
fun isoToScreen(col: Float, row: Float): Offset = Offset(
    x = (col - row) * TILE_W2,
    y = (col + row) * TILE_H2,
)

/** Экранные → мировые (обратное преобразование). */
fun screenToIso(sx: Float, sy: Float): Pair<Int, Int> {
    val col = (sx / TILE_W2 + sy / TILE_H2) / 2f
    val row = (sy / TILE_H2 - sx / TILE_W2) / 2f
    return col.roundToInt() to row.roundToInt()
}

// ── Типы тайлов ───────────────────────────────────────────────────────────────
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
val TILE_COLORS_TOP = mapOf(
    TileType.GRASS to Color(0xFF5D9B50),
    TileType.DIRT  to Color(0xFFA07820),
    TileType.STONE to Color(0xFF858585),
    TileType.WATER to Color(0xFF2E86C1),
    TileType.WOOD  to Color(0xFF7B4F2A),
    TileType.VOID  to Color.Transparent,
)
val TILE_COLORS_SHADOW = mapOf(
    TileType.GRASS to Color(0xFF2E5A28),
    TileType.DIRT  to Color(0xFF5C4510),
    TileType.STONE to Color(0xFF444444),
    TileType.WATER to Color(0xFF0E3A5C),
    TileType.WOOD  to Color(0xFF3D2210),
    TileType.VOID  to Color.Transparent,
)

// ── Карта ─────────────────────────────────────────────────────────────────────
data class IsoMap(
    val cols: Int = 16,
    val rows: Int = 16,
    val tiles: Array<Array<TileType>> = Array(16) { Array(16) { TileType.GRASS } },
) {
    fun tileAt(col: Int, row: Int): TileType =
        if (col in 0 until cols && row in 0 until rows) tiles[row][col] else TileType.VOID
}

/** Генерация тестовой карты. */
fun generateTestMap(): IsoMap {
    val tiles = Array(16) { r -> Array(16) { c ->
        when {
            r == 0 || r == 15 || c == 0 || c == 15 -> TileType.STONE // стены
            r in 6..9 && c in 6..9 -> TileType.WATER  // пруд в центре
            r % 5 == 0 && c % 5 == 0 -> TileType.DIRT  // грязные пятна
            (r + c) % 7 == 0 -> TileType.WOOD           // деревянные тропы
            else -> TileType.GRASS
        }
    }}
    return IsoMap(tiles = tiles)
}

// ── Sprite loader ─────────────────────────────────────────────────────────────
object SpriteCache {
    private val cache = HashMap<String, ImageBitmap?>()

    fun load(context: Context, assetPath: String): ImageBitmap? {
        cache[assetPath]?.let { return it }
        return try {
            context.assets.open(assetPath).use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                bmp?.asImageBitmap()
            }.also { cache[assetPath] = it }
        } catch (e: Exception) { null }
    }
}

// ── Sprite animator ───────────────────────────────────────────────────────────
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
    map: IsoMap = remember { generateTestMap() },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Камера — offset в экранных координатах, двигается тачем
    var camOffset by remember { mutableStateOf(Offset(0f, -200f)) }

    // Загружаем спрайты
    val playerFrontSheet = remember { SpriteCache.load(context, "sprites/player_idle_front.png") }
    val spriteFrame by rememberSpriteFrame(45, fps = 12)

    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .background(Color(0xFF1A1A2E))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, _, _ ->
                    camOffset += pan
                }
            }
    ) {
        val cx = size.width / 2f + camOffset.x
        val cy = size.height / 3f + camOffset.y

        // ── Рисуем тайлы (сортировка по глубине: col+row возрастает) ─────────
        for (row in 0 until map.rows) {
            for (col in 0 until map.cols) {
                val tile = map.tileAt(col, row)
                if (tile == TileType.VOID) continue

                val iso = isoToScreen(col.toFloat(), row.toFloat())
                val sx = cx + iso.x
                val sy = cy + iso.y

                drawIsoTile(sx, sy, tile)
            }
        }

        // ── Рисуем entity (сортировка по col+row для z-order) ────────────────
        val allEntities = gameState.entities.values.toList() + gameState.player
        val sorted = allEntities.sortedBy { it.col + it.row }

        sorted.forEach { entity ->
            val iso = isoToScreen(entity.col.toFloat(), entity.row.toFloat())
            val sx = cx + iso.x
            val sy = cy + iso.y

            if (entity.id == gameState.player.id && playerFrontSheet != null) {
                // Анимированный спрайт игрока
                drawSprite(
                    sheet = playerFrontSheet,
                    frameIndex = spriteFrame,
                    frameW = 214, frameH = 297,
                    screenX = sx - 107f,  // центрируем по горизонтали
                    screenY = sy - 290f,  // ставим ноги на тайл
                    scale = 0.45f,
                )
            } else {
                // Цветной прямоугольник-placeholder для NPC/врагов
                val color = when {
                    entity.hasFlag("ENEMY") || entity.hasFlag("HOSTILE") -> Color(0xFFCC2222)
                    entity.hasFlag("PEASANT") || entity.hasFlag("VILLAGER") -> Color(0xFFDDCC55)
                    entity.hasFlag("COMPANION") -> Color(0xFF44AAFF)
                    else -> Color(0xFF888888)
                }
                // Тень
                drawOval(Color(0x33000000),
                    topLeft = Offset(sx - 18f, sy - 6f),
                    size = androidx.compose.ui.geometry.Size(36f, 12f))
                // Тело
                drawRect(color,
                    topLeft = Offset(sx - 12f, sy - 48f),
                    size = androidx.compose.ui.geometry.Size(24f, 36f))
                drawCircle(color.copy(alpha = 0.9f), 10f, Offset(sx, sy - 56f))
            }

            // HP бар (только если повреждён)
            if (entity.hp < entity.maxHp && entity.maxHp > 0) {
                val barW = 32f
                val ratio = entity.hp.toFloat() / entity.maxHp.toFloat()
                drawRect(Color(0x88000000), Offset(sx - barW/2 - 1, sy - 68f), androidx.compose.ui.geometry.Size(barW + 2, 6f))
                drawRect(Color(0xFFCC2222), Offset(sx - barW/2, sy - 67f), androidx.compose.ui.geometry.Size(barW, 4f))
                drawRect(Color(0xFF22CC44), Offset(sx - barW/2, sy - 67f), androidx.compose.ui.geometry.Size(barW * ratio, 4f))
            }

            // Имя
            if (!entity.hasFlag("SILENT")) {
                val nameLayout = textMeasurer.measure(
                    entity.name,
                    TextStyle(fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                        color = Color.White, fontWeight = FontWeight.Bold)
                )
                drawText(nameLayout, topLeft = Offset(sx - nameLayout.size.width / 2f, sy - 72f))
            }
        }
    }
}

// ── Рисование одного изометрического тайла ───────────────────────────────────
fun DrawScope.drawIsoTile(cx: Float, cy: Float, tile: TileType) {
    val top    = TILE_COLORS_TOP[tile]    ?: return
    val left   = TILE_COLORS[tile]        ?: return
    val right  = TILE_COLORS_SHADOW[tile] ?: return
    val h      = 8f  // высота "бока" тайла

    // Верхняя грань — ромб
    val topPath = Path().apply {
        moveTo(cx,            cy - TILE_H2)  // верх
        lineTo(cx + TILE_W2,  cy)            // право
        lineTo(cx,            cy + TILE_H2)  // низ
        lineTo(cx - TILE_W2,  cy)            // лево
        close()
    }
    drawPath(topPath, top)
    // Обводка ромба
    drawPath(topPath, Color(0x22000000), style = Stroke(width = 0.8f))

    // Левая грань (юго-запад)
    val leftPath = Path().apply {
        moveTo(cx - TILE_W2, cy)
        lineTo(cx,           cy + TILE_H2)
        lineTo(cx,           cy + TILE_H2 + h)
        lineTo(cx - TILE_W2, cy + h)
        close()
    }
    drawPath(leftPath, left)

    // Правая грань (юго-восток)
    val rightPath = Path().apply {
        moveTo(cx,           cy + TILE_H2)
        lineTo(cx + TILE_W2, cy)
        lineTo(cx + TILE_W2, cy + h)
        lineTo(cx,           cy + TILE_H2 + h)
        close()
    }
    drawPath(rightPath, right)
}

// ── Рисование спрайта из спрайтшита ──────────────────────────────────────────
fun DrawScope.drawSprite(
    sheet: ImageBitmap,
    frameIndex: Int,
    frameW: Int, frameH: Int,
    screenX: Float, screenY: Float,
    scale: Float = 1f,
) {
    val srcLeft = frameIndex * frameW
    if (srcLeft + frameW > sheet.width) return

    val dstW = frameW * scale
    val dstH = frameH * scale

    withTransform({
        translate(screenX, screenY)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        drawImage(
            image = sheet,
            srcOffset = androidx.compose.ui.unit.IntOffset(srcLeft, 0),
            srcSize = androidx.compose.ui.unit.IntSize(frameW, frameH),
            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
            dstSize = androidx.compose.ui.unit.IntSize(frameW, frameH),
            filterQuality = FilterQuality.None,  // пиксельарт — без сглаживания
        )
    }
}
