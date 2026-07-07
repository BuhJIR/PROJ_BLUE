package com.google.ai.edge.gallery.customtasks.tinygarden

import kotlin.math.abs
import kotlin.math.max

/**
 * BehaviourExecutor — актуатор (SPEC §2).
 *
 * BehaviourDecider решает, ЧТО entity делает; этот объект превращает решение
 * в реальные вызовы движка: applyDamage, executePath, pickUpItemAt.
 * Вызывается из GameEngine.wakeEntity сразу после decide() — без него все семь
 * вариантов Behaviour были тупиками, и NPC "решал бежать" стоя на месте.
 */
object BehaviourExecutor {

    /**
     * Урон базовой атаки. Минимальная формула до полной боевой системы (§17.1):
     * небольшая база + бонус за "массу" атакующего.
     */
    fun computeDamage(attacker: Entity): Int = 3 + attacker.maxHp / 25

    fun execute(entity: Entity, engine: GameEngine) {
        if (!entity.isAlive()) return
        val map = engine.worldMap
        when (val b = entity.currentBehaviour) {
            Behaviour.Idle          -> {} // осознанное ничего — корректно
            Behaviour.Wander        -> wander(entity, engine, map)
            is Behaviour.Navigate   -> navigateTo(entity, b.tx, b.ty, engine, map)
            is Behaviour.Attack     -> attack(entity, b.targetId, engine, map)
            is Behaviour.Collect    -> collect(entity, b, engine, map)
            is Behaviour.Socialize  -> socialize(entity, b.targetId, engine, map)
            is Behaviour.Flee       -> flee(entity, b, engine, map)
            is Behaviour.Custom     ->
                // Модель-определённое поведение — сигналим в мир, Душа/микро-агент решит
                engine.emitCustomEvent(
                    entity.x.toFloat(), entity.y.toFloat(),
                    radius = 32f, intensity = 0.5f,
                    payload = mapOf("behaviour" to b.tag) + b.data,
                )
        }
    }

    // ── Атака ────────────────────────────────────────────────────────────────

    private fun attack(entity: Entity, targetId: String, engine: GameEngine, map: IsoMap) {
        val target = engine.resolveEntity(targetId) ?: return
        if (!target.isAlive()) return
        if (chebyshev(entity, target) <= 1) {
            engine.applyDamage(target.id, computeDamage(entity))
        } else {
            // Не дотягиваемся — подходим вплотную (последний шаг пути — клетка цели, срезаем его)
            pathTowards(entity, target.col, target.row, engine, map, stopShort = true)
        }
    }

    // ── Навигация ────────────────────────────────────────────────────────────

    private fun navigateTo(entity: Entity, tx: Int, ty: Int, engine: GameEngine, map: IsoMap) {
        pathTowards(entity, tx, ty, engine, map, stopShort = false)
    }

    private fun pathTowards(
        entity: Entity, tx: Int, ty: Int,
        engine: GameEngine, map: IsoMap,
        stopShort: Boolean,
    ) {
        val blocked = engine.occupiedCells(except = setOf(entity.id))
            .let { if (stopShort) it - (tx to ty) else it }
        val path = Pathfinder.findPath(entity.col, entity.row, tx, ty, map, blocked) ?: return
        // NPC проходит максимум moveRange клеток за пробуждение — дальняя цель
        // достигается за несколько «ходов», как фигура, а не телепорт
        val walk = (if (stopShort) path.dropLast(1) else path).take(entity.moveRange)
        if (walk.isNotEmpty()) engine.executePath(entity.id, walk)
    }

    // ── Бегство ──────────────────────────────────────────────────────────────

    private fun flee(entity: Entity, b: Behaviour.Flee, engine: GameEngine, map: IsoMap) {
        // Вектор от угрозы к entity, огрублённый до знаков — противоположное направление
        val sx = when {
            entity.x > b.fromX -> 1
            entity.x < b.fromX -> -1
            else               -> if (entity.id.hashCode() % 2 == 0) 1 else -1
        }
        val sy = when {
            entity.y > b.fromY -> 1
            entity.y < b.fromY -> -1
            else               -> 0
        }
        // Пробуем убежать подальше; если путь не находится — короче; в упор — хотя бы шаг
        for (dist in intArrayOf(entity.moveRange, 2)) {
            val tx = entity.x + sx * dist
            val ty = entity.y + sy * dist
            if (map.isWalkable(tx, ty)) {
                val blocked = engine.occupiedCells(except = setOf(entity.id))
                val path = Pathfinder.findPath(entity.col, entity.row, tx, ty, map, blocked)
                if (!path.isNullOrEmpty()) {
                    engine.executePath(entity.id, path.take(entity.moveRange))
                    return
                }
            }
        }
        if (map.isWalkable(entity.x + sx, entity.y + sy)) {
            engine.moveEntity(entity.id, sx, sy)
        }
    }

    // ── Сбор предмета ────────────────────────────────────────────────────────

    private fun collect(entity: Entity, b: Behaviour.Collect, engine: GameEngine, map: IsoMap) {
        if (entity.x == b.itemX && entity.y == b.itemY) {
            engine.pickUpItemAt(b.itemX, b.itemY, b.itemType, entity)
        } else {
            navigateTo(entity, b.itemX, b.itemY, engine, map)
        }
    }

    // ── Социализация ─────────────────────────────────────────────────────────

    private fun socialize(entity: Entity, targetId: String, engine: GameEngine, map: IsoMap) {
        val target = engine.resolveEntity(targetId) ?: return
        if (chebyshev(entity, target) <= 1) {
            engine.logMessage("${entity.name} chats with ${target.name}.")
            engine.emitCustomEvent(
                entity.x.toFloat(), entity.y.toFloat(),
                radius = 24f, intensity = 0.3f,
                payload = mapOf("type" to "SOCIAL", "with" to target.id),
            )
        } else {
            pathTowards(entity, target.col, target.row, engine, map, stopShort = true)
        }
    }

    // ── Прогулка ─────────────────────────────────────────────────────────────

    private fun wander(entity: Entity, engine: GameEngine, map: IsoMap) {
        val occupied = engine.occupiedCells(except = setOf(entity.id))
        val dirs = listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0).shuffled()
        for ((dx, dy) in dirs) {
            val nx = entity.x + dx
            val ny = entity.y + dy
            if (map.isWalkable(nx, ny) && (nx to ny) !in occupied) {
                engine.moveEntity(entity.id, dx, dy)
                return
            }
        }
    }

    private fun chebyshev(a: Entity, b: Entity): Int =
        max(abs(a.x - b.x), abs(a.y - b.y))
}
