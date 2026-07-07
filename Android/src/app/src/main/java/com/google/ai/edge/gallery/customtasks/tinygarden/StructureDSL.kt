package com.google.ai.edge.gallery.customtasks.tinygarden

/**
 * StructureDSL — компактная грамматика для генерации построек.
 *
 * Формат: [N×levels; feature1; feature2; ...; material; shape; top]
 * Пример: "3×levels; stairs; non-trees; stone; open-type; flat"
 *
 * Языковая модель пишет ОДНУ строку вместо десятков JSON-команд.
 * Парсер превращает её в детерминированный набор LayeredTile на карте.
 */

enum class StepDirection { NORTH, SOUTH, EAST, WEST }

/** Ступень внутри клетки — не отдельный тайл, а модификатор LayeredTile. */
data class StairInfo(val fromHeight: Int, val toHeight: Int, val facing: StepDirection)

data class LayeredTileEx(
    val base: TileType,
    val height: Int = 0,
    val stair: StairInfo? = null,
)

data class StructureSpec(
    val levels: Int,               // сколько ярусов (пирамида вниз к 1)
    val hasStairs: Boolean,
    val excludeTrees: Boolean,
    val material: TileType,
    val openType: Boolean,         // true = терраса открыта, false = закрытая крыша
    val flatTop: Boolean,          // true = верх плоский, false = пик/пирамида
)

/**
 * Парсер DSL строки от модели.
 * Токены через ";", регистр и пробелы не важны.
 */
object StructureParser {

    fun parse(input: String): StructureSpec {
        val tokens = input
            .trim()
            .removePrefix("[").removeSuffix("]")
            .split(";")
            .map { it.trim().lowercase() }

        var levels = 1
        var hasStairs = false
        var excludeTrees = false
        var material = TileType.STONE
        var openType = true
        var flatTop = true

        for (tok in tokens) {
            when {
                tok.contains("×level") || tok.contains("xlevel") -> {
                    levels = tok.substringBefore("×").substringBefore("x")
                        .filter { it.isDigit() }.toIntOrNull() ?: 1
                }
                tok == "stairs" || tok == "stair"      -> hasStairs = true
                tok == "non-trees" || tok == "no-trees" -> excludeTrees = true
                tok == "stone"   -> material = TileType.STONE
                tok == "wood"    -> material = TileType.WOOD
                tok == "dirt"    -> material = TileType.DIRT
                tok == "grass"   -> material = TileType.GRASS
                tok == "open-type" || tok == "open"    -> openType = true
                tok == "closed-type" || tok == "closed" -> openType = false
                tok == "flat"    -> flatTop = true
                tok == "peak" || tok == "pyramid"       -> flatTop = false
            }
        }
        return StructureSpec(levels, hasStairs, excludeTrees, material, openType, flatTop)
    }
}

/**
 * Генератор — превращает StructureSpec в набор тайлов на карте
 * вокруг центра (centerCol, centerRow), пирамидальной формы как на референсе.
 *
 * Каждый уровень уменьшается на margin с каждой стороны — классическая
 * зиккурат-структура (см. FabianTerhorst/Isometric Stairs.java как референс формы).
 */
object StructureGenerator {

    /**
     * @return список (worldCol, worldRow, LayeredTileEx) для применения к IsoMap
     */
    fun generate(
        spec: StructureSpec,
        centerCol: Int,
        centerRow: Int,
        baseSize: Int = 7,       // размер нижнего яруса (нечётное — есть центр)
        margin: Int = 1,         // на сколько уменьшается каждый следующий ярус
        baseHeight: Int = 0,     // высота земли: постройка растёт ОТ рельефа, не от нуля
    ): List<Triple<Int, Int, LayeredTileEx>> {
        val result = mutableListOf<Triple<Int, Int, LayeredTileEx>>()

        // Тело зиккурата: полные квадраты, каждый ярус уже предыдущего
        for (level in 0 until spec.levels) {
            val size = baseSize - level * margin * 2
            if (size <= 0) break
            placeSquare(result, spec.material, centerCol, centerRow, size, baseHeight + level)

            // Ступени — на каждой границе ярусов, по центру одной из сторон
            if (spec.hasStairs && level > 0) {
                val prevHalf = (baseSize - (level - 1) * margin * 2) / 2
                val stairCol = centerCol
                val stairRow = centerRow + prevHalf  // южная сторона
                result.add(Triple(
                    stairCol, stairRow,
                    LayeredTileEx(
                        spec.material, baseHeight + level - 1,
                        StairInfo(baseHeight + level - 1, baseHeight + level, StepDirection.SOUTH)
                    )
                ))
            }
        }

        // Пик (SPEC §12): не кольцо с точкой, а настоящее сужение — продолжаем
        // ставить уменьшающиеся квадраты над телом, пока не сойдёмся к колонне 1×1.
        // Поздние тайлы перекрывают ранние на тех же клетках — вершина выигрывает.
        if (!spec.flatTop) {
            var level = spec.levels
            var size = baseSize - spec.levels * margin * 2
            while (size >= 1) {
                placeSquare(result, spec.material, centerCol, centerRow, size, baseHeight + level)
                size -= margin * 2
                level++
            }
        }

        return result
    }

    private fun placeSquare(
        result: MutableList<Triple<Int, Int, LayeredTileEx>>,
        material: TileType,
        centerCol: Int, centerRow: Int,
        size: Int, height: Int,
    ) {
        val half = size / 2
        for (dr in -half..half) {
            for (dc in -half..half) {
                result.add(Triple(centerCol + dc, centerRow + dr, LayeredTileEx(material, height)))
            }
        }
    }

    /** Применяет сгенерированную структуру к живой карте движка. */
    fun applyToEngine(spec: StructureSpec, centerCol: Int, centerRow: Int, engine: GameEngine) {
        // Постройка растёт от рельефа: первый ярус на 1 выше земли под центром,
        // иначе на холмах здания тонут в грунте и читаются как «серые плато»
        val groundH = engine.worldMap.tileAt(centerCol, centerRow).height
        val tiles = generate(spec, centerCol, centerRow, baseHeight = groundH + 1)
        engine.applyStructure(tiles)
    }
}
