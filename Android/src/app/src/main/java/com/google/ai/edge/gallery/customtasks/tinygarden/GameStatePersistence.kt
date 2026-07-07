package com.google.ai.edge.gallery.customtasks.tinygarden

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Персистентность мира (SPEC §3).
 *
 * Мир, который не переживает телефонный звонок, противоречит собственной
 * заявке "мир живёт сам". Полный JSON-снимок GameState — позиции и память
 * entity, флаги/группы/нужды, постройки, закон мира, лор поселений,
 * состояние Сестёр — пишется в файл с debounce'ом и читается на старте.
 *
 * Не сохраняется (сознательно): currentBehaviour (транзиентно, восстановится
 * первым же событием) и история разговора Души (живёт в состоянии модели,
 * а не в мире — отдельная задача поверх LlmChatModelHelper).
 */
object GameStatePersistence {

    const val VERSION = 1
    const val SAVE_FILE_NAME = "tinygarden_save.json"

    // ── Save ─────────────────────────────────────────────────────────────────

    fun save(engine: GameEngine, file: File): Boolean = runCatching {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson(engine.currentState(), engine.structureOverrides))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    fun toJson(state: GameState, overrides: Map<Pair<Int, Int>, LayeredTileEx>): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("mode", state.mode.name)
        root.put("turn", state.turn)
        root.put("worldSeed", state.worldSeed)
        root.put("worldLaw", state.worldLaw)
        root.put("settlementLore", JSONObject(state.settlementLore as Map<*, *>))
        state.activeSister?.let { root.put("activeSisterId", it.id) }
        root.put("player", entityToJson(state.player))
        root.put("entities", JSONArray().apply {
            state.entities.values.forEach { put(entityToJson(it)) }
        })
        root.put("items", JSONArray().apply {
            state.items.values.forEach { item ->
                put(JSONObject()
                    .put("id", item.id).put("type", item.type)
                    .put("x", item.x).put("y", item.y)
                    .put("quantity", item.quantity))
            }
        })
        root.put("battleLog", JSONArray(state.battleLog))
        root.put("structures", JSONArray().apply {
            overrides.forEach { (pos, tile) ->
                val o = JSONObject()
                    .put("col", pos.first).put("row", pos.second)
                    .put("base", tile.base.name).put("height", tile.height)
                tile.stair?.let { s ->
                    o.put("stair", JSONObject()
                        .put("from", s.fromHeight).put("to", s.toHeight)
                        .put("facing", s.facing.name))
                }
                put(o)
            }
        })
        // Мутабельное состояние Сестёр: партия и текущая фигура (промоушен перманентен)
        root.put("sisters", JSONArray().apply {
            Sisters.ALL.forEach { s ->
                put(JSONObject()
                    .put("id", s.id)
                    .put("isCompanion", s.isCompanion)
                    .put("pattern", patternToString(s.currentPattern)))
            }
        })
        return root.toString()
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    fun load(engine: GameEngine, file: File): Boolean = runCatching {
        if (!file.exists()) return false
        val root = JSONObject(file.readText())
        if (root.optInt("version", 0) > VERSION) return false

        root.optJSONArray("sisters")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                Sisters.byId(o.getString("id"))?.apply {
                    isCompanion = o.optBoolean("isCompanion", isCompanion)
                    currentPattern = patternFromString(o.optString("pattern"), currentPattern)
                }
            }
        }

        val entities = mutableMapOf<String, Entity>()
        root.optJSONArray("entities")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = entityFromJson(arr.getJSONObject(i))
                entities[e.id] = e
            }
        }
        val items = mutableMapOf<String, WorldItem>()
        root.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val item = WorldItem(
                    id = o.getString("id"), type = o.getString("type"),
                    x = o.getInt("x"), y = o.getInt("y"),
                    quantity = o.optInt("quantity", 1),
                )
                items[item.id] = item
            }
        }
        val battleLog = mutableListOf<String>()
        root.optJSONArray("battleLog")?.let { arr ->
            for (i in 0 until arr.length()) battleLog.add(arr.getString(i))
        }
        val lore = mutableMapOf<String, String>()
        root.optJSONObject("settlementLore")?.let { o ->
            o.keys().forEach { k -> lore[k] = o.getString(k) }
        }
        val overrides = mutableMapOf<Pair<Int, Int>, LayeredTileEx>()
        root.optJSONArray("structures")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val stair = o.optJSONObject("stair")?.let { s ->
                    StairInfo(
                        s.getInt("from"), s.getInt("to"),
                        StepDirection.valueOf(s.getString("facing")),
                    )
                }
                overrides[o.getInt("col") to o.getInt("row")] =
                    LayeredTileEx(TileType.valueOf(o.getString("base")), o.getInt("height"), stair)
            }
        }

        val state = GameState(
            mode = runCatching { GameMode.valueOf(root.optString("mode")) }.getOrDefault(GameMode.OVERWORLD),
            player = root.optJSONObject("player")?.let { entityFromJson(it) } ?: GameState().player,
            entities = entities,
            items = items,
            battleLog = battleLog,
            turn = root.optInt("turn", 0),
            activeSister = root.optString("activeSisterId").takeIf { it.isNotEmpty() }
                ?.let { Sisters.byId(it) },
            worldLaw = root.optString("worldLaw").ifEmpty { DEFAULT_WORLD_LAW },
            settlementLore = lore,
            worldSeed = root.optLong("worldSeed", IsoMap.WORLD_SEED),
        )
        engine.restore(state, overrides)
        true
    }.getOrDefault(false)

    // ── Entity ↔ JSON ────────────────────────────────────────────────────────

    private fun entityToJson(e: Entity): JSONObject {
        val o = JSONObject()
            .put("id", e.id).put("name", e.name)
            .put("hp", e.hp).put("maxHp", e.maxHp)
            .put("mp", e.mp).put("maxMp", e.maxMp)
            .put("x", e.x).put("y", e.y)
            .put("interestRadius", e.interestRadius.toDouble())
        o.put("flags", JSONArray(e.flags.toList()))
        o.put("groups", JSONArray().apply {
            e.registeredGroups().forEach { g ->
                put(JSONObject().put("name", g.name).put("flags", JSONArray(g.flags.toList())))
            }
        })
        o.put("needs", JSONArray().apply {
            e.needs.forEach { n ->
                put(JSONObject()
                    .put("name", n.name).put("priority", n.priority)
                    .put("satisfiedBy", JSONArray(n.satisfiedByFlags.toList())))
            }
        })
        o.put("memory", JSONObject().apply {
            e.memory.forEach { (k, v) -> put(k, memoryValueToJson(v)) }
        })
        return o
    }

    private fun entityFromJson(o: JSONObject): Entity {
        val e = Entity(
            name = o.getString("name"),
            hp = o.getInt("hp"), maxHp = o.getInt("maxHp"),
            mp = o.optInt("mp", 0), maxMp = o.optInt("maxMp", 0),
            x = o.getInt("x"), y = o.getInt("y"),
            interestRadius = o.optDouble("interestRadius", 192.0).toFloat(),
            id = o.getString("id"),
        )
        o.optJSONArray("flags")?.let { arr ->
            for (i in 0 until arr.length()) e.addFlag(arr.getString(i))
        }
        o.optJSONArray("groups")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val flags = g.getJSONArray("flags").let { fa ->
                    (0 until fa.length()).map { fa.getString(it) }.toSet()
                }
                e.registerGroup(FlagGroup(g.getString("name"), flags))
            }
        }
        o.optJSONArray("needs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                val satisfied = n.optJSONArray("satisfiedBy")?.let { sa ->
                    (0 until sa.length()).map { sa.getString(it) }.toSet()
                } ?: emptySet()
                e.needs.add(Need(n.getString("name"), n.getInt("priority"), satisfied))
            }
        }
        o.optJSONObject("memory")?.let { m ->
            m.keys().forEach { k ->
                memoryValueFromJson(m.getJSONObject(k))?.let { e.memory[k] = it }
            }
        }
        return e
    }

    // ── MemoryValue ↔ JSON ───────────────────────────────────────────────────

    private fun memoryValueToJson(v: MemoryValue): JSONObject = when (v) {
        is MemoryValue.Str   -> JSONObject().put("t", "str").put("v", v.v)
        is MemoryValue.Num   -> JSONObject().put("t", "num").put("v", v.v)
        is MemoryValue.Coord -> JSONObject().put("t", "coord").put("col", v.col).put("row", v.row)
        is MemoryValue.Bool  -> JSONObject().put("t", "bool").put("v", v.v)
    }

    private fun memoryValueFromJson(o: JSONObject): MemoryValue? = when (o.optString("t")) {
        "str"   -> MemoryValue.Str(o.getString("v"))
        "num"   -> MemoryValue.Num(o.getDouble("v"))
        "coord" -> MemoryValue.Coord(o.getInt("col"), o.getInt("row"))
        "bool"  -> MemoryValue.Bool(o.getBoolean("v"))
        else    -> null
    }

    // ── MovementPattern ↔ String ─────────────────────────────────────────────

    private fun patternToString(p: MovementPattern): String = when (p) {
        is MovementPattern.Walker -> "walker"
        is MovementPattern.King   -> "king"
        is MovementPattern.Queen  -> "queen"
        is MovementPattern.Pawn   -> "pawn"
        is MovementPattern.Knight -> "knight"
        is MovementPattern.Bishop -> "bishop"
        is MovementPattern.Rook   -> "rook"
    }

    private fun patternFromString(s: String, fallback: MovementPattern): MovementPattern = when (s) {
        "walker" -> MovementPattern.Walker
        "king"   -> MovementPattern.King
        "queen"  -> MovementPattern.Queen
        "pawn"   -> MovementPattern.Pawn()
        "knight" -> MovementPattern.Knight
        "bishop" -> MovementPattern.Bishop
        "rook"   -> MovementPattern.Rook
        else     -> fallback
    }
}
