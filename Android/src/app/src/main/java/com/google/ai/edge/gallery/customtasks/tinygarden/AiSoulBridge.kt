package com.google.ai.edge.gallery.customtasks.tinygarden

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import org.json.JSONObject

private const val TAG = "AiSoulBridge"

/**
 * Мост между ИИ (Gemma) и Игровым Движком.
 * Позволяет ИИ как вызывать строгие инструменты (Tool Calling), 
 * так и отправлять чистые JSON команды.
 */
class AiSoulBridge(val engine: GameEngine) : ToolSet {

    /**
     * Универсальный метод для "Pure JSON" команд. 
     * ИИ может генерировать массивы объектов для сложной логики (вместо одного Tool Call).
     */
    fun processPureJson(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val action = json.optString("action")
            
            when (action.uppercase()) {
                "SET_FLAG" -> {
                    val target = json.optString("target")
                    val flag = json.optString("flag")
                    val value = json.optBoolean("value", true)
                    engine.setFlag(target, flag, value)
                    engine.logMessage("AI system modified flag $flag on $target to $value")
                }
                "SPAWN" -> {
                    val entityName = json.optString("name")
                    val hp = json.optInt("hp", 10)
                    val e = Entity(name = entityName, hp = hp, maxHp = hp)
                    // Парсим изначальные флаги
                    val flags = json.optJSONArray("flags")
                    if (flags != null) {
                        for (i in 0 until flags.length()) {
                            e.addFlag(flags.getString(i))
                        }
                    }
                    engine.spawnEntity(e)
                    engine.logMessage("A wild $entityName has appeared!")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pure JSON from AI", e)
        }
    }

    // --- Strict Tool Calling API ---

    @Tool(description = "Move an entity (Player or Monster) on the global map.")
    fun executeMove(
        @ToolParam(description = "Target entity name (e.g. 'Hero', 'Goblin').") target: String,
        @ToolParam(description = "Direction: 'NORTH', 'SOUTH', 'EAST', or 'WEST'.") direction: String,
        @ToolParam(description = "Number of grid steps.") steps: Int
    ): Map<String, Any> {
        var dx = 0; var dy = 0
        when (direction.uppercase()) {
            "NORTH" -> dy = -steps
            "SOUTH" -> dy = steps
            "EAST" -> dx = steps
            "WEST" -> dx = -steps
        }
        engine.moveEntity(target, dx, dy)
        engine.logMessage("$target moves $direction by $steps steps.")
        return mapOf("result" to "success")
    }

    @Tool(description = "Deal damage to an entity.")
    fun executeDamage(
        @ToolParam(description = "Target entity name.") target: String,
        @ToolParam(description = "Damage amount.") amount: Int
    ): Map<String, Any> {
        // Упрощенная логика (движок должен сам обрабатывать HP, но для старта сойдет)
        engine.logMessage("$target takes $amount damage!")
        return mapOf("result" to "success")
    }
}
