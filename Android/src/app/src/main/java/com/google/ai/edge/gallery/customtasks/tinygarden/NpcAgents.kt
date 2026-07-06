package com.google.ai.edge.gallery.customtasks.tinygarden

/**
 * NPC-микроагенты — изолированные Edge AI вызовы без истории чата (SPEC §19.3).
 *
 * Значимые NPC думают сами: каждый вызов получает свежесобранный промпт —
 * worldLaw + лор поселения + собственная роль + текущее восприятие. Никакой
 * истории разговора игрока: Душа помнит, NPC — нет.
 *
 * Детерминированный BehaviourDecider остаётся дешёвым fallback-путём: фоновые
 * персонажи не платят за инференс, и любой сбой/отсутствие модели откатывается
 * на него. Это performance-решение из спеки, не afterthought.
 */
data class NpcAgentProfile(
    val entityId: String,
    val localRole: String,               // "You are a blacksmith. Metal matters to you above all else."
    val usesMicroAgent: Boolean = false, // false → всегда BehaviourDecider (дешёвый путь)
)

object NpcAgentRunner {

    /**
     * Подключаемый короткий historyless-вызов on-device модели.
     * Пока не подключён (null) — все агенты работают на детерминированном пути.
     * Владелец модели (задача/ViewModel) устанавливает его при инициализации.
     */
    @Volatile
    var inference: (suspend (prompt: String) -> String)? = null

    /**
     * Изолированный промпт → короткий инференс → Behaviour.
     * Любая ошибка/таймаут/отсутствие модели → BehaviourDecider — детерминированный
     * путь всегда страхует.
     */
    suspend fun resolveBehaviour(
        entity: Entity,
        profile: NpcAgentProfile,
        engine: GameEngine,
        nearbyEntities: List<Entity>,
        nearbyItems: List<WorldItem>,
        event: WorldEvent?,
    ): Behaviour {
        if (!profile.usesMicroAgent) {
            return BehaviourDecider.decide(entity, nearbyEntities, nearbyItems, event)
        }
        val infer = inference
            ?: return BehaviourDecider.decide(entity, nearbyEntities, nearbyItems, event)
        val prompt = buildPrompt(entity, profile, engine, nearbyEntities, nearbyItems, event)
        return runCatching {
            parseBehaviour(infer(prompt), entity, nearbyEntities, nearbyItems)
        }.getOrNull()
            ?: BehaviourDecider.decide(entity, nearbyEntities, nearbyItems, event)
    }

    /** Промпт собирается заново на каждое пробуждение — истории нет по построению. */
    fun buildPrompt(
        entity: Entity,
        profile: NpcAgentProfile,
        engine: GameEngine,
        nearbyEntities: List<Entity>,
        nearbyItems: List<WorldItem>,
        event: WorldEvent?,
    ): String = buildString {
        val state = engine.currentState()
        appendLine(state.worldLaw)
        entity.memoryString("settlement")?.let { settlement ->
            state.settlementLore[settlement]?.let { appendLine(it) }
        }
        appendLine(profile.localRole)
        appendLine("You perceive: ${describePerception(nearbyEntities, nearbyItems, event)}")
        appendLine(
            "Respond with ONE short action, exactly one line. Choose from: " +
            "ATTACK <name> | FLEE | GOTO <x> <y> | COLLECT | WANDER | IDLE"
        )
    }

    private fun describePerception(
        nearbyEntities: List<Entity>,
        nearbyItems: List<WorldItem>,
        event: WorldEvent?,
    ): String {
        val parts = mutableListOf<String>()
        event?.let { parts.add("${it.type.name.lowercase()} nearby") }
        nearbyEntities.take(5).forEach {
            parts.add("${it.name} at (${it.x},${it.y})${if (it.hasFlag("ENEMY")) " [enemy]" else ""}")
        }
        nearbyItems.take(3).forEach { parts.add("${it.type.lowercase()} at (${it.x},${it.y})") }
        return if (parts.isEmpty()) "nothing of note" else parts.joinToString("; ")
    }

    /**
     * Короткий ответ модели → Behaviour. null при непонятном ответе,
     * чтобы вызвавший откатился на детерминированный путь.
     */
    fun parseBehaviour(
        response: String,
        entity: Entity,
        nearbyEntities: List<Entity>,
        nearbyItems: List<WorldItem>,
    ): Behaviour? {
        val line = response.trim().lines().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val upper = line.uppercase()
        return when {
            upper.startsWith("ATTACK") -> {
                val name = line.substringAfter(' ', "").trim()
                val target = nearbyEntities.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: nearbyEntities.firstOrNull { it.hasFlag("ENEMY") }
                target?.let { Behaviour.Attack(it.id) }
            }
            upper.startsWith("FLEE") -> {
                val threat = nearbyEntities.firstOrNull { it.hasFlag("ENEMY") || it.hasFlag("THREAT") }
                Behaviour.Flee(
                    threat?.x?.toFloat() ?: entity.x.toFloat(),
                    threat?.y?.toFloat() ?: entity.y.toFloat(),
                )
            }
            upper.startsWith("GOTO") -> {
                val nums = Regex("-?\\d+").findAll(line).map { it.value.toInt() }.toList()
                if (nums.size >= 2) Behaviour.Navigate(nums[0], nums[1], "agent") else null
            }
            upper.startsWith("COLLECT") -> {
                nearbyItems.firstOrNull()?.let { Behaviour.Collect(it.x, it.y, it.type) }
            }
            upper.startsWith("WANDER") -> Behaviour.Wander
            upper.startsWith("IDLE") || upper.startsWith("WAIT") -> Behaviour.Idle
            else -> null
        }
    }
}
