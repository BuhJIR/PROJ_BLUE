package com.google.ai.edge.gallery.customtasks.tinygarden

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * Кадры спрайт-листов по данным sprites_meta.json.
 *
 * Раньше рендерер жёстко резал лист игрока на 45 кадров, а NPC — на 2, хотя
 * реальные листы разные (35–45 кадров у Сестёр, 2 у врагов) и кадры внутри
 * листа неравной ширины (±2px). Отсюда «нарезанная» героиня со сдвигом
 * анимации. Здесь листы режутся по мете, а внутренние границы дотягиваются
 * до ближайшей полностью прозрачной колонки — стыки кадров точные.
 */

/** Паспорт кадров одного листа из sprites_meta.json. */
data class FrameSpec(val frames: Int, val frameW: Int, val frameH: Int, val fps: Int)

/** Декодированный лист с точными x-границами кадров. */
class SpriteSheet(
    val bitmap: ImageBitmap,
    private val bounds: IntArray,   // размер = frameCount + 1
    val fps: Int,
) {
    val frameCount: Int get() = bounds.size - 1

    /** Номинальная ширина кадра — для стабильного центрирования на экране. */
    val nominalW: Int get() = bitmap.width / frameCount

    fun srcLeft(i: Int): Int = bounds[i]
    fun srcWidth(i: Int): Int = bounds[i + 1] - bounds[i]

    fun frameAt(timeMs: Long): Int =
        if (frameCount <= 1) 0 else ((timeMs * fps / 1000) % frameCount).toInt()
}

object SpriteMeta {
    @Volatile private var cache: Map<String, FrameSpec>? = null

    /** Спека по базовому имени листа ("sister_3", "enemy_red"...). */
    fun specFor(context: Context, spriteBase: String): FrameSpec? {
        val all = cache ?: load(context).also { cache = it }
        return all[spriteBase.substringAfterLast('/')]
    }

    private fun load(context: Context): Map<String, FrameSpec> = runCatching {
        val text = context.assets.open("sprites/sprites_meta.json")
            .bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val out = mutableMapOf<String, FrameSpec>()
        for (section in listOf("sprite_frames", "enemies")) {
            root.optJSONObject(section)?.let { o ->
                for (k in o.keys()) {
                    val s = o.getJSONObject(k)
                    out[k] = FrameSpec(
                        frames = s.optInt("frames", 1),
                        frameW = s.optInt("frameW", 0),
                        frameH = s.optInt("frameH", 0),
                        fps = s.optInt("fps", 12),
                    )
                }
            }
        }
        out
    }.getOrDefault(emptyMap())
}

object SpriteSheetCache {
    private val cache = HashMap<String, SpriteSheet?>()

    fun sheet(context: Context, assetPath: String, spriteBase: String): SpriteSheet? {
        if (cache.containsKey(assetPath)) return cache[assetPath]
        val sheet = runCatching { build(context, assetPath, spriteBase) }.getOrNull()
        cache[assetPath] = sheet
        return sheet
    }

    private fun build(context: Context, assetPath: String, spriteBase: String): SpriteSheet? {
        val bmp = context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
            ?: return null
        val spec = SpriteMeta.specFor(context, spriteBase)
        // Число кадров — из ширины листа и номинальной ширины кадра: это
        // переживает листы одной семьи с разным числом кадров
        val frames = when {
            spec == null || spec.frameW <= 0 -> (spec?.frames ?: 1).coerceAtLeast(1)
            else -> (bmp.width.toFloat() / spec.frameW).roundToInt().coerceAtLeast(1)
        }
        val bounds = IntArray(frames + 1) { k -> (k.toLong() * bmp.width / frames).toInt() }
        if (frames > 1) snapToGutters(bmp, bounds)
        return SpriteSheet(bmp.asImageBitmap(), bounds, spec?.fps ?: 12)
    }

    /**
     * Кадры в листах неравной ширины: равномерная сетка даёт дрожание и
     * срезанные конечности. Каждую внутреннюю границу дотягиваем к ближайшей
     * полностью прозрачной колонке (±12px). У листов без прозрачных зазоров
     * (враги) сетка остаётся равномерной — там она и так точна.
     */
    private fun snapToGutters(bmp: Bitmap, bounds: IntArray) {
        val w = bmp.width
        val h = bmp.height
        val opaque = BooleanArray(w)
        val row = IntArray(w)
        val step = (h / 24).coerceAtLeast(1)
        for (y in 0 until h step step) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (!opaque[x] && (row[x] ushr 24) > 8) opaque[x] = true
            }
        }
        for (i in 1 until bounds.size - 1) {
            val b = bounds[i]
            for (d in 0..12) {
                val l = b - d
                val r = b + d
                if (l > 0 && !opaque[l]) { bounds[i] = l; break }
                if (r < w - 1 && !opaque[r]) { bounds[i] = r; break }
            }
        }
    }
}
