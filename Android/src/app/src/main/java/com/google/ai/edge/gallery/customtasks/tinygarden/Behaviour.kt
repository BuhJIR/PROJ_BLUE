package com.google.ai.edge.gallery.customtasks.tinygarden

import kotlin.math.sqrt

/**
 * Группы флагов — именованные наборы, можно снимать/добавлять оптом.
 *
 * Пример: FlagGroup("WORK", setOf("HARVEST","CRAFT","BUILD","PATROL"))
 * entity.removeGroup("WORK") снимает все 4 флага разом.
 */
data class FlagGroup(val name: String, val flags: Set<String>)

/**
 * Нужда (Need) — внутреннее состояние entity с приоритетом.
 * PriorityQueue сортирует по убыванию priority.
 * Голод (priority=90) важнее скуки (priority=10).
 */
data class Need(
    val name: String,
    val priority: Int,           // 0..100
    val satisfiedByFlags: Set<String>, // какие флаги нужны для удовлетворения
)

/**
 * Поведение — что entity делает прямо сейчас.
 */
sealed class Behaviour {
    object Idle          : Behaviour()
    object Wander        : Behaviour()
    data class Navigate(val tx: Int, val ty: Int, val reason: String) : Behaviour()
    data class Attack(val targetId: String)                            : Behaviour()
    data class Collect(val itemX: Int, val itemY: Int, val itemType: String) : Behaviour()
    data class Socialize(val targetId: String)                         : Behaviour()
    data class Flee(val fromX: Float, val fromY: Float)                : Behaviour()
    data class Custom(val tag: String, val data: Map<String, Any> = emptyMap()) : Behaviour()
}

/**
 * BehaviourDecider — детерминированный выбор поведения по флагам и нуждам.
 * Вызывается когда entity "просыпается" от события или истекает его sleep-time.
 *
 * Порядок приоритетов (сверху вниз):
 *  1. FLEEING — убегать немедленно
 *  2. COMBAT — атаковать если AGGRESSIVE и есть враг в радиусе
 *  3. Нужды (голод > усталость > ...) — идти к ресурсу
 *  4. Работа — если есть флаги группы WORK
 *  5. CELEBRATE — праздновать
 *  6. Wander — просто гулять в радиусе интереса
 */
object BehaviourDecider {

    fun decide(
        entity: Entity,
        nearbyEntities: List<Entity>,
        nearbyItems: List<WorldItem>,
        event: WorldEvent? = null,
    ): Behaviour {

        // 1. Угроза / взрыв рядом — убегать (если не AGGRESSIVE)
        if (event != null && event.type == WorldEventType.EXPLOSION) {
            if (!entity.hasFlag("AGGRESSIVE") && !entity.hasFlag("FEARLESS")) {
                return Behaviour.Flee(event.x, event.y)
            }
        }

        // 2. FLEEING флаг — override всего
        if (entity.hasFlag("FLEEING")) {
            val threat = nearbyEntities.firstOrNull { it.hasFlag("ENEMY") || it.hasFlag("THREAT") }
            if (threat != null) return Behaviour.Flee(threat.x.toFloat(), threat.y.toFloat())
            else entity.removeFlag("FLEEING") // угрозы нет — успокоиться
        }

        // 3. Боевой контакт — орк вбежал в город
        if (entity.hasFlag("AGGRESSIVE") || entity.hasFlag("GUARD")) {
            val target = nearbyEntities.firstOrNull { e ->
                e.hasFlag("ENEMY") || (entity.hasFlag("GUARD") && e.hasFlag("HOSTILE"))
            }
            if (target != null) return Behaviour.Attack(target.id)
        }

        // 4. Нужды — приоритетная очередь
        entity.needs.sortedByDescending { it.priority }.forEach { need ->
            val canSatisfy = need.satisfiedByFlags.any { entity.hasFlag(it) }
            if (!canSatisfy) {
                // Ищем предмет/место которое удовлетворит нужду
                val item = nearbyItems.firstOrNull { need.satisfiedByFlags.contains(it.type.uppercase()) }
                if (item != null) return Behaviour.Collect(item.x, item.y, item.type)
            }
        }

        // 5. TIRED + HOME → идти домой
        if (entity.hasFlag("TIRED")) {
            val home = entity.memory["home"]
            if (home is Pair<*, *>) {
                return Behaviour.Navigate(home.first as Int, home.second as Int, "rest")
            }
        }

        // 6. FORAGER → искать фрукты/еду в радиусе
        if (entity.hasFlag("FORAGER")) {
            val fruit = nearbyItems.firstOrNull { it.type == "FRUIT" || it.type == "FOOD" }
            if (fruit != null) return Behaviour.Collect(fruit.x, fruit.y, fruit.type)
        }

        // 7. CELEBRATE → праздновать (искать алкоголь или социализироваться)
        if (entity.hasFlag("CELEBRATE")) {
            val drink = nearbyItems.firstOrNull { it.type == "ALCOHOL" || it.type == "DRINK" }
            if (drink != null) return Behaviour.Collect(drink.x, drink.y, drink.type)
            val friend = nearbyEntities.firstOrNull { it.id != entity.id && it.hasFlag("CELEBRATE") }
            if (friend != null) return Behaviour.Socialize(friend.id)
        }

        // 8. Default — гулять в радиусе интереса
        return Behaviour.Wander
    }
}

/** Предмет в мире (фрукт, алкоголь, оружие...) */
data class WorldItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: String,   // "FRUIT", "ALCOHOL", "WEAPON", ...
    val x: Int,
    val y: Int,
    var quantity: Int = 1,
)
