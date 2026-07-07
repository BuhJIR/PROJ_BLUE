package com.google.ai.edge.gallery.customtasks.tinygarden

import android.content.Context
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

// Кибер-готика: пепельная зелень, фиолетовый пепел земли, холодная сталь,
// неоновая бездна воды. Яркость оставлена цветам-акцентам (цветы, вода, HUD).
val TILE_TOP = mapOf(
    TileType.GRASS to Color(0xFF3B5248), TileType.DIRT  to Color(0xFF4E3B52),
    TileType.STONE to Color(0xFF6E7B8B), TileType.WATER to Color(0xFF0F6272),
    TileType.WOOD  to Color(0xFF4A3040), TileType.VOID  to Color(0xFF161622),
)
val TILE_LEFT = mapOf(
    TileType.GRASS to Color(0xFF2B3D36), TileType.DIRT  to Color(0xFF3A2C40),
    TileType.STONE to Color(0xFF505A66), TileType.WATER to Color(0xFF0A4250),
    TileType.WOOD  to Color(0xFF362232), TileType.VOID  to Color(0xFF10101A),
)
val TILE_RIGHT = mapOf(
    TileType.GRASS to Color(0xFF1C2A24), TileType.DIRT  to Color(0xFF271D2C),
    TileType.STONE to Color(0xFF353D46), TileType.WATER to Color(0xFF062C36),
    TileType.WOOD  to Color(0xFF231521), TileType.VOID  to Color(0xFF0A0A12),
)

// ── Карта ──────────────────────────────────────────────────────────────────────
data class IsoMap(
    val cols: Int = 48,
    val rows: Int = 48,
    val centerCol: Int = 0,
    val centerRow: Int = 0,
    val tiles: Array<Array<LayeredTile>> = Array(48) { Array(48) { LayeredTile(TileType.GRASS) } },
    // Постройки — факт модели мира, не рендер-патч (SPEC §4/§5).
    // Передаётся живой HashMap движка по ссылке: applyStructure видна сразу,
    // без пересборки буфера. tileAt/isWalkable/Pathfinder читают их одинаково.
    val overrides: Map<Pair<Int, Int>, LayeredTileEx> = emptyMap(),
    // Seed мира: теперь часть состояния, а не константа — NEW GAME даёт новый мир
    val seed: Long = WORLD_SEED,
    // Предрасчитанная близость воды (радиус 2) — цветы у воды растут гуще
    val waterNear: Array<BooleanArray>? = null,
) {
    // localCol/localRow → индекс в массиве
    private fun local(worldCol: Int, worldRow: Int): Pair<Int,Int> {
        val lc = worldCol - centerCol + cols / 2
        val lr = worldRow - centerRow + rows / 2
        return lc to lr
    }

    fun tileAt(worldCol: Int, worldRow: Int): LayeredTile {
        overrides[worldCol to worldRow]?.let { return LayeredTile(it.base, it.height) }
        val (lc, lr) = local(worldCol, worldRow)
        if (lc in 0 until cols && lr in 0 until rows) return tiles[lr][lc]
        // За пределами — генерируем на лету из глобального noise (без обрыва)
        return generateTile(worldCol, worldRow, seed)
    }

    /** Полный тайл постройки (с лестницей) на этой клетке, если есть. */
    fun structureAt(worldCol: Int, worldRow: Int): LayeredTileEx? = overrides[worldCol to worldRow]

    /** Есть ли вода в радиусе 2 клеток (в пределах буфера). */
    fun isNearWater(worldCol: Int, worldRow: Int): Boolean {
        val wn = waterNear ?: return false
        val (lc, lr) = local(worldCol, worldRow)
        return lr in 0 until rows && lc in 0 until cols && wn[lr][lc]
    }

    fun isWalkable(col: Int, row: Int): Boolean {
        // Постройки проходимы (кроме воды в них); дикий WOOD — дерево, преграда
        overrides[col to row]?.let { return it.base != TileType.WATER }
        val t = tileAt(col, row).base
        return t != TileType.WATER && t != TileType.WOOD
    }

    companion object {
        // Seed по умолчанию (для миров, созданных до NEW GAME)
        const val WORLD_SEED = 14159265358979323L
    }
}

// ── Hash-noise: непериодичный, детерминированный по seed ──────────────────────
// Прежний генератор строился на sin/cos — тригонометрия периодична, и мир
// обязан был повторяться каждые ~35–100 тайлов. Здесь splitmix64-хэш решётки
// + value noise: тот же seed → тот же мир, но узор не зацикливается никогда.

private const val GOLDEN = -0x61c8864680b583ebL  // 0x9E3779B97F4A7C15
private const val MIX_1  = -0x40a7b892e31b1a47L  // 0xBF58476D1CE4E5B9
private const val MIX_2  = -0x6b2fb644ecceee15L  // 0x94D049BB133111EB
private const val Y_STEP = -0x3d4d51c2d82b14b1L  // 0xC2B2AE3D27D4EB4F
private const val CH_STEP = 0x632BE59BD9B4E019L

private fun mix64(z0: Long): Long {
    var z = z0 + GOLDEN
    z = (z xor (z ushr 30)) * MIX_1
    z = (z xor (z ushr 27)) * MIX_2
    return z xor (z ushr 31)
}

/** Значение решётки в [0,1] для целой точки (x,y); channel разводит слои шума. */
private fun lattice(x: Int, y: Int, seed: Long, channel: Long): Float {
    val h = mix64(seed + channel * CH_STEP + x * GOLDEN + y * Y_STEP)
    return ((h ushr 40) and 0xFFFFFF).toFloat() / 0xFFFFFF
}

/** Value noise: билинейная интерполяция решётки со smoothstep. */
private fun valueNoise(x: Float, y: Float, seed: Long, channel: Long): Float {
    val x0 = kotlin.math.floor(x).toInt()
    val y0 = kotlin.math.floor(y).toInt()
    val tx = x - x0
    val ty = y - y0
    val sx = tx * tx * (3f - 2f * tx)
    val sy = ty * ty * (3f - 2f * ty)
    val v00 = lattice(x0, y0, seed, channel)
    val v10 = lattice(x0 + 1, y0, seed, channel)
    val v01 = lattice(x0, y0 + 1, seed, channel)
    val v11 = lattice(x0 + 1, y0 + 1, seed, channel)
    val top = v00 + (v10 - v00) * sx
    val bot = v01 + (v11 - v01) * sx
    return top + (bot - top) * sy
}

/** 3 октавы value noise, результат в [0,1]. */
private fun fbm(x: Float, y: Float, seed: Long, channel: Long): Float =
    valueNoise(x, y, seed, channel) * 0.5f +
    valueNoise(x * 2.1f, y * 2.1f, seed, channel + 7) * 0.3f +
    valueNoise(x * 4.3f, y * 4.3f, seed, channel + 13) * 0.2f

// ── Процедурная генерация тайла по мировым координатам (детерминированно) ─────
fun generateTile(worldCol: Int, worldRow: Int, seed: Long): LayeredTile {
    val terrain = fbm(worldCol * 0.09f, worldRow * 0.09f, seed, channel = 1)   // рельеф
    val heightN = fbm(worldCol * 0.045f, worldRow * 0.045f, seed, channel = 2) // высоты
    val scatter = lattice(worldCol, worldRow, seed, channel = 3)               // точечный разброс

    // Пороги подобраны симуляцией: ~64% трава, 20% земля, 12% камень,
    // ~2% вода (низкая частота собирает её в озёра), ~2% лес
    val base = when {
        terrain > 0.75f  -> TileType.WATER
        terrain > 0.65f  -> TileType.STONE
        terrain > 0.56f  -> TileType.DIRT
        scatter > 0.965f -> TileType.WOOD
        else             -> TileType.GRASS
    }
    val height = if (base == TileType.WATER) 0
                 else (heightN * 5f).toInt().coerceIn(0, 4)
    return LayeredTile(base, height)
}

// ── Генерация буфера карты вокруг центра ──────────────────────────────────────
fun generateMapAround(
    centerCol: Int, centerRow: Int,
    cols: Int = 48, rows: Int = 48,
    overrides: Map<Pair<Int, Int>, LayeredTileEx> = emptyMap(),
    seed: Long = IsoMap.WORLD_SEED,
): IsoMap {
    val tiles = Array(rows) { r ->
        Array(cols) { c ->
            val wc = c - cols / 2 + centerCol
            val wr = r - rows / 2 + centerRow
            generateTile(wc, wr, seed)
        }
    }
    // Близость воды: один проход при сборке буфера, дальше — O(1) на клетку
    val waterNear = Array(rows) { BooleanArray(cols) }
    for (r in 0 until rows) for (c in 0 until cols) {
        if (tiles[r][c].base == TileType.WATER) {
            for (dr in -2..2) for (dc in -2..2) {
                val rr = r + dr
                val cc = c + dc
                if (rr in 0 until rows && cc in 0 until cols) waterNear[rr][cc] = true
            }
        }
    }
    return IsoMap(cols, rows, centerCol, centerRow, tiles, overrides, seed, waterNear)
}

// ── Направления ───────────────────────────────────────────────────────────────
enum class Direction { SOUTH, NORTH, WEST, EAST }

/** Базовое имя листа для персонажа — ключ в sprites_meta.json. */
fun spriteBase(charName: String): String = when {
    // Все враждебные/нежить-имена → скелет-маг (enemy_* листы были битые, удалены)
    charName.contains("skeleton", ignoreCase = true) ||
    charName.contains("skull",    ignoreCase = true) ||
    charName.contains("mage",     ignoreCase = true) ||
    charName.contains("imp",      ignoreCase = true) ||
    charName.contains("red",      ignoreCase = true) -> "sprites/skeleton_mage"
    charName.contains("ice",      ignoreCase = true) -> "sprites/hero_ice"
    charName.contains("sister_4", ignoreCase = true) -> "sprites/sister_4"
    charName.contains("sister_5", ignoreCase = true) -> "sprites/sister_5"
    charName.contains("sister_6", ignoreCase = true) -> "sprites/sister_6"
    charName.contains("sister_3", ignoreCase = true) ||
    charName.equals("Hero",       ignoreCase = true) -> "sprites/sister_3"
    else -> "sprites/hero_white"
}

fun spritePath(charName: String, dir: Direction): String =
    "${spriteBase(charName)}_${dir.name.lowercase()}.png"

// ── Анимация ───────────────────────────────────────────────────────────────────
/**
 * Общие часы анимации, квантованные до 12 Гц: у листов разный fps (2 у врагов,
 * 12 у Сестёр), индекс кадра каждый лист считает сам от одного времени.
 */
@Composable
fun rememberSpriteClock(): State<Long> {
    val ms = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { t ->
                val q = t - t % 83L  // ~12 Гц — не перерисовываем каждый vsync
                if (q != ms.longValue) ms.longValue = q
            }
        }
    }
    return ms
}

// ── Главный рендерер ──────────────────────────────────────────────────────────
@Composable
fun IsoMapRenderer(
    gameState: GameState,
    engine: GameEngine? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var camOffset by remember { mutableStateOf(Offset(0f, -180f)) }
    val spriteClock by rememberSpriteClock()

    // Карта регенерируется когда игрок близко к краю — ОДНИМ seed'ом.
    // engine.worldMap — та же карта, которой пользуются Pathfinder и BehaviourExecutor.
    var liveMap by remember {
        mutableStateOf(engine?.worldMap ?: generateMapAround(0, 0))
    }
    val player = gameState.player
    LaunchedEffect(player.col, player.row, gameState.worldSeed) {
        val halfC = liveMap.cols / 2
        val halfR = liveMap.rows / 2
        val dc = player.col - liveMap.centerCol
        val dr = player.row - liveMap.centerRow
        // Рестартуем буфер если игрок ближе 8 тайлов к краю или сменился seed (NEW GAME)
        if (abs(dc) > halfC - 8 || abs(dr) > halfR - 8 || gameState.worldSeed != liveMap.seed) {
            liveMap = generateMapAround(
                player.col, player.row,
                overrides = engine?.structureOverrides ?: emptyMap(),
                seed = gameState.worldSeed,
            )
            engine?.updateWorldMap(liveMap)
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
                // tileAt смотрит сквозь overrides — постройки видны и здесь,
                // и в Pathfinder/isWalkable, одним и тем же путём (SPEC §5)
                val lt = liveMap.tileAt(wc, wr)
                val iso = isoToScreen(wc.toFloat(), wr.toFloat(), lt.height.toFloat())
                val sx = cx + iso.x
                val sy = cy + iso.y

                // Не рисуем то что точно за экраном
                if (sx < -TILE_W || sx > size.width + TILE_W) continue
                if (sy < -TILE_H * 6 || sy > size.height + TILE_H * 6) continue

                // Дикий WOOD — дерево на траве; WOOD в постройке — материал
                val wildTile = liveMap.structureAt(wc, wr) == null
                if (lt.base == TileType.WOOD && wildTile) {
                    drawIsoTile(sx, sy, TileType.GRASS, lt.height)
                    drawTree(sx, sy, seedFor(wc, wr))
                } else {
                    drawIsoTile(sx, sy, lt.base, lt.height)
                }
                // Растительность на диких клетках — кроме камня и воды
                if (wildTile && (lt.base == TileType.GRASS || lt.base == TileType.DIRT)) {
                    drawVegetation(
                        sx, sy, seedFor(wc, wr), lt.base,
                        turn = gameState.turn,
                        nearWater = liveMap.isNearWater(wc, wr),
                    )
                }

                // Ступень — диагональный переход внутри клетки
                liveMap.structureAt(wc, wr)?.stair?.let { stair ->
                    drawStairOverlay(sx, sy, stair, lt.base)
                }

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

            val dirStr = entity.memoryString("direction") ?: "SOUTH"
            val dir    = runCatching { Direction.valueOf(dirStr) }.getOrDefault(Direction.SOUTH)
            val base   = spriteBase(if (isPlayer) "sister_3" else entity.name)
            val key    = "${base}_${dir.name.lowercase()}.png"
            val sheet  = SpriteSheetCache.sheet(context, key, base)

            if (sheet != null) {
                val frameH  = sheet.bitmap.height
                // Масштаб от целевой высоты: враги (120px) остаются 1:1,
                // Сёстры (309–332px) ужимаются одинаково для игрока и NPC
                val targetH = if (isPlayer) 150f else 120f
                val scale   = targetH / frameH
                val fi      = sheet.frameAt(spriteClock)
                // Позиция центрируется по номинальной ширине кадра —
                // не плавает при смене кадров неравной ширины
                val screenX = sx - sheet.nominalW * scale / 2f
                val screenY = sy - frameH * scale
                drawSpriteFrame(sheet, fi, screenX, screenY, scale)
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

// ── drawSpriteFrame — кадр по точным границам из SpriteSheet ──────────────────
fun DrawScope.drawSpriteFrame(
    sheet: SpriteSheet,
    frameIndex: Int,
    screenX: Float, screenY: Float,
    scale: Float = 1f,
) {
    val srcLeft = sheet.srcLeft(frameIndex)
    val srcW = sheet.srcWidth(frameIndex)
    val srcH = sheet.bitmap.height
    if (srcW <= 0 || srcLeft + srcW > sheet.bitmap.width) return
    withTransform({
        translate(screenX, screenY)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        drawImage(
            image         = sheet.bitmap,
            srcOffset     = IntOffset(srcLeft, 0),
            srcSize       = IntSize(srcW, srcH),
            dstOffset     = IntOffset.Zero,
            dstSize       = IntSize(srcW, srcH),
            filterQuality = FilterQuality.None,
        )
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

/** Детерминированная «личность» дерева на клетке — размер/оттенок не мигают. */
private fun seedFor(col: Int, row: Int): Int {
    var h = col * 374761393 + row * 668265263
    h = (h xor (h shr 13)) * 1274126177
    return h xor (h shr 16)
}

/**
 * Дерево: ствол + двухъярусная ромбовидная крона в духе PS1.
 * Дикие WOOD-тайлы теперь читаются как лес, а не как коричневый паркет.
 * Каждое ~восьмое дерево — неоново-фиолетовое: кибер-готика.
 */
fun DrawScope.drawTree(cx: Float, cy: Float, seed: Int) {
    val s = 0.85f + (seed and 0x7) * 0.06f            // 0.85..1.27 — разброс размера
    val violet = (seed and 0x38) == 0                  // редкий фиолетовый экземпляр
    val trunk = Color(0xFF2E2430)
    val leafDark = if (violet) Color(0xFF3A1E5C) else Color(0xFF14453A)
    val leafMain = if (violet) Color(0xFF6A34A8)
                   else if (seed and 0x10 == 0) Color(0xFF1D5C4A) else Color(0xFF256852)

    // Ствол
    drawRect(trunk, Offset(cx - 4f * s, cy - 30f * s), Size(8f * s, 30f * s))
    // Нижний ярус кроны
    drawPath(Path().apply {
        moveTo(cx, cy - 78f * s)
        lineTo(cx + 30f * s, cy - 40f * s)
        lineTo(cx, cy - 22f * s)
        lineTo(cx - 30f * s, cy - 40f * s)
        close()
    }, leafDark)
    // Верхний ярус кроны
    drawPath(Path().apply {
        moveTo(cx, cy - 96f * s)
        lineTo(cx + 20f * s, cy - 62f * s)
        lineTo(cx, cy - 44f * s)
        lineTo(cx - 20f * s, cy - 62f * s)
        close()
    }, leafMain)
}

/**
 * Растительность: пучки/кусты статичны; цветы растут пачками крупных кружков,
 * не по центру клетки, живут циклом (растут → цветут → увядают → исчезают)
 * от хода мира и гуще у воды. Всё детерминированно от координат — не мигает.
 */
fun DrawScope.drawVegetation(
    cx: Float, cy: Float, seed: Int, base: TileType,
    turn: Int, nearWater: Boolean,
) {
    var bits = seed
    // Пучки и кусты — как раньше, статичный подлесок
    val maxN = if (base == TileType.DIRT) 1 else 2
    for (i in 0 until maxN) {
        val roll = bits and 0xF; bits = bits ushr 4
        val show = if (base == TileType.DIRT) roll < 2 else roll < 6
        if (!show) continue
        val ox = ((bits and 0x1F) - 15).toFloat(); bits = bits ushr 5
        val oy = ((bits and 0xF) - 7) * 0.8f; bits = bits ushr 4
        val px = cx + ox * 0.9f
        val py = cy + oy
        val kind = bits and 0x3; bits = bits ushr 2
        if (base == TileType.DIRT || kind < 3) drawTuft(px, py, dry = base == TileType.DIRT)
        else drawBush(px, py)
    }

    // Цветочная пачка на клетке: у воды — чаще и крупнее
    if (base != TileType.GRASS) return
    val patchRoll = bits and 0xF; bits = bits ushr 4
    val patchChance = if (nearWater) 6 else 2
    if (patchRoll >= patchChance) return

    // Жизненный цикл пачки: фаза сдвинута seed'ом, тикает ходом мира
    val phase = ((bits and 0x7) + turn) % 10; bits = bits ushr 3
    val life = when (phase) {
        0 -> 0.45f          // проклюнулись
        1 -> 0.75f          // растут
        in 2..5 -> 1f       // цветение
        6 -> 0.8f           // начали никнуть
        7 -> 0.5f           // увяли
        else -> return      // пусто — клетка отдыхает
    }
    val wilting = phase >= 6

    // Центр пачки смещён от центра клетки
    val pcx = cx + (((bits and 0x1F) - 15).toFloat()) * 0.8f; bits = bits ushr 5
    val pcy = cy + (((bits and 0x7) - 3).toFloat()) * 1.2f; bits = bits ushr 3
    val heads = 3 + (bits and 0x3) + (if (nearWater) 2 else 0); bits = bits ushr 2

    for (i in 0 until heads) {
        val hx = pcx + (((bits and 0xF) - 7).toFloat()) * 1.4f; bits = bits ushr 4
        val hy = pcy + (((bits and 0x7) - 3).toFloat()) * 1.1f; bits = bits ushr 3
        var head = when (bits and 0x3) {
            0    -> Color(0xFF00E5FF)  // неоновый циан
            1    -> Color(0xFFE040FB)  // фуксия
            2    -> Color(0xFF9C7BFF)  // фиолет
            else -> Color(0xFFFF5370)  // алый
        }
        bits = bits ushr 2
        if (wilting) head = head.copy(alpha = 0.55f)
        val r = (3.5f + (bits and 0x3) * 0.8f) * life; bits = bits ushr 2
        drawCircle(head.copy(alpha = head.alpha * 0.35f), r * 1.8f, Offset(hx, hy))  // свечение
        drawCircle(head, r, Offset(hx, hy))
    }
}

private fun DrawScope.drawTuft(x: Float, y: Float, dry: Boolean) {
    val c = if (dry) Color(0xFF5C5340) else Color(0xFF2F5D46)
    drawLine(c, Offset(x, y), Offset(x - 3f, y - 7f), strokeWidth = 1.8f)
    drawLine(c, Offset(x, y), Offset(x, y - 9f), strokeWidth = 1.8f)
    drawLine(c, Offset(x, y), Offset(x + 3f, y - 6f), strokeWidth = 1.8f)
}

private fun DrawScope.drawBush(x: Float, y: Float) {
    drawCircle(Color(0xFF1E4034), 6f, Offset(x, y - 4f))
    drawCircle(Color(0xFF2A5644), 4f, Offset(x + 3f, y - 7f))
}

fun DrawScope.drawStairOverlay(cx: Float, cy: Float, stair: StairInfo, material: TileType) {
    val stepCount = 4
    val stepH = TILE_LIFT / stepCount
    val col = TILE_LEFT[material] ?: Color.Gray
    for (i in 0 until stepCount) {
        val t = i.toFloat() / stepCount
        val stepY = cy - t * (TILE_H2 * 0.6f)
        val stepPath = Path().apply {
            moveTo(cx - TILE_W2 * (1 - t) * 0.5f, stepY)
            lineTo(cx + TILE_W2 * (1 - t) * 0.5f, stepY)
            lineTo(cx + TILE_W2 * (1 - t) * 0.5f, stepY + stepH)
            lineTo(cx - TILE_W2 * (1 - t) * 0.5f, stepY + stepH)
            close()
        }
        drawPath(stepPath, col.copy(alpha = 0.7f + 0.3f * t))
    }
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
