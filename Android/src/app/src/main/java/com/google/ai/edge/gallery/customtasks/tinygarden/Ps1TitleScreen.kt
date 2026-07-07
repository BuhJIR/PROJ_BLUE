package com.google.ai.edge.gallery.customtasks.tinygarden

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelImportDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelImportingDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

/**
 * PS1-титульник — точка входа приложения.
 *
 * Убирает весь ритуал инициализации: раньше игрок шёл через список режимов →
 * верхнее меню → плюсик импорта → переключатель tinygarden → выбор задачи.
 * Теперь: запустил приложение → [IMPORT MODEL] один раз → [START] → игра.
 * Импорт с титульника принудительно включает поддержку игры у модели.
 */

private val TitleRed = Color(0xFF8B0000)
private val TitleRedBright = Color(0xFFC41E3A)
private val MenuWhite = Color(0xFFE8E8E8)
private val MenuGrey = Color(0xFF5A5A5A)

@Composable
fun Ps1TitleScreen(
  modelManagerViewModel: ModelManagerViewModel,
  onStartGame: (Model) -> Unit,
  onOpenGallery: () -> Unit,
  gameViewModel: TinyGardenViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val uiState by modelManagerViewModel.uiState.collectAsState()
  var saveExists by remember { mutableStateOf(gameViewModel.hasSave()) }

  // Модели, доступные игре. Чтение updateTrigger/modelImportingUpdateTrigger
  // подписывает композицию на появление свежеимпортированной модели.
  val gameTask = uiState.tasks.firstOrNull { it.id == BuiltInTaskId.LLM_TINY_GARDEN }
  @Suppress("UNUSED_EXPRESSION") gameTask?.updateTrigger?.value
  @Suppress("UNUSED_EXPRESSION") uiState.modelImportingUpdateTrigger
  val models: List<Model> = gameTask?.models ?: emptyList()
  var modelIdx by remember { mutableIntStateOf(0) }
  if (modelIdx >= models.size) modelIdx = 0
  val selectedModel = models.getOrNull(modelIdx)

  val pixelFont = remember { FontFamily(Font(R.font.pixel_04b03)) }

  // ── Импорт модели прямо с титульника ─────────────────────────────────────
  var showImportDialog by remember { mutableStateOf(false) }
  var showImportingDialog by remember { mutableStateOf(false) }
  var showUnsupportedFileTypeDialog by remember { mutableStateOf(false) }
  val selectedUri = remember { mutableStateOf<Uri?>(null) }
  val importedInfo = remember { mutableStateOf<ImportedModel?>(null) }

  val filePickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == android.app.Activity.RESULT_OK) {
        result.data?.data?.let { uri ->
          val fileName = queryFileName(context, uri)
          if (fileName != null && !fileName.endsWith(".task") && !fileName.endsWith(".litertlm")) {
            showUnsupportedFileTypeDialog = true
          } else {
            selectedUri.value = uri
            showImportDialog = true
          }
        }
      }
    }

  val openFilePicker = {
    filePickerLauncher.launch(
      Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
      }
    )
  }

  val blink by
    rememberInfiniteTransition(label = "blink")
      .animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec =
          infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
          ),
        label = "blinkAlpha",
      )

  Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
    Column(
      modifier = Modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(modifier = Modifier.weight(1f))

      // ── Логотип ──────────────────────────────────────────────────────────
      Text(
        "PROJ BLUE",
        fontFamily = pixelFont,
        fontSize = 46.sp,
        letterSpacing = 6.sp,
        color = TitleRed,
        style = TextStyle(shadow = Shadow(color = TitleRedBright.copy(alpha = 0.6f), blurRadius = 24f)),
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        "GARDEN INTERFACE",
        fontFamily = pixelFont,
        fontSize = 13.sp,
        letterSpacing = 9.sp,
        color = TitleRedBright,
      )
      Spacer(modifier = Modifier.height(14.dp))
      Box(
        modifier =
          Modifier.width(220.dp)
            .height(2.dp)
            .background(TitleRed.copy(alpha = 0.55f))
      )

      Spacer(modifier = Modifier.weight(1f))

      // ── Меню ─────────────────────────────────────────────────────────────
      Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        if (saveExists) {
          Ps1MenuItem(
            label = "CONTINUE",
            font = pixelFont,
            enabled = selectedModel != null,
            cursorAlpha = if (selectedModel != null) blink else 0f,
            onClick = { selectedModel?.let(onStartGame) },
          )
        }
        Ps1MenuItem(
          label = "NEW GAME",
          font = pixelFont,
          enabled = selectedModel != null,
          cursorAlpha = if (selectedModel != null && !saveExists) blink else 0f,
          onClick = {
            selectedModel?.let { model ->
              gameViewModel.startNewGame()
              saveExists = false
              onStartGame(model)
            }
          },
        )
        if (models.size > 1) {
          Ps1MenuItem(
            label = "MODEL: ${selectedModel?.name?.uppercase()?.take(22) ?: "-"}",
            font = pixelFont,
            enabled = true,
            cursorAlpha = 0f,
            onClick = { modelIdx = (modelIdx + 1) % models.size },
          )
        }
        Ps1MenuItem(
          label = "IMPORT MODEL",
          font = pixelFont,
          enabled = true,
          cursorAlpha = if (selectedModel == null) blink else 0f,
          onClick = { openFilePicker() },
        )
        Ps1MenuItem(
          label = "GALLERY",
          font = pixelFont,
          enabled = true,
          cursorAlpha = 0f,
          onClick = onOpenGallery,
        )
      }

      Spacer(modifier = Modifier.height(28.dp))

      if (models.isEmpty()) {
        Text(
          "NO MODEL FOUND\nIMPORT A .task / .litertlm FILE",
          fontFamily = pixelFont,
          fontSize = 11.sp,
          letterSpacing = 2.sp,
          lineHeight = 20.sp,
          color = TitleRedBright.copy(alpha = 0.4f + 0.6f * blink),
          textAlign = TextAlign.Center,
        )
      }

      Spacer(modifier = Modifier.weight(1.2f))

      Text(
        "THE WORLD LIVES INSIDE YOUR PHONE",
        fontFamily = pixelFont,
        fontSize = 9.sp,
        letterSpacing = 3.sp,
        color = MenuGrey,
        modifier = Modifier.padding(bottom = 26.dp),
      )
    }

    // ── CRT-скан-линии поверх всего ─────────────────────────────────────────
    Canvas(modifier = Modifier.fillMaxSize()) {
      var y = 0f
      while (y < size.height) {
        drawRect(
          color = Color(0x28000000),
          topLeft = Offset(0f, y),
          size = Size(size.width, 1.6f),
        )
        y += 4f
      }
    }
  }

  // ── Диалоги импорта (общие с менеджером моделей) ──────────────────────────
  if (showImportDialog) {
    selectedUri.value?.let { uri ->
      ModelImportDialog(
        uri = uri,
        onDismiss = { showImportDialog = false },
        onDone = { info ->
          // Импорт с титульника всегда предназначен для игры — включаем
          // поддержку tinygarden независимо от переключателя в диалоге
          importedInfo.value =
            info.toBuilder()
              .setLlmConfig(info.llmConfig.toBuilder().setSupportTinyGarden(true).build())
              .build()
          showImportDialog = false
          showImportingDialog = true
        },
      )
    }
  }

  if (showImportingDialog) {
    selectedUri.value?.let { uri ->
      importedInfo.value?.let { info ->
        ModelImportingDialog(
          uri = uri,
          info = info,
          onDismiss = { showImportingDialog = false },
          onDone = {
            modelManagerViewModel.addImportedLlmModel(info = it)
            showImportingDialog = false
          },
        )
      }
    }
  }

  if (showUnsupportedFileTypeDialog) {
    AlertDialog(
      onDismissRequest = { showUnsupportedFileTypeDialog = false },
      title = { Text("Unsupported file type") },
      text = { Text("Only \".task\" or \".litertlm\" file type is supported.") },
      confirmButton = {
        TextButton(onClick = { showUnsupportedFileTypeDialog = false }) { Text("OK") }
      },
    )
  }
}

@Composable
private fun Ps1MenuItem(
  label: String,
  font: FontFamily,
  enabled: Boolean,
  cursorAlpha: Float,
  onClick: () -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      "▶",
      fontFamily = font,
      fontSize = 15.sp,
      color = TitleRedBright,
      modifier = Modifier.alpha(cursorAlpha).padding(end = 12.dp),
    )
    Text(
      label,
      fontFamily = font,
      fontSize = 19.sp,
      letterSpacing = 4.sp,
      color = if (enabled) MenuWhite else MenuGrey,
      modifier =
        Modifier.clickable(enabled = enabled, onClick = onClick)
          .padding(vertical = 4.dp),
    )
  }
}

private fun queryFileName(context: Context, uri: Uri): String? =
  context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
  }
