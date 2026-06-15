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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Composable
fun TinyGardenScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  tools: List<ToolProvider>,
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  setTopBarVisible: (Boolean) -> Unit,
  commandFlow: Flow<TinyGardenCommand>, // kept for signature compatibility
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
  LaunchedEffect(showConversationHistoryPanel) { setTopBarVisible(!showConversationHistoryPanel) }

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
          GameRenderer(
            gameState = uiState.gameState,
            engine = viewModel.engine,
            modifier = Modifier.fillMaxWidth().weight(1f).border(2.dp, Color(0xFF223344), RoundedCornerShape(4.dp))
          )
          Spacer(modifier = Modifier.height(16.dp))
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
