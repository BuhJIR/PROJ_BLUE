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
    ): List<Triple<Int, Int, LayeredTileEx>> {
        val result = mutableListOf<Triple<Int, Int, LayeredTileEx>>()

        for (level in 0 until spec.levels) {
            val size = baseSize - level * margin * 2
            if (size <= 0) break
            val half = size / 2
            val height = level

            for (dr in -half..half) {
                for (dc in -half..half) {
                    val isEdge = dr == -half || dr == half || dc == -half || dc == half
                    // На верхнем ярусе, если flatTop — заливаем всё; иначе только край (пик)
                    val shouldPlace = if (level == spec.levels - 1 && !spec.flatTop) {
                        isEdge || (dr == 0 && dc == 0)
                    } else true

                    if (shouldPlace) {
                        val wc = centerCol + dc
                        val wr = centerRow + dr
                        result.add(Triple(wc, wr, LayeredTileEx(spec.material, height)))
                    }
                }
            }

            // Ступени — на каждой границе ярусов, по центру одной из сторон
            if (spec.hasStairs && level > 0) {
                val prevHalf = (baseSize - (level - 1) * margin * 2) / 2
                val stairCol = centerCol
                val stairRow = centerRow + prevHalf  // южная сторона
                result.add(Triple(
                    stairCol, stairRow,
                    LayeredTileEx(
                        spec.material, level - 1,
                        StairInfo(level - 1, level, StepDirection.SOUTH)
                    )
                ))
            }
        }

        return result
    }

    /** Применяет сгенерированную структуру к живой карте движка. */
    fun applyToEngine(spec: StructureSpec, centerCol: Int, centerRow: Int, engine: GameEngine) {
        val tiles = generate(spec, centerCol, centerRow)
        engine.applyStructure(tiles)
    }
}
