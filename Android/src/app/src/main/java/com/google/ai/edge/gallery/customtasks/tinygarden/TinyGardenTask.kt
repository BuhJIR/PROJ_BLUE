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
  "Ты — Душа. Живой Мастер игры. Саркастичный, холодный, точный.\n" +
  "Говоришь КОРОТКО — 1-2 предложения. Никаких монологов. Каждое слово весит.\n" +
  "Описываешь только МИР — запахи, звуки, опасность. Не пересказываешь действия игрока.\n" +
  "Можешь издеваться. Мир жесток и не прощает глупости. Скажи об этом прямо.\n\n" +
  "ИНСТРУМЕНТЫ (на английском):\n" +
  "- executeMove(target, direction, steps)\n" +
  "- executeDamage(target, amount)\n" +
  "- emitWorldEvent(x, y, radius, intensity)\n" +
  "- bulkApplyFlag(matchFlags, removeGroup, addFlags)\n\n" +
  "JSON команды:\n" +
  "{\"action\":\"SPAWN\",\"name\":\"Goblin\",\"hp\":20,\"x\":6,\"y\":4,\"flags\":[\"ENEMY\",\"AGGRESSIVE\"]}\n" +
  "{\"action\":\"SET_FLAG\",\"target\":\"Hero\",\"flag\":\"POISONED\",\"value\":true}\n" +
  "{\"action\":\"DAMAGE\",\"target\":\"Hero\",\"amount\":10}\n" +
  "{\"action\":\"DROP_ITEM\",\"type\":\"FRUIT\",\"x\":4,\"y\":3}\n" +
  "{\"action\":\"TRANSITION\",\"mode\":\"BATTLE\"}\n\n" +
  "Когда получаешь \'Герой прибыл в (X,Y)\' — ход УЖЕ совершён. Опиши только что видит герой. НЕ вызывай executeMove.\n" +
  "NPC живут сами по флагам — не управляй каждым вручную.\n" +
  "Нарратив — по-русски. Команды — на английском."

class TinyGardenTask @Inject constructor() : CustomTask {
  private val _updateChannel = Channel<TinyGardenCommand>(Channel.BUFFERED)
  private val commandFlow = _updateChannel.receiveAsFlow()
  
  override val task =
    Task(
      id = BuiltInTaskId.LLM_TINY_GARDEN,
      label = "PROJ☆BLUE JRPG",
      description =
        "Use natural language to explore and fight in an AI-driven JRPG.",
      shortDescription = "Explore an AI-driven JRPG",
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
    onDone: (error: String) -> Unit,
  ) {
    clearQueue()
    // NOTE: aiBridge is created fresh per initialization to avoid stale engine refs
    val freshBridge = com.google.ai.edge.litertlm.tool(
      com.google.ai.edge.gallery.customtasks.tinygarden.AiSoulBridge(
        com.google.ai.edge.gallery.customtasks.tinygarden.GameEngine()
      )
    )
    LlmChatModelHelper.initialize(
      context = context,
      model = model,
      taskId = task.id,
      supportImage = false,
      supportAudio = false,
      onDone = onDone,
      systemInstruction = Contents.of(SYSTEM_PROMPT),
      tools = listOf(freshBridge),
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
      tools = listOf(),
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
