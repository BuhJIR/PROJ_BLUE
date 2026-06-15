package com.google.ai.edge.gallery.customtasks.tinygarden

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.awaitPointerEvent
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
// ── Направления движения ────────────────────────────────────────────────────
enum class Direction { SOUTH, NORTH, WEST, EAST }

/** По delta движения определяем направление в изометрии. */
fun directionFromDelta(dx: Int, dy: Int): Direction = when {
    dx > 0 && dy == 0 -> Direction.EAST
    dx < 0 && dy == 0 -> Direction.WEST
    dy < 0            -> Direction.NORTH
    else              -> Direction.SOUTH
}

/** Путь к спрайту по имени персонажа и направлению. */
fun spritePath(charName: String, dir: Direction): String {
    val base = when {
        charName.contains("skull", ignoreCase = true) -> "sprites/enemy_skull"
        charName.contains("red",   ignoreCase = true) -> "sprites/enemy_red"
        charName.contains("ice",   ignoreCase = true) -> "sprites/hero_ice"
        else -> "sprites/hero_white"
    }
    return "${base}_${dir.name.lowercase()}.png"
}

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
    engine: GameEngine? = null,
    map: IsoMap = remember { generateTestMap() },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Камера — offset в экранных координатах, двигается тачем
    var camOffset by remember { mutableStateOf(Offset(0f, -200f)) }

    // Загружаем спрайты
    val spriteFrame by rememberSpriteFrame(45, fps = 12)

    val textMeasurer = rememberTextMeasurer()

    val onTileClick: (Int, Int) -> Unit = { col, row ->
        engine?.selectTile(col, row, map)
    }

    Canvas(
        modifier = modifier
            .background(Color(0xFF1A1A2E))
            .pointerInput(Unit) {
                val TAP_SLOP = 12f
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startPos = down.position
                        var totalDrag = 0f
                        var lastPos = startPos
                        var isDragging = false
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            val delta = change.position - lastPos
                            val dist = kotlin.math.sqrt(delta.x * delta.x + delta.y * delta.y)
                            totalDrag += dist
                            if (totalDrag > TAP_SLOP) isDragging = true
                            if (isDragging) { camOffset += delta; change.consume() }
                            lastPos = change.position
                        } while (event.changes.any { it.pressed })
                        if (!isDragging) {
                            val cx2 = size.width / 2f + camOffset.x
                            val cy2 = size.height / 3f + camOffset.y
                            val sx = startPos.x - cx2
                            val sy = startPos.y - cy2
                            val (col, row) = screenToIso(sx, sy)
                            onTileClick(col, row)
                        }
                    }
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

        // ── Подсветка пути ───────────────────────────────────────────────────
        val pathSet = engine?.currentPath?.map { it.col to it.row }?.toSet() ?: emptySet()
        val selected = engine?.selectedTile

        pathSet.forEach { (col, row) ->
            val iso = isoToScreen(col.toFloat(), row.toFloat())
            val sx = cx + iso.x
            val sy = cy + iso.y
            // Подсвечиваем верхнюю грань тайла голубым
            val pathPath = Path().apply {
                moveTo(sx, sy - TILE_H2)
                lineTo(sx + TILE_W2, sy)
                lineTo(sx, sy + TILE_H2)
                lineTo(sx - TILE_W2, sy)
                close()
            }
            drawPath(pathPath, Color(0x5500CFFF))
            drawPath(pathPath, Color(0xAA00CFFF), style = Stroke(width = 1.5f))
        }

        // Выбранный тайл — яркий контур
        selected?.let { (col, row) ->
            val iso = isoToScreen(col.toFloat(), row.toFloat())
            val sx = cx + iso.x
            val sy = cy + iso.y
            val selPath = Path().apply {
                moveTo(sx, sy - TILE_H2)
                lineTo(sx + TILE_W2, sy)
                lineTo(sx, sy + TILE_H2)
                lineTo(sx - TILE_W2, sy)
                close()
            }
            drawPath(selPath, Color(0x7700FFAA))
            drawPath(selPath, Color(0xFFFFFFFF), style = Stroke(width = 2f))
        }

        // ── Рисуем entity (сортировка по col+row для z-order) ────────────────
        val allEntities = gameState.entities.values.toList() + gameState.player
        val sorted = allEntities.sortedBy { it.col + it.row }

        sorted.forEach { entity ->
            val iso = isoToScreen(entity.col.toFloat(), entity.row.toFloat())
            val sx = cx + iso.x
            val sy = cy + iso.y

            // Выбираем спрайт по направлению
            val dirStr = entity.memory["direction"] as? String ?: "SOUTH"
            val dir = try { Direction.valueOf(dirStr) } catch (e: Exception) { Direction.SOUTH }
            val spriteKey = spritePath(
                if (entity.id == gameState.player.id) "sister_3" else entity.name, dir
            )
            val sheet = SpriteCache.load(context, spriteKey)
            val isPlayer = entity.id == gameState.player.id

            if (sheet != null) {
                val frameCount = when {
                    isPlayer -> 45
                    else -> 2
                }
                val frameW = sheet.width / frameCount
                val frameH = sheet.height
                val scale = if (isPlayer) 0.45f else 1f
                drawSprite(
                    sheet = sheet,
                    frameIndex = if (isPlayer) spriteFrame else (spriteFrame % 2),
                    frameW = frameW, frameH = frameH,
                    screenX = sx - frameW * scale / 2f,
                    screenY = sy - frameH * scale * 0.95f,
                    scale = scale,
                )
            } else {
                // Fallback — цветной прямоугольник
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

            // Секционный HP bar — 6 секций, maxHp/6 HP на секцию (до 12 секций макс)
            if (entity.maxHp > 0) {
                drawSegmentedHpBar(
                    cx = sx,
                    cy = sy - 62f,
                    hp = entity.hp,
                    maxHp = entity.maxHp,
                )
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
            srcOffset = IntOffset(srcLeft, 0),
            srcSize = IntSize(frameW, frameH),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(frameW, frameH),
            filterQuality = FilterQuality.None,  // пиксельарт — без сглаживания
        )
    }
}

// ── Секционный HP bar ─────────────────────────────────────────────────────────
// segments = maxHp / 6, но не больше 12 секций
// Каждая секция — маленький прямоугольник с зазором
fun DrawScope.drawSegmentedHpBar(cx: Float, cy: Float, hp: Int, maxHp: Int) {
    val segments = (maxHp / 6).coerceIn(1, 12)
    val hpPerSeg = maxHp.toFloat() / segments
    val segW = 7f
    val segH = 5f
    val gap = 1.5f
    val totalW = segments * segW + (segments - 1) * gap
    val startX = cx - totalW / 2f

    // Фон всей полоски
    drawRect(
        Color(0xAA000000),
        topLeft = Offset(startX - 2f, cy - 2f),
        size = androidx.compose.ui.geometry.Size(totalW + 4f, segH + 4f)
    )

    for (i in 0 until segments) {
        val segStartHp = i * hpPerSeg
        val segEndHp = (i + 1) * hpPerSeg
        val fill = when {
            hp >= segEndHp -> 1f                          // полная секция
            hp <= segStartHp -> 0f                        // пустая
            else -> (hp - segStartHp) / hpPerSeg         // частичная
        }

        val x = startX + i * (segW + gap)

        // Фон секции (пустая)
        drawRect(
            Color(0xFF333333),
            topLeft = Offset(x, cy),
            size = androidx.compose.ui.geometry.Size(segW, segH)
        )

        // Заполненная часть
        if (fill > 0f) {
            val color = when {
                hp.toFloat() / maxHp > 0.5f -> Color(0xFF44DD44)   // зелёный
                hp.toFloat() / maxHp > 0.25f -> Color(0xFFDDCC00)  // жёлтый
                else -> Color(0xFFDD2222)                           // красный
            }
            drawRect(
                color,
                topLeft = Offset(x, cy),
                size = androidx.compose.ui.geometry.Size(segW * fill, segH)
            )
            // Блик сверху
            drawRect(
                Color(0x55FFFFFF),
                topLeft = Offset(x, cy),
                size = androidx.compose.ui.geometry.Size(segW * fill, 1.5f)
            )
        }

        // Разделитель между секциями
        if (i < segments - 1) {
            drawRect(
                Color(0xFF111111),
                topLeft = Offset(x + segW, cy),
                size = androidx.compose.ui.geometry.Size(gap, segH)
            )
        }
    }
}
