package com.google.ai.edge.gallery.customtasks.tinygarden

import kotlin.math.abs

/**
 * Шахматные паттерны движения (SPEC §19.4).
 *
 * Каждая Сестра ходит по миру как её фигура: "куда мне идти" превращается в
 * "кем мне нужно быть, чтобы туда попасть". Действует ТОЛЬКО в оверворлде —
 * бой пошаговый, без позиционной сетки.
 *
 * Подтверждённые назначения: One — King, Six — Queen, Five — Pawn (промоутится).
 * Knight/Bishop/Rook зарезервированы за Two/Three/Four (назначение не зафиксировано).
 */
sealed class MovementPattern {
    /** Обычный 4-way шаг — NPC и герой без активной Сестры. Не шахматная фигура. */
    object Walker : MovementPattern()
    object King   : MovementPattern()  // One
    object Queen  : MovementPattern()  // Six
    data class Pawn(
        val forwardDir: Pathfinder.Step = Pathfinder.Step(0, 1),  // дельта "вперёд" (юг)
        val hasPromoted: Boolean = false,
    ) : MovementPattern()              // Five
    object Knight : MovementPattern()  // зарезервировано
    object Bishop : MovementPattern()  // зарезервировано
    object Rook   : MovementPattern()  // зарезервировано
}

/** Водная преграда — ширина в тайлах поперёк направления пересечения (SPEC §19.5). */
data class WaterBarrier(val widthInTiles: Int)

/** Кто какую воду пересекает. Вода — параметризованный барьер, не boolean. */
fun canCrossWater(pattern: MovementPattern, barrier: WaterBarrier): Boolean = when (pattern) {
    is MovementPattern.Knight -> barrier.widthInTiles <= 1   // прыжок перелетает ровно один тайл воды
    is MovementPattern.Queen  -> barrier.widthInTiles <= 1   // грозна, но география сильнее
    is MovementPattern.Bishop,
    is MovementPattern.Rook,
    is MovementPattern.King,
    is MovementPattern.Walker -> false                        // шаг/скольжение воду не пересекают
    is MovementPattern.Pawn   -> false                        // никогда — в этом суть (промоушен — исключение)
}

object ChessMovement {

    // Скольжение ограничено: мир процедурно бесконечен, "до края доски" не существует
    private const val MAX_SLIDE = 24

    private val ORTHOGONAL = listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)
    private val DIAGONAL   = listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)
    private val KING_DELTAS = ORTHOGONAL + DIAGONAL
    private val KNIGHT_DELTAS = listOf(
        1 to 2, 2 to 1, -1 to 2, -2 to 1,
        1 to -2, 2 to -1, -1 to -2, -2 to -1,
    )

    /**
     * Все клетки, достижимые ровно за один ход фигуры, с учётом проходимости
     * и правил пересечения воды. Занятость клеток фильтрует BFS (blockedCells).
     */
    fun candidateMoves(from: Pathfinder.Step, pattern: MovementPattern, map: IsoMap): List<Pathfinder.Step> =
        when (pattern) {
            is MovementPattern.Walker -> stepMoves(from, ORTHOGONAL, map)
            is MovementPattern.King   -> stepMoves(from, KING_DELTAS, map)
            is MovementPattern.Queen  -> slide(from, KING_DELTAS, map, pattern)
            is MovementPattern.Rook   -> slide(from, ORTHOGONAL, map, pattern)
            is MovementPattern.Bishop -> slide(from, DIAGONAL, map, pattern)
            is MovementPattern.Knight -> knightMoves(from, map)
            is MovementPattern.Pawn   ->
                if (pattern.hasPromoted) slide(from, KING_DELTAS, map, MovementPattern.Queen)
                else pawnMoves(from, pattern, map)
        }

    private fun stepMoves(
        from: Pathfinder.Step,
        deltas: List<Pair<Int, Int>>,
        map: IsoMap,
    ): List<Pathfinder.Step> = deltas.mapNotNull { (dc, dr) ->
        val c = from.col + dc; val r = from.row + dr
        if (map.isWalkable(c, r)) Pathfinder.Step(c, r) else null
    }

    /**
     * Скольжение до первой преграды. Водный run шириной w по направлению
     * скольжения пересекается, если canCrossWater разрешает (Queen: w <= 1).
     */
    private fun slide(
        from: Pathfinder.Step,
        deltas: List<Pair<Int, Int>>,
        map: IsoMap,
        pattern: MovementPattern,
    ): List<Pathfinder.Step> {
        val out = mutableListOf<Pathfinder.Step>()
        for ((dc, dr) in deltas) {
            var c = from.col + dc
            var r = from.row + dr
            var travelled = 0
            while (travelled < MAX_SLIDE) {
                if (map.isWalkable(c, r)) {
                    out.add(Pathfinder.Step(c, r))
                    c += dc; r += dr; travelled++
                    continue
                }
                // Упёрлись в воду — меряем ширину run'а вдоль направления
                var width = 0
                var cc = c; var rr = r
                while (!map.isWalkable(cc, rr) && width <= 2) {
                    width++; cc += dc; rr += dr
                }
                if (canCrossWater(pattern, WaterBarrier(width)) && map.isWalkable(cc, rr)) {
                    out.add(Pathfinder.Step(cc, rr))
                    c = cc + dc; r = rr + dr; travelled += width + 1
                    continue
                }
                break
            }
        }
        return out
    }

    /** Конь перелетает: промежуточные клетки не важны, важна только посадка. */
    private fun knightMoves(from: Pathfinder.Step, map: IsoMap): List<Pathfinder.Step> =
        KNIGHT_DELTAS.mapNotNull { (dc, dr) ->
            val c = from.col + dc; val r = from.row + dr
            if (map.isWalkable(c, r)) Pathfinder.Step(c, r) else null
        }

    /**
     * Пешка: один тайл строго вперёд, воду не пересекает никогда.
     * Диагональное взятие — боевая механика; бой не имеет сетки (SPEC §19.4),
     * поэтому в оверворлде его нет.
     */
    private fun pawnMoves(
        from: Pathfinder.Step,
        pattern: MovementPattern.Pawn,
        map: IsoMap,
    ): List<Pathfinder.Step> {
        val c = from.col + pattern.forwardDir.col
        val r = from.row + pattern.forwardDir.row
        return if (map.isWalkable(c, r)) listOf(Pathfinder.Step(c, r)) else emptyList()
    }
}

/**
 * Зоны промоушена Five (SPEC §19.5).
 *
 * В спеке зона — край текущего буфера карты, но буфер пересобирается, когда
 * игрок подходит к краю ближе 8 тайлов, так что его край недостижим по
 * построению. "Край света" здесь — фиксированный рубеж от начала координат,
 * до которого реально можно дойти. Дополнительные зоны (середина карты) —
 * точка расширения через worldLaw/settlementLore, без изменения движка.
 */
object PromotionZones {
    const val WORLD_EDGE = 96  // grid units от (0,0)

    /** true если (col,row) — триггер промоушена именно для Five. */
    fun isPromotionZone(col: Int, row: Int, map: IsoMap): Boolean =
        abs(col) >= WORLD_EDGE || abs(row) >= WORLD_EDGE
}
