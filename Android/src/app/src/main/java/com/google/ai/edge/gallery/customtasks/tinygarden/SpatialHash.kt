package com.google.ai.edge.gallery.customtasks.tinygarden

import kotlin.math.floor

/**
 * Пространственный хэш — O(1) поиск соседей.
 * Мир разбит на ячейки размером cellSize.
 * "Кто рядом со взрывом" = запрос 3×3 ячеек вокруг точки.
 */
class SpatialHash(private val cellSize: Float = 64f) {

    private val cells = HashMap<Long, MutableList<Entity>>()

    private fun key(cx: Int, cy: Int): Long = cx.toLong() shl 32 or cy.toLong().and(0xFFFFFFFFL)
    private fun cell(v: Float): Int = floor(v / cellSize).toInt()

    fun insert(entity: Entity) {
        val k = key(cell(entity.x.toFloat()), cell(entity.y.toFloat()))
        cells.getOrPut(k) { mutableListOf() }.add(entity)
    }

    fun remove(entity: Entity) {
        val k = key(cell(entity.x.toFloat()), cell(entity.y.toFloat()))
        cells[k]?.remove(entity)
    }

    fun move(entity: Entity, oldX: Int, oldY: Int) {
        val oldKey = key(cell(oldX.toFloat()), cell(oldY.toFloat()))
        cells[oldKey]?.remove(entity)
        insert(entity)
    }

    /**
     * Все entity в радиусе radius от точки (px, py).
     * Все координаты и радиусы — в grid units (SPEC §6): Entity.x/y, WorldEvent.x/y
     * и radius живут в одном пространстве; пиксели появляются только в рендерере.
     */
    fun query(px: Float, py: Float, radius: Float): List<Entity> {
        val result = mutableListOf<Entity>()
        val r = (radius / cellSize).toInt() + 1
        val cx = cell(px); val cy = cell(py)
        for (dx in -r..r) for (dy in -r..r) {
            cells[key(cx + dx, cy + dy)]?.forEach { e ->
                val ex = e.x - px
                val ey = e.y - py
                if (ex * ex + ey * ey <= radius * radius) result.add(e)
            }
        }
        return result
    }

    fun clear() = cells.clear()
}
