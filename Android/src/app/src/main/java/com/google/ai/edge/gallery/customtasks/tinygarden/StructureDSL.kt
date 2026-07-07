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

/** Семейство формы постройки (SPEC §16): shape отделён от материала и декора. */
enum class StructureShape { ZIGGURAT, TOWER, RING }

data class StructureSpec(
    val levels: Int,               // сколько ярусов — БЕЗ ограничений, мир не спорит
    val hasStairs: Boolean,
    val excludeTrees: Boolean,
    val material: TileType,
    val openType: Boolean,         // терраса/ворота: для ring — проём в южной стене
    val flatTop: Boolean,          // true = верх плоский, false = пик/пирамида
    val shape: StructureShape = StructureShape.ZIGGURAT,
    val baseSize: Int = 0,         // 0 = авто: зиккурат сам расширяет базу под ярусы
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
        var shape = StructureShape.ZIGGURAT
        var baseSize = 0

        for (tok in tokens) {
            when {
                tok.contains("×level") || tok.contains("xlevel") -> {
                    levels = (tok.substringBefore("×").substringBefore("x")
                        .filter { it.isDigit() }.toIntOrNull() ?: 1).coerceAtLeast(1)
                }
                tok.contains("×base") || tok.contains("xbase") -> {
                    baseSize = (tok.substringBefore("×").substringBefore("x")
                        .filter { it.isDigit() }.toIntOrNull() ?: 0).coerceAtLeast(0)
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
                tok == "tower"   -> shape = StructureShape.TOWER
                tok == "ring" || tok == "walls" || tok == "courtyard" -> shape = StructureShape.RING
            }
        }
        return StructureSpec(levels, hasStairs, excludeTrees, material, openType, flatTop, shape, baseSize)
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
        margin: Int = 1,         // на сколько сужается каждый ярус зиккурата
        baseHeight: Int = 0,     // высота земли: постройка растёт ОТ рельефа, не от нуля
    ): List<Triple<Int, Int, LayeredTileEx>> = when (spec.shape) {
        StructureShape.ZIGGURAT -> ziggurat(spec, centerCol, centerRow, margin, baseHeight)
        StructureShape.TOWER    -> tower(spec, centerCol, centerRow, baseHeight)
        StructureShape.RING     -> ring(spec, centerCol, centerRow, baseHeight)
    }

    /**
     * Зиккурат/пирамида. База либо задана токеном 'N×base', либо авто:
     * расширяется под запрошенные ярусы — попросили 150 ярусов, получат гору.
     */
    private fun ziggurat(
        spec: StructureSpec, centerCol: Int, centerRow: Int,
        margin: Int, baseHeight: Int,
    ): List<Triple<Int, Int, LayeredTileEx>> {
        val result = mutableListOf<Triple<Int, Int, LayeredTileEx>>()
        val baseSize = if (spec.baseSize > 0) spec.baseSize
                       else maxOf(7, spec.levels * margin * 2 + 1)

        // Тело: полные квадраты, каждый ярус уже предыдущего
        for (level in 0 until spec.levels) {
            val size = baseSize - level * margin * 2
            if (size <= 0) break
            placeSquare(result, spec.material, centerCol, centerRow, size, baseHeight + level)

            // Ступени — на каждой границе ярусов, по центру южной стороны
            if (spec.hasStairs && level > 0) {
                val prevHalf = (baseSize - (level - 1) * margin * 2) / 2
                result.add(Triple(
                    centerCol, centerRow + prevHalf,
                    LayeredTileEx(
                        spec.material, baseHeight + level - 1,
                        StairInfo(baseHeight + level - 1, baseHeight + level, StepDirection.SOUTH)
                    )
                ))
            }
        }

        // Пик (SPEC §12): настоящее сужение до колонны 1×1 над телом.
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

    /**
     * Башня (SPEC §16 TowerShape): основание НЕ сужается, рост только вверх —
     * единственная форма, где 150 ярусов имеют смысл. Тайл — колонна полной
     * высоты, поэтому достаточно одной записи с финальной высотой.
     */
    private fun tower(
        spec: StructureSpec, centerCol: Int, centerRow: Int, baseHeight: Int,
    ): List<Triple<Int, Int, LayeredTileEx>> {
        val result = mutableListOf<Triple<Int, Int, LayeredTileEx>>()
        val size = if (spec.baseSize > 0) spec.baseSize else 3
        val topH = baseHeight + spec.levels - 1
        placeSquare(result, spec.material, centerCol, centerRow, size, topH)
        // Пик поверх башни — маленький шпиль
        if (!spec.flatTop) {
            var level = topH + 1
            var s = size - 2
            while (s >= 1) {
                placeSquare(result, spec.material, centerCol, centerRow, s, level)
                s -= 2
                level++
            }
        }
        return result
    }

    /**
     * Кольцо (SPEC §16 RingShape): только стены, внутри — двор на земле.
     * open-type оставляет ворота в южной стене.
     */
    private fun ring(
        spec: StructureSpec, centerCol: Int, centerRow: Int, baseHeight: Int,
    ): List<Triple<Int, Int, LayeredTileEx>> {
        val result = mutableListOf<Triple<Int, Int, LayeredTileEx>>()
        val size = if (spec.baseSize > 0) spec.baseSize else 9
        val half = size / 2
        val wallH = baseHeight + spec.levels - 1
        for (dr in -half..half) {
            for (dc in -half..half) {
                val isEdge = dr == -half || dr == half || dc == -half || dc == half
                if (!isEdge) continue
                // Ворота: проём в центре южной стены
                if (spec.openType && dr == half && dc == 0) continue
                result.add(Triple(centerCol + dc, centerRow + dr, LayeredTileEx(spec.material, wallH)))
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

    /**
     * Поднять весь пол в радиусе — вместе со ВСЕМ построенным на нём.
     * Радиус называет игрок; Душа передаёт его параметром.
     */
    fun raiseTerrain(centerCol: Int, centerRow: Int, radius: Int, lift: Int, engine: GameEngine) {
        val tiles = mutableListOf<Triple<Int, Int, LayeredTileEx>>()
        val r2 = radius * radius
        for (dr in -radius..radius) {
            for (dc in -radius..radius) {
                if (dc * dc + dr * dr > r2) continue
                val c = centerCol + dc
                val r = centerRow + dr
                val existing = engine.structureOverrides[c to r]
                if (existing != null) {
                    // Постройка едет вверх вместе с полом, лестницы — тоже
                    tiles.add(Triple(c, r, existing.copy(
                        height = existing.height + lift,
                        stair = existing.stair?.copy(
                            fromHeight = existing.stair.fromHeight + lift,
                            toHeight = existing.stair.toHeight + lift,
                        ),
                    )))
                } else {
                    val t = generateTile(c, r, engine.worldMap.seed)
                    tiles.add(Triple(c, r, LayeredTileEx(t.base, t.height + lift)))
                }
            }
        }
        engine.applyStructure(tiles)
        engine.logMessage("The ground itself obeys — earth rises within $radius tiles.")
    }
}
