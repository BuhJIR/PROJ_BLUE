package com.google.ai.edge.gallery.customtasks.tinygarden

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.customtask.CustomTask
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

private const val SYSTEM_PROMPT =
  "You are the Game Master (Soul Companion) for PROJ BLUE, an AI-driven JRPG.\n" +
  "The player explores the Overworld and fights monsters.\n" +
  "Your tools are:\n" +
  "1. Tool: 'executeMove' to move the player around the map.\n" +
  "2. Pure JSON: If you want to spawn enemies or change flags, reply ONLY with a valid JSON block, for example:\n" +
  "{\"action\": \"SPAWN\", \"name\": \"Goblin\", \"hp\": 20, \"flags\": [\"ENEMY\"]}\n" +
  "or\n" +
  "{\"action\": \"SET_FLAG\", \"target\": \"Hero\", \"flag\": \"POISONED\", \"value\": true}\n\n" +
  "Narrate briefly in a dramatic retro RPG style when not using pure JSON!"

class TinyGardenTask @Inject constructor() : CustomTask {
  private val _updateChannel = Channel<TinyGardenCommand>(Channel.BUFFERED)
  private val commandFlow = _updateChannel.receiveAsFlow()

  override fun initializeModelFn(
    scope: CoroutineScope,
    model: Model,
    systemInstruction: String?,
    llmChatModelHelper: LlmChatModelHelper,
    onDone: () -> Unit,
    onError: (String) -> Unit
  ) {
    llmChatModelHelper.resetChat(
      model = model,
      systemInstruction = getTinyGardenSystemPrompt(model),
      toolSets = listOf(),
    )
    onDone()
  }

  override fun cleanUpModelFn(
    scope: CoroutineScope,
    model: Model,
    llmChatModelHelper: LlmChatModelHelper,
    onDone: () -> Unit
  ) {
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val request = data as CustomTask.MainScreenRequest
    
    TinyGardenScreen(
      task = request.task,
      modelManagerViewModel = request.modelManagerViewModel,
      tools = listOf(),
      bottomPadding = request.bottomPadding,
      setAppBarControlsDisabled = request.setAppBarControlsDisabled,
      setTopBarVisible = request.setTopBarVisible,
      commandFlow = commandFlow,
    )
  }
}

fun getTinyGardenSystemPrompt(model: Model): String = SYSTEM_PROMPT
