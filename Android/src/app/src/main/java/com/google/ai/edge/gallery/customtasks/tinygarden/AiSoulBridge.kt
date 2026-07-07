package com.google.ai.edge.gallery.customtasks.tinygarden

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AiSoulBridge"

/**
 * Разбор смешанного ответа модели (SPEC §10).
 *
 * Маленькая on-device модель регулярно пишет нарратив и JSON-команду в одном
 * ответе: "Гоблин рычит. {\"action\":\"DAMAGE\",...}". Раньше команда,
 * обёрнутая в прозу, молча терялась. Сканер выделяет первый сбалансированный
 * {...}-блок (учитывая строки и экранирование), остальное — нарратив.
 */
object SoulResponseParser {

    /** @return (json или null, нарратив вне блока) */
    fun extractFirstJsonObject(text: String): Pair<String?, String> {
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped   -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"'  -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> if (depth > 0) inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0) {
                        val json = text.substring(start, i + 1)
                        val narrative = (text.substring(0, start) + " " + text.substring(i + 1)).trim()
                        return json to narrative
                    }
                }
            }
        }
        return null to text.trim()
    }
}

/**
 * AiSoulBridge — интерфейс между Gemma и GameEngine.
 *
 * Два канала:
 *  A) Tool Calling  — строгие инструменты с типизированными параметрами
 *  B) Pure JSON     — свободные команды для сложной логики (SPAWN, SET_FLAG, BULK_FLAG, EVENT)
 */
class AiSoulBridge(val engine: GameEngine) : ToolSet {

    // ── B) Pure JSON ─────────────────────────────────────────────────────────

    fun processPureJson(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            // Модель смешивает каналы: пишет имена инструментов как JSON-экшены
            // ("execute_damage", "build_structure"). Нормализуем — убираем
            // подчёркивания/дефисы, дальше матчим по алиасам, а не буквально.
            val action = json.optString("action").uppercase()
                .replace("_", "").replace("-", "")
            when (action) {

                "SPAWN" -> {
                    val entityName = json.optString("name", "Unknown")
                    val hp = json.optInt("hp", 10)
                    val x = json.optInt("x", 3)
                    val y = json.optInt("y", 3)
                    val e = Entity(name = entityName, hp = hp, maxHp = hp, x = x, y = y)
                    json.optJSONArray("flags")?.let { arr ->
                        for (i in 0 until arr.length()) e.addFlag(arr.getString(i))
                    }
                    // Регистрируем группы если переданы
                    json.optJSONArray("groups")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val g = arr.getJSONObject(i)
                            val gName = g.getString("name")
                            val gFlags = g.getJSONArray("flags").let { fa ->
                                (0 until fa.length()).map { fa.getString(it) }.toSet()
                            }
                            e.registerGroup(FlagGroup(gName, gFlags))
                            if (g.optBoolean("active", false)) e.addGroup(gName)
                        }
                    }
                    // Нужды: [{"name":"HUNGER","priority":90,"satisfied_by":["FRUIT","FOOD"]}]
                    json.optJSONArray("needs")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val n = arr.getJSONObject(i)
                            val satisfied = n.optJSONArray("satisfied_by")?.let { sa ->
                                (0 until sa.length()).map { sa.getString(it).uppercase() }.toSet()
                            } ?: emptySet()
                            e.needs.add(Need(n.optString("name", "NEED"), n.optInt("priority", 50), satisfied))
                        }
                    }
                    // Дом: {"home":{"x":4,"y":7}} — TIRED поведёт entity сюда
                    json.optJSONObject("home")?.let { h ->
                        e.memory["home"] = MemoryValue.Coord(h.optInt("x", e.x), h.optInt("y", e.y))
                    }
                    // Поселение — ключ в settlementLore для промпта микро-агента (SPEC §19.3)
                    json.optString("settlement").takeIf { it.isNotEmpty() }?.let {
                        e.memory["settlement"] = MemoryValue.Str(it)
                    }
                    // Роль — NPC становится микро-агентом с собственным моментом мысли
                    json.optString("role").takeIf { it.isNotEmpty() }?.let { role ->
                        engine.registerNpcProfile(
                            NpcAgentProfile(entityId = e.id, localRole = role, usesMicroAgent = true)
                        )
                    }
                    engine.spawnEntity(e)
                    Log.d(TAG, "Spawned: $entityName at ($x,$y)")
                }

                "SETFLAG" -> {
                    val target = json.optString("target")
                    val flag = json.optString("flag")
                    val value = json.optBoolean("value", true)
                    engine.setFlag(target, flag, value)
                    engine.logMessage("[$target] flag $flag → $value")
                }

                "BULKFLAG", "BULKAPPLYFLAG" -> {
                    // "Все гоблины крестьяне сегодня празднуют"
                    // { "action": "BULK_FLAG", "match": ["GOBLIN","PEASANT"],
                    //   "remove_group": "WORK", "add_flags": ["CELEBRATE"] }
                    val match = json.optJSONArray("match")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList()
                    val removeGroup = json.optString("remove_group").ifEmpty { null }
                    val addFlags = json.optJSONArray("add_flags")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList()
                    engine.bulkFlag(match, removeGroup, addFlags)
                }

                "DROPITEM" -> {
                    val type = json.optString("type", "ITEM")
                    val x = json.optInt("x", 5)
                    val y = json.optInt("y", 5)
                    engine.dropItem(WorldItem(type = type, x = x, y = y))
                    engine.logMessage("A $type appears at ($x, $y).")
                }

                "EMITEVENT", "EMITWORLDEVENT", "EVENT" -> {
                    val x = json.optDouble("x", 5.0).toFloat()
                    val y = json.optDouble("y", 5.0).toFloat()
                    val radius = json.optDouble("radius", 128.0).toFloat()
                    val intensity = json.optDouble("intensity", 0.8).toFloat()
                    engine.emitCustomEvent(x, y, radius, intensity)
                    engine.logMessage("A mysterious disturbance ripples through the area.")
                }

                "TRANSITION" -> {
                    val mode = json.optString("mode", "OVERWORLD")
                    engine.transitionMode(if (mode.uppercase() == "BATTLE") GameMode.BATTLE else GameMode.OVERWORLD)
                }

                "DAMAGE", "EXECUTEDAMAGE" -> {
                    val target = json.optString("target").ifEmpty { json.optString("name") }
                    val amount = json.optInt("amount", json.optInt("damage", json.optInt("value", 0)))
                    engine.applyDamage(target, amount)
                }

                "MOVE", "EXECUTEMOVE" -> {
                    // Инструмент, написанный как JSON: {"action":"execute_move","target":"Hero","direction":"NORTH","steps":2}
                    val target = json.optString("target").ifEmpty { json.optString("name") }
                    val direction = json.optString("direction").ifEmpty { json.optString("dir") }
                    val steps = json.optInt("steps", json.optInt("count", 1)).coerceIn(1, 8)
                    var dx = 0; var dy = 0
                    when (direction.uppercase()) {
                        "NORTH" -> dy = -steps; "SOUTH" -> dy = steps
                        "EAST"  -> dx = steps;  "WEST"  -> dx = -steps
                    }
                    if (dx != 0 || dy != 0) {
                        engine.moveEntity(target, dx, dy)
                        engine.logMessage("$target moves $direction $steps step${if (steps > 1) "s" else ""}.")
                    }
                }

                "BUILD", "BUILDSTRUCTURE" -> {
                    // {"action":"build_structure","dsl":"3×levels; stairs; stone; flat","x":28,"y":41}
                    val dsl = json.optString("dsl").ifEmpty { json.optString("structure") }
                    if (dsl.isNotEmpty()) {
                        val x = json.optInt("x", engine.currentState().player.col)
                        val y = json.optInt("y", engine.currentState().player.row)
                        val spec = StructureParser.parse(dsl)
                        StructureGenerator.applyToEngine(spec, x, y, engine)
                    }
                }

                "RAISE", "RAISETERRAIN", "RAISEFLOOR", "RAISEGROUND" -> {
                    // {"action":"raise_terrain","x":28,"y":41,"radius":6,"height":3}
                    val x = json.optInt("x", engine.currentState().player.col)
                    val y = json.optInt("y", engine.currentState().player.row)
                    val radius = json.optInt("radius", 4)
                    val height = json.optInt("height", json.optInt("lift", 1))
                    StructureGenerator.raiseTerrain(x, y, radius, height, engine)
                }

                "WORLDLAW", "REWRITEWORLDLAW" -> {
                    // {"action":"WORLD_LAW","law":"The sun has gone out. Darkness is permanent."}
                    val law = json.optString("law")
                        .ifEmpty { json.optString("newLaw") }
                        .ifEmpty { json.optString("new_law") }
                    if (law.isNotEmpty()) engine.setWorldLaw(law)
                }

                "SETTLEMENTLORE" -> {
                    // {"action":"SETTLEMENT_LORE","id":"riverside_village","lore":"Here, Three is honoured above all."}
                    val id = json.optString("id")
                    val lore = json.optString("lore")
                    if (id.isNotEmpty() && lore.isNotEmpty()) engine.setSettlementLore(id, lore)
                }

                else -> engine.logMessage("Soul whispers: ${jsonString.take(120)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pure JSON from AI: $jsonString", e)
        }
    }

    // ── A) Tool Calling ──────────────────────────────────────────────────────

    @Tool(description = "Move an entity on the map. Direction: NORTH, SOUTH, EAST, WEST.")
    fun executeMove(
        @ToolParam(description = "Entity name (e.g. 'Hero', 'Goblin').") target: String,
        @ToolParam(description = "Direction: NORTH, SOUTH, EAST, or WEST.") direction: String,
        @ToolParam(description = "Number of grid steps (1-5).") steps: Int,
    ): Map<String, Any> {
        var dx = 0; var dy = 0
        when (direction.uppercase()) {
            "NORTH" -> dy = -steps; "SOUTH" -> dy = steps
            "EAST"  -> dx = steps;  "WEST"  -> dx = -steps
        }
        engine.moveEntity(target, dx, dy)
        engine.logMessage("$target moves $direction $steps step${if (steps > 1) "s" else ""}.")
        return mapOf("result" to "success", "dx" to dx, "dy" to dy)
    }

    @Tool(description = "Deal damage to a named entity or player.")
    fun executeDamage(
        @ToolParam(description = "Target entity name.") target: String,
        @ToolParam(description = "Damage amount (1-50).") amount: Int,
    ): Map<String, Any> {
        engine.applyDamage(target, amount)
        return mapOf("result" to "success", "target" to target, "damage" to amount)
    }

    @Tool(description = "Emit a world event at coordinates to wake nearby NPCs.")
    fun emitWorldEvent(
        @ToolParam(description = "X coordinate.") x: Float,
        @ToolParam(description = "Y coordinate.") y: Float,
        @ToolParam(description = "Radius in grid units.") radius: Float,
        @ToolParam(description = "Intensity 0.0-1.0.") intensity: Float,
    ): Map<String, Any> {
        engine.emitCustomEvent(x, y, radius, intensity)
        return mapOf("result" to "success")
    }

    @Tool(description = "Build a structure (ziggurat, tower, platform) using a compact DSL. " +
        "Format: 'N×levels; stairs; non-trees; material; open-type; flat'. " +
        "Example: '3×levels; stairs; non-trees; stone; open-type; flat'. " +
        "Materials: stone, wood, dirt, grass. Use flat for platforms, peak for pyramids.")
    fun buildStructure(
        @ToolParam(description = "DSL string, e.g. '3×levels; stairs; stone; flat'") dsl: String,
        @ToolParam(description = "X coordinate of the structure centre") x: Int,
        @ToolParam(description = "Y coordinate of the structure centre") y: Int,
    ): Map<String, Any> {
        val spec = StructureParser.parse(dsl)
        StructureGenerator.applyToEngine(spec, x, y, engine)
        return mapOf("result" to "success", "levels" to spec.levels, "material" to spec.material.name)
    }

    @Tool(description = "Raise ALL terrain in a radius around a point — including every structure " +
        "standing on it. The player states the radius in their request; pass it through. " +
        "Use for floods of earth, ritual platforms, lifting a whole district.")
    fun raiseTerrain(
        @ToolParam(description = "X coordinate of the centre") x: Int,
        @ToolParam(description = "Y coordinate of the centre") y: Int,
        @ToolParam(description = "Radius in tiles, as stated by the player") radius: Int,
        @ToolParam(description = "How many height units to lift (1-10 typical)") height: Int,
    ): Map<String, Any> {
        StructureGenerator.raiseTerrain(x, y, radius, height, engine)
        return mapOf("result" to "success", "radius" to radius, "height" to height)
    }

    @Tool(description = "Rewrite the fundamental law of the world. Use only for major plot events that " +
        "change reality itself for every inhabitant simultaneously — e.g. an artefact extinguishing the sun, " +
        "a plague of silence, the invention of fire. Every NPC agent will perceive this as ground truth " +
        "from their next wake cycle. This is not a flag on one entity — it rewrites what all entities believe " +
        "to be true about existence.")
    fun rewriteWorldLaw(
        @ToolParam(description = "The new law, written as a short declarative statement of fact, " +
            "e.g. 'The sun has gone out. Darkness is permanent. No one remembers warmth.'") newLaw: String,
    ): Map<String, Any> {
        engine.setWorldLaw(newLaw)
        return mapOf("result" to "success", "law" to newLaw)
    }

    @Tool(description = "Apply a flag to all entities matching given flag conditions. Use for group events like festivals.")
    fun bulkApplyFlag(
        @ToolParam(description = "Comma-separated flags to match (e.g. 'GOBLIN,PEASANT').") matchFlags: String,
        @ToolParam(description = "Flag group name to remove (e.g. 'WORK'). Empty to skip.") removeGroup: String,
        @ToolParam(description = "Comma-separated flags to add (e.g. 'CELEBRATE').") addFlags: String,
    ): Map<String, Any> {
        val match = matchFlags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val add = addFlags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        engine.bulkFlag(match, removeGroup.ifEmpty { null }, add)
        return mapOf("result" to "success", "matched_flags" to match, "added" to add)
    }
}
