package com.google.ai.edge.gallery.customtasks.tinygarden

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Живой 3D-кубик (SPEC §15 — «кидаем кубик под ноги»). Одна сущность: кубик
 * всегда висит в углу карты и медленно крутится; свайпом по нему бросаем.
 * Он вылетает ДУГОЙ из точки покоя (не телепорт), падает, отскакивает,
 * фиксируется на грани — и плавно интерполируется обратно, показывая
 * результат, пока летит домой. Настоящая физика: 8 вершин, 6 граней,
 * гравитация, затухающие отскоки, кувыркание.
 */

// ── Мини-математика 3×3 (row-major) ─────────────────────────────────────────

private data class V3(val x: Float, val y: Float, val z: Float) {
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

/** Родригес: матрица поворота на angle вокруг оси. */
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

private class Face(val idx: IntArray, val normal: V3, val value: Int)

private val FACES = arrayOf(
    Face(intArrayOf(1, 2, 6, 5), V3(1f, 0f, 0f), 1),
    Face(intArrayOf(0, 4, 7, 3), V3(-1f, 0f, 0f), 6),
    Face(intArrayOf(3, 7, 6, 2), V3(0f, 1f, 0f), 2),
    Face(intArrayOf(0, 1, 5, 4), V3(0f, -1f, 0f), 5),
    Face(intArrayOf(4, 5, 6, 7), V3(0f, 0f, 1f), 3),
    Face(intArrayOf(0, 3, 2, 1), V3(0f, 0f, -1f), 4),
)

private val PIPS = mapOf(
    1 to listOf(.5f to .5f),
    2 to listOf(.28f to .28f, .72f to .72f),
    3 to listOf(.26f to .26f, .5f to .5f, .74f to .74f),
    4 to listOf(.28f to .28f, .72f to .28f, .28f to .72f, .72f to .72f),
    5 to listOf(.26f to .26f, .74f to .26f, .5f to .5f, .26f to .74f, .74f to .74f),
    6 to listOf(.28f to .22f, .28f to .5f, .28f to .78f, .72f to .22f, .72f to .5f, .72f to .78f),
)

private val TO_CAM = V3(0.577f, 0.577f, 0.577f)
private const val COS30 = 0.866f

// ── Тело кубика ─────────────────────────────────────────────────────────────

private enum class DieState { HOVER, THROWN, RESULT, RETURN }

private class DieBody {
    var homeX = 0f
    var groundY = 0f
    var hoverH = 50f
    var rp = 26f                      // физический полу-размер куба (px)

    var px = 0f
    var h = 50f                       // высота ЦЕНТРА над плоскостью пола
    var vx = 0f
    var vh = 0f
    var rot = randomRot()
    var wx = 0.5f; var wy = 0.8f; var wz = 0.3f
    var t = 0f
    var timer = 0f
    var landPx = 0f
    var landH = 0f

    var state = DieState.HOVER
        private set
    var value = 0
        private set
    /** Взводится один раз при фиксации — вызывающий резолвит и сбрасывает. */
    var pendingResolve = false

    var worldW = 0f

    fun placeHome(width: Float, height: Float) {
        worldW = width
        homeX = width - 66f
        groundY = height * 0.72f          // сильно ниже — ~1/3 высоты от нижнего края
        rp = kotlin.math.min(width, height) * 0.057f
        hoverH = rp * 1.9f                 // над полом так, что нижняя вершина не в полу
        if (state == DieState.HOVER && px == 0f) px = homeX
    }

    fun throwWith(vSwipe: Offset) {
        if (state != DieState.HOVER) return
        val speed = sqrt(vSwipe.x * vSwipe.x + vSwipe.y * vSwipe.y)
        if (speed < 90f) return                   // случайный тап — не бросок
        state = DieState.THROWN
        h = kotlin.math.max(h, rp * 1.85f)        // стартуем гарантированно над полом
        // Шире диапазон силы: слабый свайп — короткий бросок, резкий — далёкий
        vx = (vSwipe.x * 0.55f).coerceIn(-1600f, 1600f)
        vh = (300f - vSwipe.y * 0.55f + speed * 0.16f).coerceIn(280f, 1800f)  // вверх
        val sp = (speed * 0.025f + 8f)
        wx = rand(sp); wy = rand(sp); wz = rand(sp)
    }

    /** Тап по кубику — крутануть его на месте (не бросок). */
    fun nudgeSpin() {
        if (state != DieState.HOVER) return
        wx += rand(10f); wy += rand(10f); wz += rand(10f)
    }

    fun step(dt: Float) {
        t += dt
        when (state) {
            DieState.HOVER -> {
                h = hoverH + sin(t * 2.1f) * 4f
                px += (homeX - px) * (1f - kotlin.math.exp(-6f * dt))   // мягко держим у дома
                // Угловая скорость плавно оседает к спокойному idle — после тапа
                // кубик крутанётся и сам успокоится
                val k = 1f - kotlin.math.exp(-1.6f * dt)
                wx += (IDLE_WX - wx) * k; wy += (IDLE_WY - wy) * k; wz += (IDLE_WZ - wz) * k
                spin(dt, 1f)
            }
            DieState.THROWN -> {
                vh -= G * dt
                h += vh * dt
                px += vx * dt
                if (px < 40f) { px = 40f; vx = -vx * 0.5f }
                if (px > worldW - 40f) { px = worldW - 40f; vx = -vx * 0.5f }
                spin(dt, 1f)

                // Контакт по НИЖНЕЙ вершине — кубик задевает пол ребром/углом,
                // а не центром: отсюда естественные отскоки и опрокидывание.
                val lowest = h + rp * lowestVy()
                var grounded = false
                if (lowest < 0f) {
                    grounded = true
                    h -= lowest                                   // вытолкнуть из пола
                    val (axis, tilt) = flatAxisTilt()             // куда валиться на грань
                    if (abs(vh) > 45f) {
                        vh = -vh * RESTITUTION                     // упругий отскок от вершины
                        vx *= 0.72f
                        wx *= 0.5f; wy *= 0.5f; wz *= 0.5f
                        val kick = 3f + tilt * 6f                  // опрокидывающий импульс
                        wx += axis.x * kick; wy += axis.y * kick; wz += axis.z * kick
                    } else {
                        vh = 0f; vx *= 0.5f
                        // Тихо на земле — гравитация доваливает ближайшую грань вниз,
                        // поэтому балансировать на ребре кубик почти никогда не остаётся
                        wx = wx * 0.7f + axis.x * tilt * 14f
                        wy = wy * 0.7f + axis.y * tilt * 14f
                        wz = wz * 0.7f + axis.z * tilt * 14f
                    }
                }
                clampSpin(45f)

                val spinMag = sqrt(wx * wx + wy * wy + wz * wz)
                val tiltNow = flatAxisTilt().second
                // Уложился ровно на грань (ребро — редкость) при низкой энергии
                if (grounded && abs(vh) < 14f && spinMag < 0.9f && tiltNow < 0.05f) {
                    timer += dt
                    if (timer > 0.18f) settle()
                } else timer = 0f
            }
            DieState.RESULT -> {
                timer += dt
                if (timer > 0.7f) { state = DieState.RETURN; timer = 0f; landPx = px; landH = h }
            }
            DieState.RETURN -> {
                // Плавно летим домой, НЕ вращаясь — грань-результат видно всю дорогу
                timer += dt
                val p = smooth((timer / 0.55f).coerceIn(0f, 1f))
                px = lerp(landPx, homeX, p)
                h = lerp(landH, hoverH, p)
                if (p >= 1f) state = DieState.HOVER
            }
        }
    }

    private fun spin(dt: Float, k: Float) {
        val s = sqrt(wx * wx + wy * wy + wz * wz)
        if (s > 1e-4f) rot = mul(rodrigues(wx, wy, wz, s * dt * k), rot)
    }

    private fun settle() {
        rot = snap(rot)
        value = upValue(rot)
        state = DieState.RESULT
        timer = 0f
        pendingResolve = true
    }

    // Масштаб «пульсирует» по состоянию — брошенный крупнее висящего
    fun renderScale(base: Float): Float = base * when (state) {
        DieState.HOVER -> 0.82f
        DieState.THROWN, DieState.RESULT -> 1.15f
        DieState.RETURN -> lerp(1.15f, 0.82f, smooth((timer / 0.55f).coerceIn(0f, 1f)))
    }

    fun showingResult() = state == DieState.RESULT || state == DieState.RETURN

    private fun upValue(m: FloatArray): Int {
        var best = FACES[0]; var bestDot = -2f
        for (f in FACES) {
            val d = apply(m, f.normal).dot(V3(0f, 1f, 0f))
            if (d > bestDot) { bestDot = d; best = f }
        }
        return best.value
    }

    private fun snap(m: FloatArray): FloatArray {
        val cx = axisSnap(V3(m[0], m[3], m[6]))
        var cy = axisSnap(V3(m[1], m[4], m[7]))
        if (sameAxis(cx, cy)) cy = if (cx.x == 0f) V3(1f, 0f, 0f) else V3(0f, 1f, 0f)
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

    private fun cross(a: V3, b: V3) =
        V3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    /** Самая низкая вершина куба в единицах rp (её высота = h + rp·это). */
    private fun lowestVy(): Float {
        var m = 2f
        for (v in VERTS) { val y = apply(rot, v).y; if (y < m) m = y }
        return m
    }

    /**
     * Ось и величина наклона, чтобы верхняя грань «легла» плоско: вращение
     * вокруг axis приближает нормаль верхней грани к вертикали (0,1,0).
     * tilt = sin угла отклонения — 0 когда грань уже плоско лежит.
     */
    private fun flatAxisTilt(): Pair<V3, Float> {
        var bestN = V3(0f, 1f, 0f); var bd = -2f
        for (f in FACES) {
            val wn = apply(rot, f.normal)
            if (wn.y > bd) { bd = wn.y; bestN = wn }
        }
        val ax = cross(bestN, V3(0f, 1f, 0f))
        val tilt = sqrt(ax.x * ax.x + ax.y * ax.y + ax.z * ax.z)
        if (tilt < 1e-4f) return V3(0f, 0f, 0f) to 0f
        return V3(ax.x / tilt, ax.y / tilt, ax.z / tilt) to tilt
    }

    private fun clampSpin(maxW: Float) {
        val m = sqrt(wx * wx + wy * wy + wz * wz)
        if (m > maxW) { val k = maxW / m; wx *= k; wy *= k; wz *= k }
    }

    private companion object {
        const val G = 2600f
        const val RESTITUTION = 0.5f
        const val IDLE_WX = 0.5f
        const val IDLE_WY = 0.8f
        const val IDLE_WZ = 0.3f
        fun rand(s: Float) = Random.nextFloat() * 2f * s - s
        fun randomRot(): FloatArray {
            var m = rodrigues(1f, 0f, 0f, Random.nextFloat() * 6.28f)
            m = mul(rodrigues(0f, 1f, 0f, Random.nextFloat() * 6.28f), m)
            return mul(rodrigues(0f, 0f, 1f, Random.nextFloat() * 6.28f), m)
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun smooth(t: Float) = t * t * (3f - 2f * t)

// ── Проекция и отрисовка ────────────────────────────────────────────────────

private fun project(v: V3, cx: Float, cy: Float, h: Float, scale: Float): Offset = Offset(
    cx + (v.x - v.z) * COS30 * scale,
    cy - h + ((v.x + v.z) * 0.5f - v.y) * scale,
)

// Направление света для затенения граней — сверху-слева-спереди
private val LIGHT = norm(V3(-0.35f, 1f, 0.45f))
private fun norm(v: V3): V3 {
    val l = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
    return if (l < 1e-6f) v else V3(v.x / l, v.y / l, v.z / l)
}

private fun DrawScope.drawDie(body: DieBody, baseScale: Float) {
    val cx = body.px
    val cy = body.groundY
    val scale = body.renderScale(baseScale)

    // Мягкая контактная тень — темнее и уже, когда кубик у земли
    val shadowK = 1f / (1f + body.h / 160f)
    drawOval(
        Color.Black.copy(alpha = 0.4f * shadowK),
        topLeft = Offset(cx - scale * 1.6f * shadowK, cy - scale * 0.62f * shadowK),
        size = Size(scale * 3.2f * shadowK, scale * 1.24f * shadowK),
    )
    val worldNormals = FACES.map { apply(body.rot, it.normal) }
    val upIdx = worldNormals.indices.maxByOrNull { worldNormals[it].dot(V3(0f, 1f, 0f)) } ?: 0

    FACES.forEachIndexed { i, face ->
        val n = worldNormals[i]
        if (n.dot(TO_CAM) <= 0.02f) return@forEachIndexed
        val p = face.idx.map { project(apply(body.rot, VERTS[it]), cx, cy, body.h, scale) }
        val path = Path().apply {
            moveTo(p[0].x, p[0].y); lineTo(p[1].x, p[1].y)
            lineTo(p[2].x, p[2].y); lineTo(p[3].x, p[3].y); close()
        }
        // Затенение по нормали: ambient + diffuse — грани обретают объём
        val nl = n.dot(LIGHT).coerceIn(0f, 1f)
        val shade = 0.42f + 0.58f * nl
        val bc = if (i == upIdx) Color(0xFF7A44C0) else Color(0xFF43206A)
        val fill = Color(bc.red * shade, bc.green * shade, bc.blue * shade, 1f)
        drawPath(path, fill)
        // Неоновое ребро, ярче на освещённой грани
        drawPath(path, Color(0xFF9C7BFF).copy(alpha = 0.5f + 0.5f * nl), style = Stroke(2f))
        PIPS[face.value]?.forEach { (u, v) ->
            val top = lerpO(p[0], p[1], u); val bot = lerpO(p[3], p[2], u)
            val c = lerpO(top, bot, v)
            drawCircle(Color(0xFF003844), scale * 0.13f, Offset(c.x + 0.8f, c.y + 0.8f)) // тень пипса
            drawCircle(Color(0xFF00E5FF), scale * 0.11f, c)
        }
    }
}

private fun lerpO(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

// ── Compose-HUD: одна живая сущность ────────────────────────────────────────

/**
 * Живой кубик на карте. Рисуется поверх всего вида (чтобы бросок мог лететь
 * дугой через карту), а свайп ловится только в правой зоне у точки покоя —
 * остальная карта свободно панорамируется.
 */
@Composable
fun DiceHud(engine: GameEngine, modifier: Modifier = Modifier) {
    val body = remember { DieBody() }
    var tick by remember { mutableIntStateOf(0) }
    var sizePx by remember { mutableStateOf(Size.Zero) }
    val die = remember { Sisters.defaultDie() }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.033f)
                    body.step(dt)
                    if (body.pendingResolve) {
                        body.pendingResolve = false
                        val p = engine.currentState().player
                        DiceCaster.applyRoll(die, body.value, p.col, p.row, engine)
                    }
                    tick++
                }
                last = now
            }
        }
    }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            body.placeHome(size.width, size.height)
            @Suppress("UNUSED_EXPRESSION") tick
            drawDie(body, baseScale = size.minDimension * 0.05f)
        }
        // Зона у правого-нижнего угла, где висит кубик: свайп — бросок, тап — крутить
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .fillMaxSize(fraction = 0.32f)
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onDrag = { change, _ -> tracker.addPosition(change.uptimeMillis, change.position) },
                        onDragEnd = {
                            val v = tracker.calculateVelocity()
                            body.throwWith(Offset(v.x, v.y))
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { body.nudgeSpin() })
                },
        )
    }
}
