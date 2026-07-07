package com.google.ai.edge.gallery.customtasks.tinygarden

import android.util.Log
import java.util.UUID
import kotlin.math.sqrt
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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
    fun registeredGroups(): List<FlagGroup> = flagGroups.values.toList()
    fun addGroup(groupName: String) { flagGroups[groupName.uppercase()]?.flags?.forEach { addFlag(it) } }
    fun removeGroup(groupName: String) { flagGroups[groupName.uppercase()]?.flags?.forEach { removeFlag(it) } }
    fun hasGroup(groupName: String): Boolean =
        flagGroups[groupName.uppercase()]?.flags?.any { hasFlag(it) } ?: false
}

/**
 * Типизированное значение в памяти Entity.
 * Sealed-обёртка вместо Any: без unchecked-кастов и сериализуется в JSON (SPEC §3, §9).
 */
sealed class MemoryValue {
    data class Str(val v: String) : MemoryValue()
    data class Num(val v: Double) : MemoryValue()
    data class Coord(val col: Int, val row: Int) : MemoryValue()
    data class Bool(val v: Boolean) : MemoryValue()
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
    id: String = UUID.randomUUID().toString(),  // стабилен через save/load (SPEC §3)
) : GameObject(id = id, name = name) {
    // Изометрические координаты (алиасы для ясности в рендерере)
    var col: Int get() = x; set(v) { x = v }
    var row: Int get() = y; set(v) { y = v }

    val needs: MutableList<Need> = mutableListOf()
    val memory: MutableMap<String, MemoryValue> = mutableMapOf()
    var currentBehaviour: Behaviour = Behaviour.Idle
    var isAwake: Boolean = true
    // Сколько клеток NPC проходит за одно пробуждение (прямо или L-образно).
    // Расширяемо: быстрые твари могут получить больше, раненые — меньше.
    var moveRange: Int = 4

    // Типобезопасные читатели памяти — единственный способ достать значение
    fun memoryString(key: String): String? = (memory[key] as? MemoryValue.Str)?.v
    fun memoryNum(key: String): Double? = (memory[key] as? MemoryValue.Num)?.v
    fun memoryCoord(key: String): Pair<Int, Int>? =
        (memory[key] as? MemoryValue.Coord)?.let { it.col to it.row }
    fun memoryBool(key: String): Boolean? = (memory[key] as? MemoryValue.Bool)?.v

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

/** Закон мира по умолчанию — то, что каждый NPC считает истиной (SPEC §19.2). */
const val DEFAULT_WORLD_LAW =
    "A world of quiet fields and old stone. The sun rises and sets as it always has."

/** Глобальный State — иммутабельный снимок мира для Compose. */
data class GameState(
    val mode: GameMode = GameMode.OVERWORLD,
    val player: Entity = Entity("Hero", 100, 100, 50, 50, x = 5, y = 5),
    val entities: Map<String, Entity> = emptyMap(),
    val items: Map<String, WorldItem> = emptyMap(),
    val battleLog: List<String> = emptyList(),
    val turn: Int = 0,
    // Сестра, чья грань выпала последней — активный голос/актёр (SPEC §15)
    val activeSister: Sister? = null,
    // Закон мира: глобальная, исключающая истина; переписывается редко (SPEC §19.2)
    val worldLaw: String = DEFAULT_WORLD_LAW,
    // Локальный, аддитивный лор поселений: ключ — id региона/поселения
    val settlementLore: Map<String, String> = emptyMap(),
    // Seed мира — «NEW GAME» генерирует новый, сейв хранит свой
    val worldSeed: Long = IsoMap.WORLD_SEED,
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
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var state = GameState()
    private var onStateChanged: ((GameState) -> Unit)? = null

    val spatialHash = SpatialHash(cellSize = 64f)
    val eventBus = EventBus()

    // true после первой попытки загрузки сохранения — engine синглтон, а
    // ViewModel пересоздаётся; повторная загрузка затёрла бы живую сессию (SPEC §3)
    var restoreAttempted: Boolean = false

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
                // Сбой одного entity не должен убивать всю волну (SPEC §9)
                runCatching { wakeEntity(entity, event, stimulusStrength) }
                    .onFailure { Log.e("GameEngine", "wakeEntity failed for ${entity.name}", it) }
            }
        }
        // Игрок тоже реагирует на события
        val playerDist = state.player.distanceTo(event.x, event.y)
        if (event.intensityAt(playerDist) > 0.05f) {
            logMessage("You sense: ${event.type.name.lowercase()} nearby.")
        }
    }

    // Guard от каскадной рекурсии: A бьёт B → COMBAT-событие будит A снова.
    // Пока entity исполняет поведение, повторное пробуждение только обновляет
    // currentBehaviour, но не запускает второй execute (SPEC §2).
    private val executingBehaviours = mutableSetOf<String>()

    // Профили значимых NPC — только они эскалируют до микро-агента (SPEC §19.3)
    val npcProfiles = HashMap<String, NpcAgentProfile>()

    fun registerNpcProfile(profile: NpcAgentProfile) {
        npcProfiles[profile.entityId] = profile
    }

    private fun wakeEntity(entity: Entity, event: WorldEvent, strength: Float) {
        entity.isAwake = true
        val nearby = spatialHash.query(entity.x.toFloat(), entity.y.toFloat(), entity.interestRadius)
            .filter { it.id != entity.id }
        val nearbyItems = state.items.values.filter { item ->
            val dx = (item.x - entity.x).toFloat()
            val dy = (item.y - entity.y).toFloat()
            sqrt(dx * dx + dy * dy) <= entity.interestRadius
        }
        val profile = npcProfiles[entity.id]
        if (profile?.usesMicroAgent == true) {
            // Значимый NPC — собственный момент мысли, асинхронно; при любом
            // сбое NpcAgentRunner сам откатывается на детерминированный путь
            scope.launch {
                val decided = NpcAgentRunner.resolveBehaviour(
                    entity, profile, this@GameEngine, nearby, nearbyItems, event,
                )
                applyDecidedBehaviour(entity, decided)
            }
        } else {
            applyDecidedBehaviour(
                entity,
                BehaviourDecider.decide(entity, nearby, nearbyItems, event),
            )
        }
    }

    private fun applyDecidedBehaviour(entity: Entity, newBehaviour: Behaviour) {
        entity.currentBehaviour = newBehaviour
        // Логируем только интересное поведение
        if (newBehaviour !is Behaviour.Wander && newBehaviour !is Behaviour.Idle) {
            logMessage("${entity.name} → ${newBehaviour::class.simpleName}")
        }
        // Решение → действие. Без этого вызова все Behaviour — тупики (SPEC §2).
        if (executingBehaviours.add(entity.id)) {
            try {
                BehaviourExecutor.execute(entity, this)
            } catch (e: Exception) {
                Log.e("GameEngine", "behaviour execution failed for ${entity.name}", e)
            } finally {
                executingBehaviours.remove(entity.id)
            }
        }
        updateState { this }  // триггерим UI
    }

    // ── Резолверы для актуатора и инструментов ───────────────────────────────

    /** Находит entity по id или имени; игрок включён. */
    fun resolveEntity(idOrName: String): Entity? {
        val p = state.player
        if (idOrName == p.id || idOrName.equals(p.name, ignoreCase = true)) return p
        return state.entities[idOrName]
            ?: state.entities.values.firstOrNull { it.name.equals(idOrName, ignoreCase = true) }
    }

    /** Занятые клетки (entity + игрок), кроме перечисленных id. */
    fun occupiedCells(except: Set<String> = emptySet()): Set<Pair<Int, Int>> {
        val cells = mutableSetOf<Pair<Int, Int>>()
        if (state.player.id !in except) cells.add(state.player.col to state.player.row)
        state.entities.values.forEach { if (it.id !in except) cells.add(it.col to it.row) }
        return cells
    }

    // ── Public API для ИИ и игрока ───────────────────────────────────────────

    fun moveEntity(entityId: String, dx: Int, dy: Int) {
        // События эмитим ПОСЛЕ завершения мутации: emit внутри лямбды updateState
        // запускает вложенные мутации, результат которых внешняя лямбда затирает.
        var footstep: WorldEvent? = null
        updateState {
            if (entityId.equals(player.name, ignoreCase = true) || entityId == player.id) {
                val oldX = player.x; val oldY = player.y
                val newPlayer = player.apply {
                    x += dx; y += dy
                    // Обновляем направление для рендерера
                    memory["direction"] = MemoryValue.Str(when {
                        dx > 0 && dy == 0 -> "EAST"
                        dx < 0 && dy == 0 -> "WEST"
                        dy < 0            -> "NORTH"
                        else              -> "SOUTH"
                    })
                }
                spatialHash.move(newPlayer, oldX, oldY)
                footstep = WorldEvent(WorldEventType.FOOTSTEP, newPlayer.x.toFloat(), newPlayer.y.toFloat(), 0.4f, 96f, "player")
                copy(player = newPlayer)
            } else {
                val e = entities[entityId] ?: return@updateState this
                val oldX = e.x; val oldY = e.y
                e.x += dx; e.y += dy
                e.memory["direction"] = MemoryValue.Str(when {
                    dx > 0 && dy == 0 -> "EAST"
                    dx < 0 && dy == 0 -> "WEST"
                    dy < 0            -> "NORTH"
                    else              -> "SOUTH"
                })
                spatialHash.move(e, oldX, oldY)
                copy(entities = entities)
            }
        }
        footstep?.let { eventBus.emit(it) }
    }

    fun spawnEntity(entity: Entity) {
        spatialHash.insert(entity)
        updateState {
            val newEntities = entities.toMutableMap()
            newEntities[entity.id] = entity
            copy(entities = newEntities)
        }
        // Spawn = событие угрозы если враг — после мутации, не внутри
        if (entity.hasFlag("ENEMY") || entity.hasFlag("HOSTILE")) {
            eventBus.emit(WorldEvent(WorldEventType.THREAT,
                entity.x.toFloat(), entity.y.toFloat(), 0.8f, 200f, entity.id))
        }
    }

    fun applyDamage(targetName: String, amount: Int) {
        var combatEvent: WorldEvent? = null
        updateState {
            if (targetName.equals(player.name, ignoreCase = true) || targetName == player.id) {
                val died = player.applyDamage(amount)
                if (died) copy(player = player, battleLog = (battleLog + "${player.name} has fallen!").takeLast(200))
                else copy(player = player)
            } else {
                val target = entities[targetName]
                    ?: entities.values.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
                    ?: return@updateState this
                val died = target.applyDamage(amount)
                if (died) {
                    spatialHash.remove(target)
                    copy(entities = entities.filter { it.key != target.id },
                         battleLog = (battleLog + "${target.name} is defeated!").takeLast(200))
                } else {
                    // Враг будит союзников — COMBAT event (эмитится после мутации)
                    combatEvent = WorldEvent(WorldEventType.COMBAT,
                        target.x.toFloat(), target.y.toFloat(), 0.9f, 256f, target.id)
                    copy(entities = entities)
                }
            }
        }
        combatEvent?.let { eventBus.emit(it) }
    }

    /**
     * Подбор предмета с клетки (x,y). Уменьшает quantity, убирает предмет при 0
     * и помечает сборщика флагом типа предмета — так Need считается удовлетворённой
     * (BehaviourDecider ищет satisfiedByFlags среди флагов entity).
     */
    fun pickUpItemAt(x: Int, y: Int, type: String, by: Entity) {
        updateState {
            val item = items.values.firstOrNull {
                it.x == x && it.y == y && it.type.equals(type, ignoreCase = true)
            } ?: return@updateState this
            item.quantity -= 1
            by.addFlag(item.type.uppercase())
            val msg = "${by.name} picks up ${item.type.lowercase()}."
            val newItems = if (item.quantity <= 0) items.filter { it.key != item.id } else items
            copy(items = newItems, battleLog = (battleLog + msg).takeLast(200))
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
            copy(items = newItems)
        }
        eventBus.emit(WorldEvent(WorldEventType.ITEM_DROPPED, item.x.toFloat(), item.y.toFloat(), 0.5f, 128f))
    }

    fun emitCustomEvent(x: Float, y: Float, radius: Float, intensity: Float, payload: Map<String, Any> = emptyMap()) {
        eventBus.emit(WorldEvent(WorldEventType.CUSTOM, x, y, intensity, radius, payload = payload))
    }

    fun logMessage(msg: String) {
        updateState { copy(battleLog = (battleLog + msg).takeLast(200)) } // кольцевой буфер
    }

    /** Ход мира: каждый обмен с Душой двигает время — цветы растут и увядают. */
    fun advanceTurn() {
        updateState { copy(turn = turn + 1) }
    }

    fun transitionMode(mode: GameMode) {
        updateState { copy(mode = mode) }
        logMessage(if (mode == GameMode.BATTLE) "⚔ Battle begins!" else "🌿 Back to the overworld.")
    }

    /** Выпавшая грань кубика назначает активную Сестру (SPEC §15). */
    fun setActiveSister(sister: Sister) {
        updateState { copy(activeSister = sister) }
        logMessage("The die settles on ${sister.face}. ${sister.displayName} takes the fore — ${sister.principle.display.lowercase()}.")
    }

    /**
     * Переписывает закон мира (SPEC §19.2). Единственная точка мутации.
     * Каждый NPC-агент увидит новый закон как ground truth со следующего
     * пробуждения; волна WORLD_LAW_CHANGE будит всех немедленно.
     */
    fun setWorldLaw(newLaw: String) {
        updateState { copy(worldLaw = newLaw) }
        logMessage("Reality shifts: $newLaw")
        eventBus.emit(WorldEvent(WorldEventType.CUSTOM, 0f, 0f,
            intensity = 1f, radius = Float.MAX_VALUE,
            payload = mapOf("type" to "WORLD_LAW_CHANGE")))
    }

    /** Локальный лор поселения — аддитивный, в отличие от глобального закона. */
    fun setSettlementLore(settlementId: String, lore: String) {
        updateState { copy(settlementLore = settlementLore + (settlementId to lore)) }
    }

    fun currentState() = state

    /** Отменяет все корутины движка (executePath и т.п.). После вызова engine мёртв. */
    fun shutdown() {
        scope.cancel()
    }

    /**
     * Восстановление мира из сохранения (SPEC §3): состояние, постройки,
     * пере-заселение spatialHash. worldMap держит ссылку на structureOverrides,
     * поэтому clear+putAll сохраняет её валидной.
     */
    fun restore(newState: GameState, overrides: Map<Pair<Int, Int>, LayeredTileEx>) {
        spatialHash.clear()
        structureOverrides.clear()
        structureOverrides.putAll(overrides)
        spatialHash.insert(newState.player)
        newState.entities.values.forEach { spatialHash.insert(it) }
        worldMap = generateMapAround(newState.player.col, newState.player.row,
            overrides = structureOverrides, seed = newState.worldSeed)
        updateState { newState }
        logMessage("World restored — ${overrides.size} built tiles, ${newState.entities.size} souls.")
    }

    // ── Структуры (зиккураты, здания) от StructureDSL ─────────────────────────
    // Единственный источник правды о постройках. IsoMap держит ссылку на эту
    // же HashMap — applyStructure видна tileAt/isWalkable/Pathfinder мгновенно (SPEC §4/§5).
    val structureOverrides = HashMap<Pair<Int,Int>, LayeredTileEx>()

    /**
     * Живая карта мира. Рендерер пересобирает буфер вокруг игрока и передаёт
     * сюда — Pathfinder и BehaviourExecutor всегда работают с актуальной картой.
     */
    var worldMap: IsoMap = generateMapAround(0, 0, overrides = structureOverrides, seed = state.worldSeed)
        private set

    fun updateWorldMap(map: IsoMap) {
        worldMap = map
    }

    /**
     * Новый мир: свежий seed, чистые постройки и entity, игрок в начале.
     * Разговорная память Души этим не трогается — она живёт в модели.
     */
    fun newGame(seed: Long) {
        spatialHash.clear()
        structureOverrides.clear()
        npcProfiles.clear()
        val fresh = GameState(worldSeed = seed)
        spatialHash.insert(fresh.player)
        worldMap = generateMapAround(fresh.player.col, fresh.player.row,
            overrides = structureOverrides, seed = seed)
        updateState { fresh }
        logMessage("A new world breathes. Seed: $seed")
        populateAmbient()
    }

    /**
     * Эмбиентное заселение: мир не должен стартовать пустым. Существа
     * рассаживаются детерминированно от seed — черепа агрессивны, бесы
     * фуражируют. Имена содержат "skull"/"red" — spritePath подхватит листы.
     */
    fun populateAmbient() {
        val rng = kotlin.random.Random(state.worldSeed)
        val p = state.player
        var spawned = 0
        var attempts = 0
        while (spawned < 5 && attempts < 80) {
            attempts++
            val dx = rng.nextInt(-20, 21)
            val dy = rng.nextInt(-20, 21)
            if (abs(dx) < 6 && abs(dy) < 6) continue  // не спавнимся игроку в лицо
            val x = p.x + dx
            val y = p.y + dy
            if (!worldMap.isWalkable(x, y)) continue
            if (occupiedCells().contains(x to y)) continue
            val hostile = spawned % 2 == 0
            val e = if (hostile) {
                Entity(name = "Skeleton Mage", hp = 30, maxHp = 30, x = x, y = y).apply {
                    addFlag("ENEMY"); addFlag("AGGRESSIVE")
                }
            } else {
                Entity(name = "Skeleton Acolyte", hp = 14, maxHp = 14, x = x, y = y).apply {
                    addFlag("FORAGER")
                    needs.add(Need("HUNGER", 80, setOf("FRUIT", "FOOD")))
                }
            }
            spawnEntity(e)
            spawned++
        }
        if (spawned > 0) logMessage("The world stirs — $spawned creatures roam nearby.")
    }

    fun applyStructure(tiles: List<Triple<Int, Int, LayeredTileEx>>) {
        tiles.forEach { (c, r, t) -> structureOverrides[c to r] = t }
        logMessage("A structure rises from the ground — ${tiles.size} tiles reshaped.")
        updateState { this }
    }

    fun structureTileAt(col: Int, row: Int): LayeredTileEx? = structureOverrides[col to row]

    // ── Выбор тайла и построение пути ────────────────────────────────────────

    var selectedTile: Pair<Int,Int>? = null
        private set

    var currentPath: List<Pathfinder.Step> = emptyList()
        private set

    fun selectTile(col: Int, row: Int, map: IsoMap) {
        val blocked = state.entities.values.map { it.col to it.row }.toSet()
        // Активная Сестра ходит своей фигурой; без неё — обычный 4-way шаг (SPEC §19.4)
        val pattern = state.activeSister?.currentPattern ?: MovementPattern.Walker
        val path = Pathfinder.findPath(
            state.player.col, state.player.row,
            col, row, map, blocked, pattern
        )
        selectedTile = col to row
        currentPath = path ?: emptyList()
        updateState { this }
    }

    fun clearSelection() {
        selectedTile = null
        currentPath = emptyList()
        updateState { this }
    }

    // ── Анимированное перемещение по пути ────────────────────────────────────

    fun executePath(
        entityId: String,
        path: List<Pathfinder.Step>,
        msPerStep: Long = 180L,
        onDone: (() -> Unit)? = null,
    ) {
        if (path.isEmpty()) { onDone?.invoke(); return }
        scope.launch {
            var prevStep: Pathfinder.Step? = null
            path.forEach { step ->
                val entity = if (entityId.equals(state.player.name, ignoreCase = true) ||
                                 entityId == state.player.id) state.player
                             else state.entities.values.firstOrNull {
                                 it.name.equals(entityId, ignoreCase = true) || it.id == entityId
                             }
                entity?.let {
                    val dir = prevStep?.let { p -> Pathfinder.stepDirection(p, step) } ?: "SOUTH"
                    it.memory["direction"] = MemoryValue.Str(dir)
                    moveEntity(entityId, step.col - it.col, step.row - it.row)
                }
                prevStep = step
                delay(msPerStep)
            }
            checkPromotion(entityId)
            clearSelection()
            onDone?.invoke()
        }
    }

    /**
     * Промоушен Five (SPEC §19.5): пешка, дошедшая до края света, становится
     * ферзём — постоянная смена currentPattern, не разовый тумблер.
     */
    private fun checkPromotion(entityId: String) {
        val sister = state.activeSister ?: return
        if (sister.currentPattern !is MovementPattern.Pawn) return
        val p = state.player
        if (entityId != p.id && !entityId.equals(p.name, ignoreCase = true)) return
        if (PromotionZones.isPromotionZone(p.col, p.row, worldMap)) {
            sister.currentPattern = MovementPattern.Queen
            logMessage("${sister.displayName} reaches the edge of the world — and is transformed. She moves as a queen now.")
            updateState { this }
        }
    }
}
