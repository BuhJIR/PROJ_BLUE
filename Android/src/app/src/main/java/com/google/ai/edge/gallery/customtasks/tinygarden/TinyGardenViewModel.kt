package com.google.ai.edge.gallery.customtasks.tinygarden

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGTGViewModel"

data class TinyGardenUiState(
  val processing: Boolean = false,
  val resettingEngine: Boolean = false,
  val messages: List<ChatMessage> = listOf(),
  val numTurns: Int = 0,
  val gameState: GameState = GameState()
)

@HiltViewModel
class TinyGardenViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  val dataStoreRepository: DataStoreRepository,
  // Синглтоны из Hilt — тот же engine, к которому привязаны tool-вызовы модели (SPEC §1)
  val engine: GameEngine,
  val aiBridge: AiSoulBridge,
) : ViewModel() {
  protected val _uiState = MutableStateFlow(TinyGardenUiState())
  val uiState = _uiState.asStateFlow()

  private val _isResettingConversation = MutableStateFlow(false)
  private val isResettingConversation = _isResettingConversation.asStateFlow()

  init {
      engine.observe { newState ->
          _uiState.update { it.copy(gameState = newState) }
      }
  }

  fun getCommand(
    model: Model,
    instructionText: String,
    onDone: (String) -> Unit,
    onError: (String) -> Unit,
  ) {
    if (model.instance == null) {
      setProcessing(processing = false)
      return
    }

    incrementNumTurns()
    this.addMessage(message = ChatMessageText(content = instructionText, side = ChatSide.USER))

    viewModelScope.launch(Dispatchers.Default) {
      setProcessing(processing = true)
      isResettingConversation.first { !it }

      val instance = model.instance as LlmModelInstance
      val conversation = instance.conversation
      val contents = mutableListOf<Content>()
      if (instructionText.trim().isNotEmpty()) {
        // Активная Сестра окрашивает тон ответа Души — point-of-injection из SPEC §15:
        // это контекст хода, не новая игровая логика
        val activeSister = engine.currentState().activeSister
        val augmented = if (activeSister != null) {
          "$instructionText\n[Currently speaking through: ${activeSister.principle.display}]"
        } else instructionText
        contents.add(Content.Text(augmented))
      }

      try {
        val responseMessage = conversation.sendMessage(Contents.of(contents))
        val response = responseMessage.toString()

        // Команда может быть вшита в прозу — извлекаем первый {...} блок (SPEC §10)
        val (jsonCommand, narrative) = SoulResponseParser.extractFirstJsonObject(response)
        if (jsonCommand != null) {
            aiBridge.processPureJson(jsonCommand)
        }
        if (narrative.isNotBlank()) {
            engine.logMessage("Soul: $narrative")
        }

        addMessage(message = ChatMessageText(content = response, side = ChatSide.AGENT))
        onDone(response)
      } catch (e: Exception) {
        onError(e.message ?: context.getString(R.string.unknown_error))
      } finally {
        setProcessing(processing = false)
      }
    }
  }

  /** Добавить сообщение игрока в историю без отправки модели. */
  fun addPlayerMessage(text: String) {
    addMessage(message = ChatMessageText(content = text, side = ChatSide.USER))
  }

  fun addMessage(message: ChatMessage) {
    val newMessages = _uiState.value.messages.toMutableList()
    newMessages.add(message)
    _uiState.update { _uiState.value.copy(messages = newMessages) }
  }

  fun clearMessages() {
    _uiState.update { _uiState.value.copy(messages = listOf()) }
  }

  fun setProcessing(processing: Boolean) {
    _uiState.update { uiState.value.copy(processing = processing) }
  }

  fun setResettingEngine(resetting: Boolean) {
    _uiState.update { uiState.value.copy(resettingEngine = resetting) }
  }

  fun incrementNumTurns() {
    _uiState.update { uiState.value.copy(numTurns = uiState.value.numTurns + 1) }
  }

  fun resetNumTurns() {
    _uiState.update { uiState.value.copy(numTurns = 0) }
  }

  fun resetEngine(
    model: Model,
    systemPrompt: String,
    llmChatModelHelper: LlmChatModelHelper,
    onDone: () -> Unit,
    onError: (String) -> Unit,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      _isResettingConversation.value = true
      setResettingEngine(resetting = true)
      try {
        llmChatModelHelper.resetConversation(
          model = model,
          supportImage = false,
          supportAudio = false,
          systemInstruction = Contents.of(systemPrompt),
          tools = listOf(com.google.ai.edge.litertlm.tool(aiBridge)),
          enableConversationConstrainedDecoding = true,
          initialMessages = listOf()
        )
        onDone()
      } catch (e: Exception) {
        onError(e.message ?: context.getString(R.string.unknown_error))
      } finally {
        setResettingEngine(resetting = false)
        _isResettingConversation.value = false
      }
    }
  }

  fun resetConversation(
    model: Model,
    systemPrompt: String,
    llmChatModelHelper: LlmChatModelHelper,
    onError: (String) -> Unit,
  ) {
    this.clearMessages()
    this.resetNumTurns()
    resetEngine(
      model = model,
      systemPrompt = systemPrompt,
      llmChatModelHelper = llmChatModelHelper,
      onDone = {},
      onError = onError,
    )
  }
}
