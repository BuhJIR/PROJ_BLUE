package com.google.ai.edge.gallery.customtasks.tinygarden

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ConfigKeys
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
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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

  private val saveFile = File(context.filesDir, GameStatePersistence.SAVE_FILE_NAME)
  private val saveTick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  init {
      // Загрузка — один раз на жизнь синглтон-движка, до подписки observe (SPEC §3)
      if (!engine.restoreAttempted) {
          engine.restoreAttempted = true
          GameStatePersistence.load(engine, saveFile)
          // Пустой мир (свежий старт или всё перебито) — мир «дышит» и заселяется вновь
          if (engine.currentState().entities.isEmpty()) {
              engine.populateAmbient()
          }
      }
      engine.observe { newState ->
          _uiState.update { it.copy(gameState = newState) }
          saveTick.tryEmit(Unit)
      }
      // Debounced autosave: пишем после 2 секунд тишины, не на каждый кадр
      viewModelScope.launch(Dispatchers.IO) {
          saveTick.collectLatest {
              delay(2000)
              GameStatePersistence.save(engine, saveFile)
          }
      }
  }

  override fun onCleared() {
      // ViewModel уходит (навигация/процесс) — не полагаемся на debounce
      GameStatePersistence.save(engine, saveFile)
      super.onCleared()
  }

  /** Есть ли сохранённый мир (для CONTINUE на титульнике). */
  fun hasSave(): Boolean = saveFile.exists()

  /** NEW GAME: стирает сейв и создаёт мир с новым случайным seed. */
  fun startNewGame() {
      saveFile.delete()
      engine.restoreAttempted = true // не дать более позднему load() затереть новый мир
      engine.newGame(kotlin.random.Random.Default.nextLong())
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
    engine.advanceTurn() // мировое время: флора живёт по ходам
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
        // Комбо памяти: на пороге N ходов Душа перерождается.
        // Слайдер RESET_CONVERSATION_TURN_COUNT существовал, но его никто не читал.
        maybeRebirth(model)
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

  // ── Перерождение Души: контекст модели не бесконечен ────────────────────────

  private var rebirthing = false

  /**
   * Каждые N ходов (настройка «Number of turns before the conversation resets»)
   * разговорная память Души сбрасывается — мир при этом не трогается.
   * Новая беседа стартует с «моста памяти»: краткой сводки мира, чтобы Душа
   * продолжила вести его без провала. Порог виден в HUD как комбо.
   */
  private fun maybeRebirth(model: Model) {
    // Порог 1 дал бы перерождение после каждого хода, включая сам мост — клампим
    val threshold = model
      .getIntConfigValue(ConfigKeys.RESET_CONVERSATION_TURN_COUNT, 3)
      .coerceAtLeast(2)
    if (rebirthing || uiState.value.numTurns < threshold) return
    rebirthing = true
    viewModelScope.launch(Dispatchers.Default) {
      _isResettingConversation.value = true
      try {
        LlmChatModelHelper.resetConversation(
          model = model,
          supportImage = false,
          supportAudio = false,
          systemInstruction = Contents.of(TINY_GARDEN_SYSTEM_PROMPT),
          tools = listOf(com.google.ai.edge.litertlm.tool(aiBridge)),
          enableConversationConstrainedDecoding = true,
          initialMessages = listOf(),
        )
        resetNumTurns()
        engine.logMessage("The Soul sheds its memory — and wakes anew.")
        addMessage(
          ChatMessageText(
            content = "— Душа переродилась: память растворилась, мир остался —",
            side = ChatSide.SYSTEM,
          )
        )
        _isResettingConversation.value = false
        // Мост памяти: первый ход новой беседы — сводка живого мира
        getCommand(model, memoryBridge(), onDone = {}, onError = {})
      } catch (e: Exception) {
        Log.e(TAG, "Soul rebirth failed", e)
        _isResettingConversation.value = false
      } finally {
        rebirthing = false
      }
    }
  }

  private fun memoryBridge(): String {
    val s = engine.currentState()
    return buildString {
      append("(Пробуждение после перерождения. Закон мира: ${s.worldLaw} ")
      append("Герой в (${s.player.col}, ${s.player.row}), HP ${s.player.hp}/${s.player.maxHp}. ")
      if (s.entities.isNotEmpty()) {
        append("Рядом: ${s.entities.values.take(4).joinToString { "${it.name}(${it.col},${it.row})" }}. ")
      }
      val recent = s.battleLog.takeLast(3)
      if (recent.isNotEmpty()) append("Недавнее: ${recent.joinToString(" | ")}. ")
      append("Продолжай вести мир как ни в чём не бывало.)")
    }
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
