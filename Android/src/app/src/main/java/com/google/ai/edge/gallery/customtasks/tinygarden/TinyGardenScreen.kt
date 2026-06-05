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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.common.getTaskBgGradientColors
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.TextAndVoiceInput
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.VoiceRecognizerOverlay
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** The main screen for the Tiny Garden game. */
@Composable
fun TinyGardenScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  tools: List<ToolProvider>,
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  setTopBarVisible: (Boolean) -> Unit,
  commandFlow: Flow<TinyGardenCommand>,
  viewModel: TinyGardenViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  var recordAudioPermissionGranted by remember { mutableStateOf(false) }
  val context = LocalContext.current

  val recordAudioClipsPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      if (permissionGranted) {
        recordAudioPermissionGranted = true
      }
    }

  LaunchedEffect(Unit) {
    when (PackageManager.PERMISSION_GRANTED) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) -> {
        recordAudioPermissionGranted = true
      }
      else -> {
        recordAudioClipsPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      }
    }
  }

  if (recordAudioPermissionGranted) {
    Column(
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding()
    ) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MainUi(
          task = task,
          modelManagerViewModel = modelManagerViewModel,
          tools = tools,
          bottomPadding = bottomPadding,
          commandFlow = commandFlow,
          viewModel = viewModel,
          setAppBarControlsDisabled = setAppBarControlsDisabled,
          setTopBarVisible = setTopBarVisible,
        )

        if (uiState.resettingEngine) {
          Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
          ) {
            Column(
              verticalArrangement = Arrangement.spacedBy(8.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              CircularProgressIndicator(
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 3.dp,
                modifier = Modifier.size(24.dp),
              )
              Text(
                stringResource(R.string.resetting_engine),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
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
  commandFlow: Flow<TinyGardenCommand>,
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

  LaunchedEffect(Unit) {
    commandFlow.collect { command ->
      val actionName = command.action.name
      val battleText = "Player used ${command.value.ifEmpty { actionName }} on ${command.target} for ${command.damage} damage!"
      viewModel.updateBattleLog(battleText)
      viewModel.applyDamage(command.target, command.damage)
      
      viewModel.addMessage(
        message = ChatMessageText(
            content = battleText,
            side = ChatSide.AGENT,
          )
      )
    }
  }

  val noFunctionCallWarningMessage = stringResource(R.string.warning_no_function_call)
  val noFunctionCallSnackbarMessage = stringResource(R.string.snackbar_no_function_call)

  fun processInstructionText(text: String) {
    clearTextTrigger = System.currentTimeMillis()
    if (text.trim().isNotEmpty()) {
      viewModel.getCommand(
        model = model,
        instructionText = text,
        onDone = { _ ->
          if (uiState.messages.isEmpty() || uiState.messages.last().side != ChatSide.AGENT) {
            viewModel.addMessage(message = ChatMessageWarning(content = noFunctionCallWarningMessage))
            scope.launch { snackbarHostState.showSnackbar(noFunctionCallSnackbarMessage, withDismissAction = true) }
          }
        },
        onError = { error ->
          errorDialogContent = error
          showErrorDialog = true
        },
      )
      firebaseAnalytics?.logEvent(
        GalleryEvent.GENERATE_ACTION.id,
        Bundle().apply {
          putString("capability_name", task.id)
          putString("model_id", model.name)
        },
      )
    }
  }

  if (!modelManagerUiState.isModelInitialized(model = model)) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeWidth = 3.dp,
        modifier = Modifier.size(24.dp),
      )
    }
  } else {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
      Column(
        modifier = Modifier.padding(
            bottom = if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) bottomPadding else 12.dp
          ).fillMaxSize()
      ) {
        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
          // --- ИНТЕГРАЦИЯ KorGE ---
          // Здесь мы запускаем движок поверх Android Jetpack Compose
          androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                // Инжектим поток команд от ИИ в движок
                com.google.ai.edge.gallery.customtasks.tinygarden.korge.GameContext.commandFlow = commandFlow

                // Создаем Korge Android View (запускает OpenGL контекст)
                val korgeView = korlibs.korge.android.KorgeAndroidView(ctx)
                
                // Инициализируем игру
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    korlibs.korge.Korge(
                        view = korgeView,
                        config = korlibs.korge.Korge.Config(
                            module = object : korlibs.korge.scene.Module() {
                                override val mainScene = com.google.ai.edge.gallery.customtasks.tinygarden.korge.JrpgBattleScene::class
                                override suspend fun asyncInjectInjector(injector: korlibs.inject.AsyncInjector) {
                                    injector.mapPrototype { com.google.ai.edge.gallery.customtasks.tinygarden.korge.JrpgBattleScene() }
                                }
                            }
                        )
                    )
                }
                korgeView
            },
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f)
              .border(4.dp, Color.White, RoundedCornerShape(8.dp))
          )
          
          Spacer(modifier = Modifier.height(16.dp))
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .border(4.dp, Color.White, RoundedCornerShape(8.dp))
              .background(Brush.verticalGradient(listOf(Color(0xFF0000AA), Color(0xFF000033))), RoundedCornerShape(8.dp))
              .padding(16.dp)
          ) {
            Text(uiState.jrpgState.battleLog, color = Color.White, fontSize = 18.sp, lineHeight = 24.sp)
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
              CircularProgressIndicator(
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 3.dp,
                modifier = Modifier.padding(end = 8.dp).size(24.dp),
              )
            } else {
              IconButton(
                onClick = { showConversationHistoryPanel = true },
                modifier = Modifier.padding(end = 8.dp),
              ) {
                Icon(
                  imageVector = Icons.Outlined.History,
                  contentDescription = stringResource(R.string.cd_more_options),
                  tint = Color.White
                )
              }
            }
          }
        }
      }

      AnimatedVisibility(
        holdToDictateUiState.recognizing,
        enter = fadeIn(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)),
        exit = fadeOut(animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing, delayMillis = 300)),
      ) {
        VoiceRecognizerOverlay(
          task = task,
          viewModel = holdToDictateViewModel,
          curAmplitude = curAmplitude,
          bottomPadding = bottomPadding,
        )
      }

      AnimatedVisibility(
        showConversationHistoryPanel,
        enter = slideInVertically { fullHeight -> fullHeight },
        exit = slideOutVertically { fullHeight -> fullHeight },
      ) {
        ConversationHistoryPanel(
          task = task,
          bottomPadding = bottomPadding,
          viewModel = viewModel,
          onDismiss = { showConversationHistoryPanel = false },
        )
      }
    }
  }

  if (showErrorDialog) {
    AlertDialog(
      title = { Text(stringResource(R.string.error)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(errorDialogContent, style = MaterialTheme.typography.bodyMedium)
          Text(
            stringResource(R.string.reset_note),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.customColors.warningTextColor,
          )
        }
      },
      onDismissRequest = {
        showErrorDialog = false
        errorDialogContent = ""
      },
      dismissButton = {
        TextButton(onClick = { showErrorDialog = false; errorDialogContent = "" }) {
          Text(stringResource(R.string.cancel))
        }
      },
      confirmButton = {
        Button(
          onClick = {
            showErrorDialog = false
            errorDialogContent = ""
            viewModel.resetEngine(
              context = context,
              model = model,
              tools = tools,
              onError = {
                errorDialogContent = it
                showErrorDialog = true
              },
            )
          },
          colors = ButtonDefaults.buttonColors(containerColor = taskColor),
        ) {
          Text(stringResource(R.string.reset), color = Color.White)
        }
      },
    )
  }
}
