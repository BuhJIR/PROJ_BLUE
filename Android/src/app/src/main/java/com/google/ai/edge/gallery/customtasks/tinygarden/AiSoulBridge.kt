package com.google.ai.edge.gallery.customtasks.tinygarden

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AiSoulBridge"

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
            when (json.optString("action").uppercase()) {

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
                    engine.spawnEntity(e)
                    Log.d(TAG, "Spawned: $entityName at ($x,$y)")
                }

                "SET_FLAG" -> {
                    val target = json.optString("target")
                    val flag = json.optString("flag")
                    val value = json.optBoolean("value", true)
                    engine.setFlag(target, flag, value)
                    engine.logMessage("[$target] flag $flag → $value")
                }

                "BULK_FLAG" -> {
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

                "DROP_ITEM" -> {
                    val type = json.optString("type", "ITEM")
                    val x = json.optInt("x", 5)
                    val y = json.optInt("y", 5)
                    engine.dropItem(WorldItem(type = type, x = x, y = y))
                    engine.logMessage("A $type appears at ($x, $y).")
                }

                "EMIT_EVENT" -> {
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

                "DAMAGE" -> {
                    val target = json.optString("target")
                    val amount = json.optInt("amount", 0)
                    engine.applyDamage(target, amount)
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
