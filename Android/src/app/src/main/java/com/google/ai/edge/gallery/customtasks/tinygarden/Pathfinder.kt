package com.google.ai.edge.gallery.customtasks.tinygarden

/**
 * Pathfinder — BFS с подключаемой генерацией соседей (SPEC §19.4).
 *
 * Алгоритм:
 *  1. BFS находит путь с минимальным числом ходов; соседей на каждом шаге даёт
 *     ChessMovement.candidateMoves(pattern) — Walker (обычный 4-way), King,
 *     Queen, Pawn и т.д. ходят по одному и тому же поиску
 *  2. Для Walker beautifyPath() переупорядочивает шаги: сначала длинная ось,
 *     потом короткая → L-образный поворот вместо зигзага
 */
object Pathfinder {

    data class Step(val col: Int, val row: Int)

    // Мир процедурно бесконечен: недостижимая цель (вода) иначе заставила бы
    // BFS расширяться вечно. Достигли лимита — считаем цель недостижимой.
    private const val MAX_EXPANSIONS = 20_000

    /** BFS — путь с минимальным числом ходов от (sc,sr) до (ec,er). null если недостижим. */
    fun findPath(
        startCol: Int, startRow: Int,
        endCol: Int, endRow: Int,
        map: IsoMap,
        blockedCells: Set<Pair<Int,Int>> = emptySet(),
        pattern: MovementPattern = MovementPattern.Walker,
    ): List<Step>? {
        if (startCol == endCol && startRow == endRow) return emptyList()

        val target = Step(endCol, endRow)
        val visited = mutableSetOf<Step>()
        val parent = mutableMapOf<Step, Step?>()
        val queue = ArrayDeque<Step>()

        val start = Step(startCol, startRow)
        queue.add(start)
        visited.add(start)
        parent[start] = null

        var expansions = 0
        while (queue.isNotEmpty() && expansions < MAX_EXPANSIONS) {
            val cur = queue.removeFirst()
            expansions++
            if (cur == target) {
                // Восстанавливаем путь
                val path = mutableListOf<Step>()
                var node: Step? = cur
                while (node != null && node != start) {
                    path.add(node)
                    node = parent[node]
                }
                path.reverse()
                // beautify пересобирает путь единичными шагами — корректно только для Walker
                return if (pattern is MovementPattern.Walker) beautifyPath(start, path) else path
            }
            for (next in ChessMovement.candidateMoves(cur, pattern, map)) {
                if (next in visited) continue
                if (Pair(next.col, next.row) in blockedCells) continue
                visited.add(next)
                parent[next] = cur
                queue.add(next)
            }
        }
        return null // недостижим
    }

    /**
     * Beautify — "ход конём":
     * Сортируем шаги так, чтобы сначала шли все движения по длинной оси,
     * потом по короткой. Даёт L-форму вместо зигзага.
     */
    private fun beautifyPath(start: Step, path: List<Step>): List<Step> {
        if (path.size <= 1) return path

        val end = path.last()
        val dc = end.col - start.col
        val dr = end.row - start.row

        // Определяем доминирующую ось
        val colFirst = Math.abs(dc) >= Math.abs(dr)

        val result = mutableListOf<Step>()
        var cur = start

        // Шаг 1: движение по доминирующей оси
        val primarySteps = if (colFirst) Math.abs(dc) else Math.abs(dr)
        val primaryDC = if (colFirst) if (dc > 0) 1 else -1 else 0
        val primaryDR = if (colFirst) 0 else if (dr > 0) 1 else -1

        repeat(primarySteps) {
            cur = Step(cur.col + primaryDC, cur.row + primaryDR)
            result.add(cur)
        }

        // Шаг 2: движение по второстепенной оси
        val secondarySteps = if (colFirst) Math.abs(dr) else Math.abs(dc)
        val secondaryDC = if (colFirst) 0 else if (dc > 0) 1 else -1
        val secondaryDR = if (colFirst) if (dr > 0) 1 else -1 else 0

        repeat(secondarySteps) {
            cur = Step(cur.col + secondaryDC, cur.row + secondaryDR)
            result.add(cur)
        }

        return result
    }

    /** Direction из двух соседних шагов пути. */
    fun stepDirection(from: Step, to: Step): String = when {
        to.col > from.col -> "EAST"
        to.col < from.col -> "WEST"
        to.row < from.row -> "NORTH"
        else              -> "SOUTH"
    }
}
