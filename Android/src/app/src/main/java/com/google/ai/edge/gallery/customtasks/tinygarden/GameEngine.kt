package com.google.ai.edge.gallery.customtasks.tinygarden

import java.util.UUID

/** 
 * Базовый класс для всего в игре.
 * Любой объект может иметь флаги (теги), которые ИИ может устанавливать/снимать.
 */
abstract class GameObject(
    val id: String = UUID.randomUUID().toString(),
    var name: String
) {
    val flags: MutableSet<String> = mutableSetOf()
    
    fun addFlag(flag: String) = flags.add(flag.uppercase())
    fun removeFlag(flag: String) = flags.remove(flag.uppercase())
    fun hasFlag(flag: String) = flags.contains(flag.uppercase())
}

/** Сущности (Герой, Враги, NPC) */
class Entity(
    name: String,
    var hp: Int,
    var maxHp: Int,
    var mp: Int = 0,
    var maxMp: Int = 0,
    var x: Int = 0,
    var y: Int = 0
) : GameObject(name = name)

enum class GameMode { OVERWORLD, BATTLE }

/** Единый глобальный State игры */
data class GameState(
    val mode: GameMode = GameMode.OVERWORLD,
    val player: Entity = Entity("Hero", 100, 100, 50, 50, x = 5, y = 5),
    val entities: Map<String, Entity> = emptyMap(),
    val battleLog: List<String> = emptyList()
) {
    // Хелпер для получения врагов на карте или в бою
    fun getEnemies() = entities.values.filter { it.hasFlag("ENEMY") }
}

/** 
 * Ядро (Движок) - принимает чистые команды, модифицирует State
 */
class GameEngine {
    private var state = GameState()
    private var onStateChanged: ((GameState) -> Unit)? = null

    fun observe(listener: (GameState) -> Unit) {
        onStateChanged = listener
        listener(state)
    }

    private fun updateState(mutation: GameState.() -> GameState) {
        state = state.mutation()
        onStateChanged?.invoke(state)
    }

    // --- API для ИИ (Tool Calling / Pure JSON) ---
    
    fun moveEntity(entityId: String, dx: Int, dy: Int) {
        updateState {
            if (entityId.equals(player.name, ignoreCase = true)) {
                copy(player = player.apply { x += dx; y += dy })
            } else {
                val e = entities[entityId] ?: return@updateState this
                val newEntities = entities.toMutableMap()
                newEntities[entityId] = e.apply { x += dx; y += dy }
                copy(entities = newEntities)
            }
        }
    }

    fun spawnEntity(entity: Entity) {
        updateState {
            val newEntities = entities.toMutableMap()
            newEntities[entity.id] = entity
            copy(entities = newEntities)
        }
    }

    fun setFlag(entityId: String, flag: String, value: Boolean) {
        updateState {
            val target = if (entityId.equals(player.name, ignoreCase = true)) player else entities[entityId]
            target?.let { 
                if (value) it.addFlag(flag) else it.removeFlag(flag) 
            }
            this // State мутировал объекты внутри, для Compose лучше делать глубокие копии, но пока сойдет
        }
    }

    fun logMessage(msg: String) {
        updateState { copy(battleLog = battleLog + msg) }
    }
}
