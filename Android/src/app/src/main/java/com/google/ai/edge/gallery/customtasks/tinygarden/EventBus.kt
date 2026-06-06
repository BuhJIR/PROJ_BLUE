package com.google.ai.edge.gallery.customtasks.tinygarden

/**
 * Типы событий в мире.
 * Каждое событие — волна давления с источником, интенсивностью и радиусом.
 */
enum class WorldEventType {
    EXPLOSION,      // взрыв — будит всех в радиусе
    FOOTSTEP,       // шаги — будит осторожных
    COMBAT,         // бой — будит агрессивных и трусливых
    ITEM_DROPPED,   // предмет упал — будит forager-ов
    CROWD_CHEER,    // праздник — социальные entity реагируют
    THREAT,         // угроза — общий триггер страха
    CUSTOM,         // расширяемый тип для ИИ
}

data class WorldEvent(
    val type: WorldEventType,
    val x: Float,
    val y: Float,
    val intensity: Float = 1f,       // 0..1, затухает с расстоянием
    val radius: Float = 128f,        // пиксели / grid units
    val sourceId: String? = null,    // кто вызвал
    val payload: Map<String, Any> = emptyMap(), // произвольные данные
) {
    /** Интенсивность события на расстоянии r от источника (обратный квадрат). */
    fun intensityAt(r: Float): Float =
        if (r >= radius) 0f
        else intensity * (1f - (r / radius) * (r / radius))
}

/**
 * EventBus — шина мировых событий.
 * GameEngine публикует, Entity подписываются через wakeConditions.
 * Всё синхронно внутри движка — coroutine-диспатч на уровне выше.
 */
class EventBus {
    private val listeners = HashMap<WorldEventType, MutableList<(WorldEvent) -> Unit>>()

    fun subscribe(type: WorldEventType, listener: (WorldEvent) -> Unit) {
        listeners.getOrPut(type) { mutableListOf() }.add(listener)
    }

    fun subscribeAll(listener: (WorldEvent) -> Unit) {
        WorldEventType.entries.forEach { subscribe(it, listener) }
    }

    fun emit(event: WorldEvent) {
        listeners[event.type]?.forEach { it(event) }
    }

    fun clear() = listeners.clear()
}
