/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.customtasks.tinygarden

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

private const val TAG = "AGJrpgTools"

enum class JrpgAction { ATTACK, MAGIC, ITEM, ENEMY_ATTACK }

data class TinyGardenCommand(
  val action: JrpgAction,
  val target: String,
  val value: String = "",
  val damage: Int = 0,
  val ts: Long = System.currentTimeMillis(),
)

class TinyGardenTools(val onFunctionCalled: (command: TinyGardenCommand) -> Unit) : ToolSet {

  @Tool(description = "Perform a physical attack against a target.")
  fun performAttack(
    @ToolParam(description = "The target of the attack (e.g. 'Goblin').") target: String,
    @ToolParam(description = "The amount of damage dealt (1-20).") damage: Int
  ): Map<String, Any> {
    Log.d(TAG, "performAttack: $target, dmg: $damage")
    onFunctionCalled(TinyGardenCommand(action = JrpgAction.ATTACK, target = target, damage = damage))
    return mapOf("result" to "success", "action" to "attack", "damage" to damage)
  }

  @Tool(description = "Cast a magic spell on a target.")
  fun castMagic(
    @ToolParam(description = "The spell name (e.g. 'Fire', 'Cure').") spell: String,
    @ToolParam(description = "The target of the spell.") target: String,
    @ToolParam(description = "The damage or healing amount (1-30).") effectAmount: Int
  ): Map<String, Any> {
    Log.d(TAG, "castMagic: $spell on $target, effect: $effectAmount")
    onFunctionCalled(TinyGardenCommand(action = JrpgAction.MAGIC, target = target, value = spell, damage = effectAmount))
    return mapOf("result" to "success", "action" to "magic", "spell" to spell, "effect" to effectAmount)
  }

  @Tool(description = "Execute an enemy counter-attack against the player. Must be used when the enemy fights back.")
  fun enemyAttack(
    @ToolParam(description = "The name of the enemy attacking.") enemyName: String,
    @ToolParam(description = "The amount of damage dealt to the player (1-15).") damage: Int
  ): Map<String, Any> {
    Log.d(TAG, "enemyAttack: $enemyName, dmg: $damage")
    onFunctionCalled(TinyGardenCommand(action = JrpgAction.ENEMY_ATTACK, target = "Player", value = enemyName, damage = damage))
    return mapOf("result" to "success", "action" to "enemy_attack", "damage" to damage)
  }
}
