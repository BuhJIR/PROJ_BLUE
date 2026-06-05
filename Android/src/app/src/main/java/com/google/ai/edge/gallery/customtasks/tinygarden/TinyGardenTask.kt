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

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.tool
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

private const val SYSTEM_PROMPT =
  """You are the Game Master for a classic turn-based JRPG (Final Fantasy style).
The player is a hero currently in a battle against a 'Goblin' (HP: 50).
The player will command actions like 'Attack the goblin' or 'Cast Fire'.
Your job is to:
1. Call the appropriate tools (performAttack, castMagic) to register the player's action.
2. If the enemy survives, ALWAYS call the 'enemyAttack' tool to fight back against the player.
3. Narrate the battle round in a dramatic, retro RPG style. Keep it brief (1-3 lines).
Example response: "You swing your sword at the Goblin! The Goblin retaliates with a vicious scratch!"
"""

/** A custom task that demonstrates how to use FunctionGemma to play a simple JRPG. */
class TinyGardenTask @Inject constructor() : CustomTask {
  private val _updateChannel = Channel<TinyGardenCommand>(Channel.BUFFERED)
  private val commandFlow = _updateChannel.receiveAsFlow()
  private val tools =
    listOf(
      tool(
        TinyGardenTools(
          onFunctionCalled = {
            val unused = _updateChannel.trySend(it)
          }
        )
      )
    )

  override val task =
    Task(
      id = BuiltInTaskId.LLM_TINY_GARDEN,
      label = "PROJ☆BLUE JRPG",
      description =
        "Use natural language to fight monsters in this JRPG battle simulator.\n\nPowered by FunctionGemma.",
      shortDescription = "Fight monsters in a JRPG battle",
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/tinygarden",
      category = Category.LLM,
      icon = Icons.Outlined.LocalFlorist,
      agentNameRes = R.string.chat_agent_agent_name,
      models = mutableListOf(),
      handleModelConfigChangesInTask = true,
      experimental = true,
      defaultSystemPrompt = SYSTEM_PROMPT,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    clearQueue()
    LlmChatModelHelper.initialize(
      context = context,
      model = model,
      taskId = task.id,
      supportImage = false,
      supportAudio = false,
      onDone = onDone,
      systemInstruction = Contents.of(getTinyGardenSystemPrompt()),
      tools = tools,
      enableConversationConstrainedDecoding = true,
    )
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    clearQueue()
    LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val customTaskData = data as CustomTaskData
    TinyGardenScreen(
      task = task,
      modelManagerViewModel = customTaskData.modelManagerViewModel,
      tools = tools,
      bottomPadding = customTaskData.bottomPadding,
      commandFlow = commandFlow,
      setAppBarControlsDisabled = customTaskData.setAppBarControlsDisabled,
      setTopBarVisible = customTaskData.setTopBarVisible,
    )
  }

  private fun clearQueue() {
    while (_updateChannel.tryReceive().isSuccess) {}
  }
}

fun getTinyGardenSystemPrompt(
  playerHp: Int = 100,
  enemyHp: Int = 50,
  enemyName: String = "Goblin"
): String {
  val parts = mutableListOf(SYSTEM_PROMPT)
  parts.add("Current Battle Status:")
  parts.add("- Player HP: $playerHp")
  parts.add("- $enemyName HP: $enemyHp")
  if (enemyHp <= 0) {
    parts.add("The $enemyName is defeated! Commend the player for their victory!")
  } else if (playerHp <= 0) {
    parts.add("The Player has been defeated! Narrate a tragic game over sequence.")
  }
  return parts.joinToString(separator = "\n")
}
