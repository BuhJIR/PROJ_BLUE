package com.google.ai.edge.gallery.customtasks.tinygarden

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.data.ConfigKeys
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.common.getTaskBgGradientColors
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.TextAndVoiceInput
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.VoiceRecognizerOverlay
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.launch

@Composable
fun TinyGardenScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  tools: List<ToolProvider>,
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  setTopBarVisible: (Boolean) -> Unit,
  viewModel: TinyGardenViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  var recordAudioPermissionGranted by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val recordAudioClipsPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) recordAudioPermissionGranted = true
    }
  LaunchedEffect(Unit) {
    when (PackageManager.PERMISSION_GRANTED) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ->
        recordAudioPermissionGranted = true
      else -> recordAudioClipsPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }
  if (recordAudioPermissionGranted) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MainUi(
          task = task,
          modelManagerViewModel = modelManagerViewModel,
          tools = tools,
          bottomPadding = bottomPadding,
          viewModel = viewModel,
          setAppBarControlsDisabled = setAppBarControlsDisabled,
          setTopBarVisible = setTopBarVisible,
        )
        if (uiState.resettingEngine) {
          Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
              CircularProgressIndicator(trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
              Text(stringResource(R.string.resetting_engine), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
    }
  }
}

@Composable
fun GameRenderer(
  gameState: GameState,
  engine: GameEngine? = null,
  modifier: Modifier = Modifier,
) {
  IsoMapRenderer(gameState = gameState, engine = engine, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainUi(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  tools: List<ToolProvider>,
  bottomPadding: Dp,
  viewModel: TinyGardenViewModel,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  setTopBarVisible: (Boolean) -> Unit,
  holdToDictateViewModel: HoldToDictateViewModel = hiltViewModel(),
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val scope = rememberCoroutineScope()
  val uiState by viewModel.uiState.collectAsState()
  var clearTextTrigger by remember { mutableLongStateOf(0L) }
  var curAmplitude by remember { mutableIntStateOf(0) }
  val holdToDictateUiState by holdToDictateViewModel.uiState.collectAsState()
  var showConversationHistoryPanel by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var errorDialogContent by remember { mutableStateOf("") }
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current

  val taskColor = getTaskBgGradientColors(task = task)[1]
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[model.name]?.status

  setAppBarControlsDisabled(
    curDownloadStatus == ModelDownloadStatusType.SUCCEEDED &&
      (!modelManagerUiState.isModelInitialized(model = model) || uiState.processing)
  )
  BackHandler(enabled = showConversationHistoryPanel) { showConversationHistoryPanel = false }
  // В игре верхней панели нет вовсе — ни кнопки назад, ни названия.
  // Выход — системный жест назад; настройка порога памяти — тап по HUD.
  LaunchedEffect(Unit) { setTopBarVisible(false) }

  val noFunctionCallWarningMessage = stringResource(R.string.warning_no_function_call)
  val noFunctionCallSnackbarMessage = stringResource(R.string.snackbar_no_function_call)

  fun processInstructionText(text: String) {
    clearTextTrigger = System.currentTimeMillis()
    if (text.trim().isNotEmpty()) {
      viewModel.getCommand(
        model = model,
        instructionText = text,
        onDone = { _ -> },
        onError = { error -> errorDialogContent = error; showErrorDialog = true },
      )
      firebaseAnalytics?.logEvent(GalleryEvent.GENERATE_ACTION.id, Bundle().apply {
        putString("capability_name", task.id)
        putString("model_id", model.name)
      })
    }
  }

  if (!modelManagerUiState.isModelInitialized(model = model)) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
    }
  } else {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
      Column(modifier = Modifier.padding(bottom = if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) bottomPadding else 12.dp).fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
          val pixelFont = remember { FontFamily(Font(R.font.pixel_04b03)) }
          // Порог перерождения Души — читается из конфига модели, тап по HUD
          // циклически переключает пресеты (тот же ключ, что и слайдер настроек)
          var memThreshold by remember(model.name) {
            mutableIntStateOf(
              model.getIntConfigValue(ConfigKeys.RESET_CONVERSATION_TURN_COUNT, 3).coerceAtLeast(2)
            )
          }
          Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            GameRenderer(
              gameState = uiState.gameState,
              engine = viewModel.engine,
              modifier = Modifier.fillMaxSize().border(2.dp, Color(0xFF223344), RoundedCornerShape(4.dp))
            )
            SoulMemoryHud(
              turns = uiState.numTurns,
              threshold = memThreshold,
              font = pixelFont,
              modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
              onTap = {
                val presets = listOf(3, 6, 9, 12, 18, 30)
                val cur = presets.indexOf(memThreshold)
                val next = presets[(if (cur < 0) 0 else cur + 1) % presets.size]
                memThreshold = next
                model.configValues = model.configValues.toMutableMap().apply {
                  put(ConfigKeys.RESET_CONVERSATION_TURN_COUNT.label, next.toFloat())
                }
              },
            )
          }
          Spacer(modifier = Modifier.height(8.dp))

          // ── Кнопка [Идти] — появляется когда путь выбран ──────────────────
          val path = viewModel.engine.currentPath
          val selectedTile = viewModel.engine.selectedTile
          if (path.isNotEmpty() && selectedTile != null) {
            androidx.compose.foundation.layout.Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Button(
                onClick = {
                  val dest = selectedTile
                  val pathCopy = path.toList()
                  viewModel.engine.executePath(
                    entityId = viewModel.engine.currentState().player.name,
                    path = pathCopy,
                    msPerStep = 160L,
                    onDone = {
                      // После хода — сообщаем Душе
                      val msg = "Герой прибыл в клетку (${dest.first}, ${dest.second})."
                      viewModel.addPlayerMessage(msg)
                      viewModel.getCommand(
                        model = model,
                        instructionText = msg,
                        onDone = {},
                        onError = {},
                      )
                    }
                  )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF005588)),
                modifier = Modifier.weight(1f),
              ) {
                Text("⚡ Идти", color = Color.White)
              }
              OutlinedButton(
                onClick = { viewModel.engine.clearSelection() },
                modifier = Modifier.weight(1f),
              ) {
                Text("✕ Отмена")
              }
            }
            Spacer(modifier = Modifier.height(8.dp))
          }

          // Paginator logic
          var logOffset by remember { mutableIntStateOf(0) }
          val logs = uiState.gameState.battleLog
          // Auto-scroll to bottom when new logs arrive, unless user manually scrolled
          LaunchedEffect(logs.size) { logOffset = 0 }
          
          val maxOffset = maxOf(0, logs.size - 1)
          val currentLogIndex = (logs.size - 1 - logOffset).coerceIn(0, maxOffset)
          val displayedLog = if (logs.isNotEmpty()) logs[currentLogIndex] else "The world is quiet..."

          Row(
            modifier = Modifier.fillMaxWidth()
              .border(4.dp, Color.White, RoundedCornerShape(8.dp))
              .background(Brush.verticalGradient(listOf(Color(0xFF0000AA), Color(0xFF000033))), RoundedCornerShape(8.dp))
              .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
                text = displayedLog, 
                color = Color.White, 
                fontSize = 18.sp, 
                lineHeight = 24.sp,
                modifier = Modifier.weight(1f)
            )
            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // "Up" goes back in history (increases offset)
                IconButton(
                    onClick = { if (logOffset < maxOffset) logOffset++ },
                    modifier = Modifier.size(32.dp).background(Color(0x44FFFFFF), RoundedCornerShape(4.dp))
                ) {
                    Text("▲", color = if (logOffset < maxOffset) Color.White else Color.Gray, fontSize = 16.sp)
                }
                // "Down" goes forward in history (decreases offset)
                IconButton(
                    onClick = { if (logOffset > 0) logOffset-- },
                    modifier = Modifier.size(32.dp).background(Color(0x44FFFFFF), RoundedCornerShape(4.dp))
                ) {
                    Text("▼", color = if (logOffset > 0) Color.White else Color.Gray, fontSize = 16.sp)
                }
            }
          }
        }
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          TextAndVoiceInput(
            task = task,
            processing = uiState.processing,
            holdToDictateViewModel = holdToDictateViewModel,
            modifier = Modifier.padding(start = 16.dp).weight(1f),
            onDone = { text: String -> processInstructionText(text = text) },
            onAmplitudeChanged = { amp: Int -> curAmplitude = amp },
            clearTextTrigger = clearTextTrigger,
            defaultTextInputMode = true,
          )
          Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (uiState.processing) {
              CircularProgressIndicator(trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeWidth = 3.dp, modifier = Modifier.padding(end = 8.dp).size(24.dp))
            } else {
              IconButton(onClick = { showConversationHistoryPanel = true }, modifier = Modifier.padding(end = 8.dp)) {
                Icon(imageVector = Icons.Outlined.History, contentDescription = stringResource(R.string.cd_more_options), tint = Color.White)
              }
            }
          }
        }
      }
      AnimatedVisibility(
        holdToDictateUiState.recognizing,
        enter = fadeIn(animationSpec = tween(150, easing = FastOutSlowInEasing)),
        exit = fadeOut(animationSpec = tween(100, easing = FastOutSlowInEasing, delayMillis = 300)),
      ) {
        VoiceRecognizerOverlay(task = task, viewModel = holdToDictateViewModel, curAmplitude = curAmplitude, bottomPadding = bottomPadding)
      }
      AnimatedVisibility(showConversationHistoryPanel, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        ConversationHistoryPanel(task = task, bottomPadding = bottomPadding, viewModel = viewModel, onDismiss = { showConversationHistoryPanel = false })
      }
    }
  }

  if (showErrorDialog) {
    AlertDialog(
      title = { Text(stringResource(R.string.error)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(errorDialogContent, style = MaterialTheme.typography.bodyMedium)
        }
      },
      onDismissRequest = { showErrorDialog = false; errorDialogContent = "" },
      dismissButton = { TextButton(onClick = { showErrorDialog = false; errorDialogContent = "" }) { Text(stringResource(R.string.cancel)) } },
      confirmButton = {
        Button(
          onClick = {
            showErrorDialog = false; errorDialogContent = ""
          },
          colors = ButtonDefaults.buttonColors(containerColor = taskColor),
        ) { Text(stringResource(R.string.reset), color = Color.White) }
      },
    )
  }
}

/**
 * HUD памяти Души — «комбо» из ходов до перерождения разговора.
 * Сегменты расходуются с каждым ходом; на пороге память Души сбрасывается
 * (мир остаётся), новая беседа стартует с моста памяти. Тап — сменить порог.
 */
@Composable
private fun SoulMemoryHud(
  turns: Int,
  threshold: Int,
  font: androidx.compose.ui.text.font.FontFamily,
  onTap: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val left = (threshold - turns).coerceAtLeast(0)
  val fillColor = when {
    left <= 1 -> Color(0xFFDD2222)
    left * 3 <= threshold -> Color(0xFFDDCC00)
    else -> Color(0xFF44DD44)
  }
  Column(
    horizontalAlignment = Alignment.End,
    modifier =
      modifier
        .background(Color(0xCC000814), RoundedCornerShape(4.dp))
        .border(1.dp, Color(0x5500CFFF), RoundedCornerShape(4.dp))
        .clickable(onClick = onTap)
        .padding(horizontal = 8.dp, vertical = 5.dp),
  ) {
    Text(
      "SOUL MEM ${turns.coerceAtMost(threshold)}/$threshold",
      fontFamily = font,
      fontSize = 10.sp,
      color = Color(0xFFB0E0FF),
    )
    Spacer(modifier = Modifier.height(3.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      val segCount = threshold.coerceAtMost(12)
      val filled =
        if (threshold == 0) 0
        else ((turns.coerceAtMost(threshold)) * segCount + threshold - 1) / threshold
      repeat(segCount) { i ->
        Box(
          modifier =
            Modifier.size(width = 8.dp, height = 6.dp)
              .background(if (i < filled) fillColor else Color(0xFF223344))
        )
      }
    }
  }
}
