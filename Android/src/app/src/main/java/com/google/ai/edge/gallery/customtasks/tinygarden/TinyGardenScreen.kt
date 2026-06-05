/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.delay
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
  commandFlow: Flow<TinyGardenCommand>,
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
          commandFlow = commandFlow,
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

// ── Canvas JRPG renderer — no KorGE, pure Compose ───────────────────────────
@Composable
fun JrpgBattleCanvas(
  jrpgState: JrpgState,
  heroAttacking: Boolean,
  goblinAttacking: Boolean,
  magicFx: Boolean,
  modifier: Modifier = Modifier,
) {
  val heroOffsetX by animateFloatAsState(if (heroAttacking) 80f else 0f, tween(150), label = "hx")
  val goblinOffsetX by animateFloatAsState(if (goblinAttacking) -80f else 0f, tween(150), label = "gx")
  val magicAlpha by animateFloatAsState(if (magicFx) 1f else 0f, tween(300), label = "mx")

  Canvas(modifier = modifier) {
    val w = size.width; val h = size.height
    drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF0D0D2B), Color(0xFF1A1A3A)), 0f, h))
    drawRect(Color(0xFF2A2A5A), Offset(0f, h * 0.72f), Size(w, h * 0.28f))

    // Enemy
    val enemyHpRatio = (jrpgState.enemyHp.toFloat() / jrpgState.enemyMaxHp.toFloat()).coerceIn(0f, 1f)
    translate(w * 0.68f + goblinOffsetX, h * 0.25f) {
      drawRect(Color(0xFF228B22), Offset(-30f, 0f), Size(60f, 90f))
      drawCircle(Color(0xFF32CD32), 28f, Offset(0f, -28f))
      drawCircle(Color.Red, 5f, Offset(-10f, -30f))
      drawCircle(Color.Red, 5f, Offset(10f, -30f))
      drawRect(Color(0xFF333333), Offset(-35f, -70f), Size(70f, 10f))
      drawRect(Color(0xFFCC2222), Offset(-35f, -70f), Size(70f * enemyHpRatio, 10f))
    }

    // Player
    val playerHpRatio = (jrpgState.playerHp.toFloat() / jrpgState.playerMaxHp.toFloat()).coerceIn(0f, 1f)
    val heroBodyColor = if (magicAlpha > 0.5f) Color(0xFFAA44FF) else Color(0xFF2244CC)
    translate(w * 0.22f + heroOffsetX, h * 0.3f) {
      drawRect(Color(0xFF0A0A66), Offset(-25f, 20f), Size(50f, 80f))
      drawRect(heroBodyColor, Offset(-20f, 0f), Size(40f, 75f))
      drawCircle(Color(0xFFFFDDAA), 22f, Offset(0f, -22f))
      drawRect(Color(0xFF333333), Offset(-40f, -65f), Size(80f, 10f))
      drawRect(Color(0xFF22AACC), Offset(-40f, -65f), Size(80f * playerHpRatio, 10f))
    }

    if (magicAlpha > 0.1f) {
      drawCircle(Color(0x55FFFF00).copy(alpha = 0.4f * magicAlpha), 60f * magicAlpha, Offset(w * 0.68f, h * 0.42f))
    }
  }
}

// ── Main UI ──────────────────────────────────────────────────────────────────
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

  // Local animation state — no ViewModel needed
  var heroAttacking by remember { mutableStateOf(false) }
  var goblinAttacking by remember { mutableStateOf(false) }
  var magicFx by remember { mutableStateOf(false) }

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
      viewModel.addMessage(message = ChatMessageText(content = battleText, side = ChatSide.AGENT))
      // Trigger animations locally
      when (command.action) {
        JrpgAction.ATTACK -> { heroAttacking = true; delay(300); heroAttacking = false }
        JrpgAction.MAGIC  -> { magicFx = true; delay(500); magicFx = false }
        JrpgAction.ENEMY_ATTACK -> { goblinAttacking = true; delay(300); goblinAttacking = false }
        else -> {}
      }
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
          JrpgBattleCanvas(
            jrpgState = uiState.jrpgState,
            heroAttacking = heroAttacking,
            goblinAttacking = goblinAttacking,
            magicFx = magicFx,
            modifier = Modifier.fillMaxWidth().weight(1f).border(4.dp, Color.White, RoundedCornerShape(8.dp))
          )
          Spacer(modifier = Modifier.height(16.dp))
          Box(
            modifier = Modifier.fillMaxWidth()
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
          Text(stringResource(R.string.reset_note), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.customColors.warningTextColor)
        }
      },
      onDismissRequest = { showErrorDialog = false; errorDialogContent = "" },
      dismissButton = { TextButton(onClick = { showErrorDialog = false; errorDialogContent = "" }) { Text(stringResource(R.string.cancel)) } },
      confirmButton = {
        Button(
          onClick = {
            showErrorDialog = false; errorDialogContent = ""
            viewModel.resetEngine(context = context, model = model, tools = tools, onError = { errorDialogContent = it; showErrorDialog = true })
          },
          colors = ButtonDefaults.buttonColors(containerColor = taskColor),
        ) { Text(stringResource(R.string.reset), color = Color.White) }
      },
    )
  }
}
