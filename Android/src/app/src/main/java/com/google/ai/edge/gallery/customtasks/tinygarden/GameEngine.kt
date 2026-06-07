package com.google.ai.edge.gallery.customtasks.tinygarden

import java.util.UUID
import kotlin.math.sqrt
import kotlin.math.abs

/**
 * Базовый класс для всего в мире.
 * Флаги — универсальный язык состояний.
 * FlagGroups — именованные наборы флагов (WORK, SOCIAL, COMBAT...).
 */
abstract class GameObject(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
) {
    val flags: MutableSet<String> = mutableSetOf()
    private val flagGroups: MutableMap<String, FlagGroup> = mutableMapOf()

    fun addFlag(flag: String) = flags.add(flag.uppercase())
    fun removeFlag(flag: String) = flags.remove(flag.uppercase())
    fun hasFlag(flag: String) = flags.contains(flag.uppercase())

    fun registerGroup(group: FlagGroup) { flagGroups[group.name.uppercase()] = group }
    fun addGroup(groupName: String) { flagGroups[groupName.uppercase()]?.flags?.forEach { addFlag(it) } }
    fun removeGroup(groupName: String) { flagGroups[groupName.uppercase()]?.flags?.forEach { removeFlag(it) } }
    fun hasGroup(groupName: String): Boolean =
        flagGroups[groupName.uppercase()]?.flags?.any { hasFlag(it) } ?: false
}

/**
 * Entity — живой объект мира: герой, NPC, монстр.
 * Содержит нужды, память, поведение и радиус интереса.
 */
class Entity(
    name: String,
    var hp: Int,
    var maxHp: Int,
    var mp: Int = 0,
    var maxMp: Int = 0,
    var x: Int = 0,
    var y: Int = 0,
    var interestRadius: Float = 192f,  // как далеко entity "слышит" события
) : GameObject(name = name) {
    // Изометрические координаты (алиасы для ясности в рендерере)
    var col: Int get() = x; set(v) { x = v }
    var row: Int get() = y; set(v) { y = v }

    val needs: MutableList<Need> = mutableListOf()
    val memory: MutableMap<String, Any> = mutableMapOf()
    var currentBehaviour: Behaviour = Behaviour.Idle
    var isAwake: Boolean = true

    /** Нанести урон. Возвращает true если entity умер. */
    fun applyDamage(amount: Int): Boolean {
        hp = (hp - amount).coerceAtLeast(0)
        return hp == 0
    }

    /** Лечение. */
    fun heal(amount: Int) { hp = (hp + amount).coerceAtMost(maxHp) }

    fun isAlive() = hp > 0

    fun distanceTo(other: Entity): Float {
        val dx = (x - other.x).toFloat()
        val dy = (y - other.y).toFloat()
        return sqrt(dx * dx + dy * dy)
    }

    fun distanceTo(ex: Float, ey: Float): Float {
        val dx = x - ex; val dy = y - ey
        return sqrt(dx * dx + dy * dy)
    }
}

enum class GameMode { OVERWORLD, BATTLE }

/** Глобальный State — иммутабельный снимок мира для Compose. */
data class GameState(
    val mode: GameMode = GameMode.OVERWORLD,
    val player: Entity = Entity("Hero", 100, 100, 50, 50, x = 5, y = 5),
    val entities: Map<String, Entity> = emptyMap(),
    val items: Map<String, WorldItem> = emptyMap(),
    val battleLog: List<String> = emptyList(),
    val turn: Int = 0,
) {
    fun getEnemies() = entities.values.filter { it.hasFlag("ENEMY") }
    fun getLiving() = entities.values.filter { it.isAlive() }
    fun getByFlag(flag: String) = entities.values.filter { it.hasFlag(flag) }
    fun getByFlags(vararg flags: String) = entities.values.filter { e -> flags.all { e.hasFlag(it) } }
}

/**
 * GameEngine — единое ядро.
 *
 * Интегрирует:
 *  - SpatialHash для O(1) пространственных запросов
 *  - EventBus для stimulus-response пробуждения NPC
 *  - BehaviourDecider для детерминированного поведения по флагам
 *  - Иммутабельный GameState для Compose
 *
 * Работает на Dispatchers.Default — UI не блокируется.
 */
class GameEngine {
    private var state = GameState()
    private var onStateChanged: ((GameState) -> Unit)? = null

    val spatialHash = SpatialHash(cellSize = 64f)
    val eventBus = EventBus()

    init {
        // Подписываемся на все события — будим NPC в радиусе
        eventBus.subscribeAll { event -> propagateEvent(event) }
        // Вставляем игрока в spatialHash
        spatialHash.insert(state.player)
    }

    fun observe(listener: (GameState) -> Unit) {
        onStateChanged = listener
        listener(state)
    }

    private fun updateState(mutation: GameState.() -> GameState) {
        state = state.mutation()
        onStateChanged?.invoke(state)
    }

    // ── Event propagation ────────────────────────────────────────────────────

    private fun propagateEvent(event: WorldEvent) {
        val nearby = spatialHash.query(event.x, event.y, event.radius)
        nearby.forEach { entity ->
            val dist = entity.distanceTo(event.x, event.y)
            val stimulusStrength = event.intensityAt(dist)
            if (stimulusStrength > 0.05f) {
                wakeEntity(entity, event, stimulusStrength)
            }
        }
        // Игрок тоже реагирует на события
        val playerDist = state.player.distanceTo(event.x, event.y)
        if (event.intensityAt(playerDist) > 0.05f) {
            logMessage("You sense: ${event.type.name.lowercase()} nearby.")
        }
    }

    private fun wakeEntity(entity: Entity, event: WorldEvent, strength: Float) {
        entity.isAwake = true
        val nearby = spatialHash.query(entity.x.toFloat(), entity.y.toFloat(), entity.interestRadius)
        val nearbyItems = state.items.values.filter { item ->
            val dx = (item.x - entity.x).toFloat()
            val dy = (item.y - entity.y).toFloat()
            sqrt(dx * dx + dy * dy) <= entity.interestRadius
        }
        val newBehaviour = BehaviourDecider.decide(
            entity = entity,
            nearbyEntities = nearby.filter { it.id != entity.id },
            nearbyItems = nearbyItems,
            event = event,
        )
        entity.currentBehaviour = newBehaviour
        // Логируем только интересное поведение
        if (newBehaviour !is Behaviour.Wander && newBehaviour !is Behaviour.Idle) {
            logMessage("${entity.name} → ${newBehaviour::class.simpleName}")
        }
        updateState { this }  // триггерим UI
    }

    // ── Public API для ИИ и игрока ───────────────────────────────────────────

    fun moveEntity(entityId: String, dx: Int, dy: Int) {
        updateState {
            if (entityId.equals(player.name, ignoreCase = true)) {
                val oldX = player.x; val oldY = player.y
                val newPlayer = player.apply { x += dx; y += dy }
                spatialHash.move(newPlayer, oldX, oldY)
                // Footstep event от игрока
                eventBus.emit(WorldEvent(WorldEventType.FOOTSTEP, newPlayer.x.toFloat(), newPlayer.y.toFloat(), 0.4f, 96f, "player"))
                copy(player = newPlayer)
            } else {
                val e = entities[entityId] ?: return@updateState this
                val oldX = e.x; val oldY = e.y
                e.x += dx; e.y += dy
                spatialHash.move(e, oldX, oldY)
                copy(entities = entities)
            }
        }
    }

    fun spawnEntity(entity: Entity) {
        spatialHash.insert(entity)
        updateState {
            val newEntities = entities.toMutableMap()
            newEntities[entity.id] = entity
            // Spawn = событие угрозы если враг
            if (entity.hasFlag("ENEMY") || entity.hasFlag("HOSTILE")) {
                eventBus.emit(WorldEvent(WorldEventType.THREAT,
                    entity.x.toFloat(), entity.y.toFloat(), 0.8f, 200f, entity.id))
            }
            copy(entities = newEntities)
        }
    }

    fun applyDamage(targetName: String, amount: Int) {
        updateState {
            if (targetName.equals(player.name, ignoreCase = true)) {
                val died = player.applyDamage(amount)
                if (died) logMessage("${player.name} has fallen!")
                copy(player = player)
            } else {
                val target = entities.values.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
                    ?: return@updateState this
                val died = target.applyDamage(amount)
                if (died) {
                    spatialHash.remove(target)
                    logMessage("${target.name} is defeated!")
                    copy(entities = entities.filter { it.key != target.id },
                         battleLog = battleLog + "${target.name} is defeated!")
                } else {
                    // Враг будит союзников — COMBAT event
                    eventBus.emit(WorldEvent(WorldEventType.COMBAT,
                        target.x.toFloat(), target.y.toFloat(), 0.9f, 256f, target.id))
                    copy(entities = entities)
                }
            }
        }
    }

    fun setFlag(entityId: String, flag: String, value: Boolean) {
        updateState {
            val target = if (entityId.equals(player.name, ignoreCase = true))
                player else entities[entityId] ?: entities.values.firstOrNull { it.name.equals(entityId, ignoreCase = true) }
            target?.let { if (value) it.addFlag(flag) else it.removeFlag(flag) }
            copy(entities = entities.toMap(), player = player) // deep-ish copy для Compose
        }
    }

    /** Массовое применение флага по нескольким условиям — "все гоблины крестьяне празднуют". */
    fun bulkFlag(matchFlags: List<String>, removeGroup: String? = null, addFlags: List<String> = emptyList()) {
        val targets = state.entities.values.filter { e -> matchFlags.all { e.hasFlag(it) } }
        targets.forEach { entity ->
            removeGroup?.let { entity.removeGroup(it) }
            addFlags.forEach { entity.addFlag(it) }
        }
        if (targets.isNotEmpty()) {
            logMessage("${targets.size} entities updated: +${addFlags.joinToString()} ${if (removeGroup != null) "-$removeGroup" else ""}")
            // Социальное событие
            val cx = targets.map { it.x }.average().toFloat()
            val cy = targets.map { it.y }.average().toFloat()
            eventBus.emit(WorldEvent(WorldEventType.CROWD_CHEER, cx, cy, 0.6f, 300f))
        }
        updateState { copy(entities = entities.toMap()) }
    }

    fun dropItem(item: WorldItem) {
        updateState {
            val newItems = items.toMutableMap()
            newItems[item.id] = item
            eventBus.emit(WorldEvent(WorldEventType.ITEM_DROPPED, item.x.toFloat(), item.y.toFloat(), 0.5f, 128f))
            copy(items = newItems)
        }
    }

    fun emitCustomEvent(x: Float, y: Float, radius: Float, intensity: Float, payload: Map<String, Any> = emptyMap()) {
        eventBus.emit(WorldEvent(WorldEventType.CUSTOM, x, y, intensity, radius, payload = payload))
    }

    fun logMessage(msg: String) {
        updateState { copy(battleLog = (battleLog + msg).takeLast(200)) } // кольцевой буфер
    }

    fun transitionMode(mode: GameMode) {
        updateState { copy(mode = mode) }
        logMessage(if (mode == GameMode.BATTLE) "⚔ Battle begins!" else "🌿 Back to the overworld.")
    }

    fun currentState() = state
}
