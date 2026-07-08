package com.google.ai.edge.gallery.customtasks.tinygarden

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Настоящий (лёгкий) 3D-кубик, падающий в плоскость мира (SPEC §15 — «кидаем
 * кубик под ноги»). 8 вершин, 6 граней, гравитация, отскоки с затуханием,
 * кувыркание. Приземляется на грань, снап к ровной позе, читается верхняя
 * грань → DiceCaster резолвит выпавшую Сестру. Никаких обёрток: результат —
 * то, что реально выпало у физики.
 */

// ── Мини-математика 3×3 (row-major) ─────────────────────────────────────────

private data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
}

private fun mat(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float, g: Float, h: Float, i: Float) =
    floatArrayOf(a, b, c, d, e, f, g, h, i)

private fun identity() = mat(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

private fun mul(a: FloatArray, b: FloatArray): FloatArray {
    val r = FloatArray(9)
    for (row in 0..2) for (col in 0..2) {
        var s = 0f
        for (k in 0..2) s += a[row * 3 + k] * b[k * 3 + col]
        r[row * 3 + col] = s
    }
    return r
}

private fun apply(m: FloatArray, v: V3) = V3(
    m[0] * v.x + m[1] * v.y + m[2] * v.z,
    m[3] * v.x + m[4] * v.y + m[5] * v.z,
    m[6] * v.x + m[7] * v.y + m[8] * v.z,
)

/** Родригес: матрица поворота на angle вокруг единичной оси. */
private fun rodrigues(ax: Float, ay: Float, az: Float, angle: Float): FloatArray {
    val len = sqrt(ax * ax + ay * ay + az * az)
    if (len < 1e-6f) return identity()
    val x = ax / len; val y = ay / len; val z = az / len
    val c = cos(angle); val s = sin(angle); val t = 1f - c
    return mat(
        t * x * x + c,     t * x * y - s * z, t * x * z + s * y,
        t * x * y + s * z, t * y * y + c,     t * y * z - s * x,
        t * x * z - s * y, t * y * z + s * x, t * z * z + c,
    )
}

// ── Геометрия кубика ────────────────────────────────────────────────────────

private val VERTS = arrayOf(
    V3(-1f, -1f, -1f), V3(1f, -1f, -1f), V3(1f, 1f, -1f), V3(-1f, 1f, -1f),
    V3(-1f, -1f, 1f), V3(1f, -1f, 1f), V3(1f, 1f, 1f), V3(-1f, 1f, 1f),
)

/** Грань: 4 вершины (по кругу), нормаль, значение (противоположные = 7). */
private class Face(val idx: IntArray, val normal: V3, val value: Int)

private val FACES = arrayOf(
    Face(intArrayOf(1, 2, 6, 5), V3(1f, 0f, 0f), 1),
    Face(intArrayOf(0, 4, 7, 3), V3(-1f, 0f, 0f), 6),
    Face(intArrayOf(3, 7, 6, 2), V3(0f, 1f, 0f), 2),   // +Y = верх в покое
    Face(intArrayOf(0, 1, 5, 4), V3(0f, -1f, 0f), 5),
    Face(intArrayOf(4, 5, 6, 7), V3(0f, 0f, 1f), 3),
    Face(intArrayOf(0, 3, 2, 1), V3(0f, 0f, -1f), 4),
)

// Пипсы в единичном квадрате грани [0..1]²
private val PIPS = mapOf(
    1 to listOf(.5f to .5f),
    2 to listOf(.28f to .28f, .72f to .72f),
    3 to listOf(.26f to .26f, .5f to .5f, .74f to .74f),
    4 to listOf(.28f to .28f, .72f to .28f, .28f to .72f, .72f to .72f),
    5 to listOf(.26f to .26f, .74f to .26f, .5f to .5f, .26f to .74f, .74f to .74f),
    6 to listOf(.28f to .22f, .28f to .5f, .28f to .78f, .72f to .22f, .72f to .5f, .72f to .78f),
)

// Камера-изометрия: грань видна если нормаль смотрит на камеру
private val TO_CAM = V3(0.577f, 0.577f, 0.577f)
private const val COS30 = 0.866f

// ── Тело кубика ─────────────────────────────────────────────────────────────

private class DieBody(dropX: Float, groundY: Float) {
    // Экранная позиция «пятна» на земле + высота над ним (px)
    var px = dropX
    var gy = groundY
    var h = 300f
    var vh = -40f                       // вертикальная скорость (вверх +)
    var vx = Random.nextFloat() * 200f - 100f
    var rot = randomRot()
    // Угловая скорость (рад/с) вокруг трёх осей
    var wx = Random.nextFloat() * 18f - 9f
    var wy = Random.nextFloat() * 18f - 9f
    var wz = Random.nextFloat() * 18f - 9f
    var restStable = 0f
    var settled = false
        private set
    var value = 0
        private set

    private companion object {
        const val G = 2600f
        const val RESTITUTION = 0.5f
        fun randomRot(): FloatArray {
            var m = rodrigues(1f, 0f, 0f, Random.nextFloat() * 6.28f)
            m = mul(rodrigues(0f, 1f, 0f, Random.nextFloat() * 6.28f), m)
            return mul(rodrigues(0f, 0f, 1f, Random.nextFloat() * 6.28f), m)
        }
    }

    fun step(dt: Float) {
        if (settled) return
        // Вертикаль: гравитация + отскок от плоскости (h = 0)
        vh -= G * dt
        h += vh * dt
        px += vx * dt
        if (h <= 0f) {
            h = 0f
            if (abs(vh) > 55f) {
                vh = -vh * RESTITUTION
                vx *= 0.7f
                wx *= 0.55f; wy *= 0.55f; wz *= 0.55f   // отскок гасит вращение
            } else {
                vh = 0f
                vx *= 0.6f
                wx *= 0.85f; wy *= 0.85f; wz *= 0.85f
            }
        }
        // Кувыркание
        val spin = sqrt(wx * wx + wy * wy + wz * wz)
        if (spin > 1e-4f) rot = mul(rodrigues(wx, wy, wz, spin * dt), rot)

        // Условие покоя: на земле, почти без прыжка и вращения
        if (h == 0f && abs(vh) < 10f && spin < 0.6f) {
            restStable += dt
            if (restStable > 0.25f) settle()
        } else {
            restStable = 0f
        }
    }

    private fun settle() {
        rot = snap(rot)
        value = upValue(rot)
        settled = true
    }

    /** Значение грани, чья повёрнутая нормаль ближе всего к мировому «вверх». */
    private fun upValue(m: FloatArray): Int {
        var best = FACES[0]; var bestDot = -2f
        for (f in FACES) {
            val d = apply(m, f.normal).dot(V3(0f, 1f, 0f))
            if (d > bestDot) { bestDot = d; best = f }
        }
        return best.value
    }

    /** Снап матрицы к ближайшей осе-выровненной позе — кубик лежит ровно. */
    private fun snap(m: FloatArray): FloatArray {
        val cx = axisSnap(V3(m[0], m[3], m[6]))
        var cy = axisSnap(V3(m[1], m[4], m[7]))
        if (sameAxis(cx, cy)) cy = fallbackAxis(cx)
        val cz = cross(cx, cy)
        return mat(cx.x, cy.x, cz.x, cx.y, cy.y, cz.y, cx.z, cy.z, cz.z)
    }

    private fun axisSnap(v: V3): V3 {
        val ax = abs(v.x); val ay = abs(v.y); val az = abs(v.z)
        return when {
            ax >= ay && ax >= az -> V3(if (v.x >= 0) 1f else -1f, 0f, 0f)
            ay >= az             -> V3(0f, if (v.y >= 0) 1f else -1f, 0f)
            else                 -> V3(0f, 0f, if (v.z >= 0) 1f else -1f)
        }
    }

    private fun sameAxis(a: V3, b: V3) =
        (a.x != 0f && b.x != 0f) || (a.y != 0f && b.y != 0f) || (a.z != 0f && b.z != 0f)

    private fun fallbackAxis(a: V3): V3 =
        if (a.x == 0f) V3(1f, 0f, 0f) else V3(0f, 1f, 0f)

    private fun cross(a: V3, b: V3) = V3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x,
    )
}

// ── Проекция и отрисовка ────────────────────────────────────────────────────

private fun project(v: V3, cx: Float, cy: Float, h: Float, scale: Float): Offset = Offset(
    cx + (v.x - v.z) * COS30 * scale,
    cy - h + ((v.x + v.z) * 0.5f - v.y) * scale,
)

private fun DrawScope.drawDie(body: DieBody, scale: Float) {
    val cx = body.px
    val cy = body.gy
    // Тень: меньше и бледнее, когда кубик высоко
    val shadowK = 1f / (1f + body.h / 160f)
    drawOval(
        Color.Black.copy(alpha = 0.35f * shadowK),
        topLeft = Offset(cx - scale * 1.5f * shadowK, cy - scale * 0.6f * shadowK),
        size = Size(scale * 3f * shadowK, scale * 1.2f * shadowK),
    )

    val worldNormals = FACES.map { apply(body.rot, it.normal) }
    val upIdx = worldNormals.indices.maxByOrNull { worldNormals[it].dot(V3(0f, 1f, 0f)) } ?: 0

    FACES.forEachIndexed { i, face ->
        val n = worldNormals[i]
        if (n.dot(TO_CAM) <= 0.02f) return@forEachIndexed   // грань от нас — пропускаем

        val p = face.idx.map { project(apply(body.rot, VERTS[it]), cx, cy, body.h, scale) }
        val path = Path().apply {
            moveTo(p[0].x, p[0].y)
            lineTo(p[1].x, p[1].y)
            lineTo(p[2].x, p[2].y)
            lineTo(p[3].x, p[3].y)
            close()
        }
        // Верхняя грань ярче — кибер-готический неон
        val fill = if (i == upIdx) Color(0xFF5A2E8C) else Color(0xFF32184F)
        drawPath(path, fill)
        drawPath(path, Color(0xFF9C7BFF), style = Stroke(2f))

        // Пипсы: билинейно по 4 углам грани
        PIPS[face.value]?.forEach { (u, v) ->
            val top = lerp(p[0], p[1], u)
            val bot = lerp(p[3], p[2], u)
            val c = lerp(top, bot, v)
            drawCircle(Color(0xFF00E5FF), scale * 0.11f, c)
        }
    }
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

// ── Compose-оверлей ─────────────────────────────────────────────────────────

/**
 * Оверлей броска: роняет кубик в центр вида, крутит физику по кадрам, на
 * приземлении резолвит грань через DiceCaster и вызывает onSettled.
 */
@Composable
fun DiceRollOverlay(
    engine: GameEngine,
    modifier: Modifier = Modifier,
    onSettled: () -> Unit,
) {
    var tick by remember { mutableIntStateOf(0) }
    val body = remember { mutableStateOf<DieBody?>(null) }
    var sizePx by remember { mutableStateOf(Size.Zero) }

    LaunchedEffect(sizePx) {
        if (sizePx == Size.Zero) return@LaunchedEffect
        val b = DieBody(dropX = sizePx.width * 0.5f, groundY = sizePx.height * 0.55f)
        body.value = b
        var last = 0L
        var done = false
        while (!done) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.033f)
                    b.step(dt)
                    tick++
                }
                last = now
            }
            if (b.settled) done = true
        }
        // Читаем выпавшее и резолвим — без повторного броска
        val p = engine.currentState().player
        val die = Sisters.defaultDie()
        DiceCaster.applyRoll(die, b.value, p.col, p.row, engine)
        delay(650)   // дать игроку увидеть результат
        onSettled()
    }

    Box(modifier = modifier.background(Color(0x66000010))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            sizePx = size
            @Suppress("UNUSED_EXPRESSION") tick // подписка на кадр — форсит перерисовку
            body.value?.let { drawDie(it, scale = size.minDimension * 0.055f) }
        }
    }
}
