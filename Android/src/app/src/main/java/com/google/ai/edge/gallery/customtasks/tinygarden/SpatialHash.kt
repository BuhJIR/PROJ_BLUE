package com.google.ai.edge.gallery.customtasks.tinygarden

import kotlin.math.floor

/**
 * Пространственный хэш — O(1) поиск соседей.
 * Мир разбит на ячейки размером cellSize.
 * "Кто рядом со взрывом" = запрос 3×3 ячеек вокруг точки.
 *
 * Все методы synchronized: query() запускает волну пробуждений, внутри которой
 * NPC двигаются и мутируют те же ячейки; executePath разных entity бегут в
 * параллельных корутинах. Без блокировки это давало интермиттентный
 * ConcurrentModificationException при движении игрока.
 */
class SpatialHash(private val cellSize: Float = 64f) {

    private val cells = HashMap<Long, MutableList<Entity>>()

    private fun key(cx: Int, cy: Int): Long = cx.toLong() shl 32 or cy.toLong().and(0xFFFFFFFFL)
    private fun cell(v: Float): Int = floor(v / cellSize).toInt()

    @Synchronized
    fun insert(entity: Entity) {
        val k = key(cell(entity.x.toFloat()), cell(entity.y.toFloat()))
        cells.getOrPut(k) { mutableListOf() }.add(entity)
    }

    @Synchronized
    fun remove(entity: Entity) {
        val k = key(cell(entity.x.toFloat()), cell(entity.y.toFloat()))
        cells[k]?.remove(entity)
    }

    @Synchronized
    fun move(entity: Entity, oldX: Int, oldY: Int) {
        val oldKey = key(cell(oldX.toFloat()), cell(oldY.toFloat()))
        cells[oldKey]?.remove(entity)
        val k = key(cell(entity.x.toFloat()), cell(entity.y.toFloat()))
        cells.getOrPut(k) { mutableListOf() }.add(entity)
    }

    /**
     * Все entity в радиусе radius от точки (px, py). Возвращает снапшот —
     * вызывающий может двигать entity, не ломая внутренние структуры.
     * Все координаты и радиусы — в grid units (SPEC §6): Entity.x/y, WorldEvent.x/y
     * и radius живут в одном пространстве; пиксели появляются только в рендерере.
     */
    @Synchronized
    fun query(px: Float, py: Float, radius: Float): List<Entity> {
        val result = mutableListOf<Entity>()
        // clamp до переполнения: radius=MAX_VALUE сатурирует toLong(), 2*r+1 ушло бы в минус
        val r = minOf((radius / cellSize).toLong() + 1, 1_000_000L)
        // Мировые события (radius = MAX_VALUE, WORLD_LAW_CHANGE) покрывают больше
        // ячеек, чем занято — дешевле обойти занятые, чем квадрат окна
        if ((2 * r + 1) * (2 * r + 1) > cells.size) {
            cells.values.forEach { list ->
                list.forEach { e ->
                    val ex = e.x - px
                    val ey = e.y - py
                    if (ex * ex + ey * ey <= radius * radius) result.add(e)
                }
            }
            return result
        }
        val ri = r.toInt()
        val cx = cell(px); val cy = cell(py)
        for (dx in -ri..ri) for (dy in -ri..ri) {
            cells[key(cx + dx, cy + dy)]?.forEach { e ->
                val ex = e.x - px
                val ey = e.y - py
                if (ex * ex + ey * ey <= radius * radius) result.add(e)
            }
        }
        return result
    }

    @Synchronized
    fun clear() = cells.clear()
}
