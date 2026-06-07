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

package com.google.ai.edge.gallery.ui.home

// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme
// import com.google.ai.edge.gallery.ui.preview.PreviewModelManagerViewModel
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import com.google.ai.edge.gallery.GalleryTopAppBar
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.CategoryInfo
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.TaskIcon
import com.google.ai.edge.gallery.ui.common.rememberDelayedAnimationProgress
import com.google.ai.edge.gallery.ui.common.tos.AppTosDialog
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGHomeScreen"
private const val TASK_COUNT_ANIMATION_DURATION = 250
private const val ANIMATION_INIT_DELAY = 0L
private const val TOP_APP_BAR_ANIMATION_DURATION = 600
private const val TITLE_FIRST_LINE_ANIMATION_DURATION = 600
private const val TITLE_SECOND_LINE_ANIMATION_DURATION = 600
private const val TITLE_SECOND_LINE_ANIMATION_DURATION2 = 800
private const val TITLE_SECOND_LINE_ANIMATION_START =
  ANIMATION_INIT_DELAY + (TITLE_FIRST_LINE_ANIMATION_DURATION * 0.5).toInt()
private const val TASK_LIST_ANIMATION_START = TITLE_SECOND_LINE_ANIMATION_START + 110
private const val TASK_CARD_ANIMATION_DELAY_OFFSET = 100
private const val TASK_CARD_ANIMATION_DURATION = 600
private const val CONTENT_COMPOSABLES_ANIMATION_DURATION = 1200
private const val CONTENT_COMPOSABLES_OFFSET_Y = 16

/** Navigation destination data */
private object HomeScreenDestination {
  @StringRes val titleRes = R.string.app_name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  modelManagerViewModel: ModelManagerViewModel,
  tosViewModel: TosViewModel,
  navigateToTaskScreen: (Task) -> Unit,
  onModelsClicked: () -> Unit,
  enableAnimation: Boolean,
  modifier: Modifier = Modifier,
  gm4: Boolean = false,
) {
  val uiState by modelManagerViewModel.uiState.collectAsState()
  val sortedCategories = remember(uiState.tasks) {
    uiState.tasks.map { it.category }.distinctBy { it.id }.sortedBy { it.id }
  }
  var selectedCategoryIndex by remember { mutableIntStateOf(0) }
  var showSettingsDialog by remember { mutableStateOf(false) }
  var showTosDialog by remember { mutableStateOf(!tosViewModel.getIsTosAccepted()) }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current

  // Show home screen content when TOS has been accepted.
  if (!showTosDialog) {
    var loadingModelAllowlistDelayed by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.loadingModelAllowlist) {
      if (uiState.loadingModelAllowlist) {
        delay(200)
        if (uiState.loadingModelAllowlist) {
          loadingModelAllowlistDelayed = true
        }
      } else {
        loadingModelAllowlistDelayed = false
      }
    }

    if (loadingModelAllowlistDelayed) {
      Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        CircularProgressIndicator(
          trackColor = MaterialTheme.colorScheme.surfaceVariant,
          strokeWidth = 3.dp,
          modifier = Modifier.padding(end = 8.dp).size(20.dp),
        )
        Text(
          stringResource(R.string.loading_model_list),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
    if (!loadingModelAllowlistDelayed && !uiState.loadingModelAllowlist) {
      val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

      val requestPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

      LaunchedEffect(Unit) {
        delay(2000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
              PackageManager.PERMISSION_GRANTED
          ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
          }
        }
      }

      BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }

      ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
          ModalDrawerSheet {
            Column(modifier = Modifier.padding(16.dp)) {
              Row(modifier = Modifier.fillMaxWidth()) {
                SquareDrawerItem(
                  label = stringResource(R.string.drawer_settings_label),
                  description = stringResource(R.string.drawer_settings_description),
                  icon = Icons.Rounded.Settings,
                  onClick = {
                    showSettingsDialog = true
                    scope.launch { drawerState.close() }
                  },
                  modifier = Modifier.weight(1f),
                  iconBrush =
                    linearGradient(
                      colors =
                        listOf(
                          MaterialTheme.customColors.taskBgGradientColors[2][0],
                          MaterialTheme.customColors.taskBgGradientColors[2][1],
                        )
                    ),
                )
                Spacer(modifier = Modifier.width(16.dp))
                SquareDrawerItem(
                  label = stringResource(R.string.drawer_models_label),
                  description = stringResource(R.string.drawer_models_description),
                  icon = Icons.AutoMirrored.Rounded.ListAlt,
                  onClick = {
                    scope.launch { drawerState.close() }
                    scope.launch {
                      delay(50)
                      onModelsClicked()
                    }
                  },
                  modifier = Modifier.weight(1f),
                  iconBrush =
                    linearGradient(
                      colors =
                        listOf(
                          MaterialTheme.customColors.taskBgGradientColors[1][0],
                          MaterialTheme.customColors.taskBgGradientColors[1][1],
                        )
                    ),
                )
              }
            }
          }
        },
        gesturesEnabled = drawerState.isOpen,
      ) {
        Scaffold(
          containerColor = MaterialTheme.colorScheme.background,
          topBar = {
            val progress =
              if (!enableAnimation) 1f
              else
                rememberDelayedAnimationProgress(
                  initialDelay = ANIMATION_INIT_DELAY - 50,
                  animationDurationMs = TOP_APP_BAR_ANIMATION_DURATION,
                  animationLabel = "top bar",
                )
            Box(
              modifier =
                Modifier.graphicsLayer {
                  alpha = progress
                  translationY = ((-16).dp * (1 - progress)).toPx()
                }
            ) {
              GalleryTopAppBar(
                title = "PROJ BLUE GARDEN",
                leftAction =
                  AppBarAction(
                    actionType = AppBarActionType.MENU,
                    actionFn = {
                      scope.launch { drawerState.apply { if (isClosed) open() else close() } }
                    },
                  ),
              )
            }
          },
        ) { innerPadding ->
          Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
          ) {
            Box(
              contentAlignment = Alignment.TopCenter,
              modifier =
                Modifier.fillMaxSize()
                  .padding(top = innerPadding.calculateTopPadding())
                  .verticalScroll(rememberScrollState()),
            ) {
              Column(modifier = Modifier.fillMaxWidth()) {
                // PROJ☆BLUE Header
                Column(
                  modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 40.dp)
                    .fillMaxWidth(),
                  horizontalAlignment = Alignment.CenterHorizontally
                ) {
                  Text(
                    text = "PROJ BLUE",
                    style = MaterialTheme.typography.headlineLarge.copy(
                      fontWeight = FontWeight.ExtraBold,
                      letterSpacing = 8.sp,
                      color = MaterialTheme.colorScheme.primary,
                      shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black,
                        offset = androidx.compose.ui.geometry.Offset(4f, 4f),
                        blurRadius = 8f
                      )
                    )
                  )
                  Text(
                    text = "GARDEN INTERFACE",
                    style = MaterialTheme.typography.labelSmall.copy(
                      letterSpacing = 2.sp,
                      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                  )
                  Spacer(modifier = Modifier.height(16.dp))
                  Box(
                    modifier = Modifier
                      .height(1.dp)
                      .fillMaxWidth(0.3f)
                      .background(
                        Brush.horizontalGradient(
                          listOf(Color.Transparent, MaterialTheme.colorScheme.primary, Color.Transparent)
                        )
                      )
                  )
                }

                // Unified Task Grid
                val filteredTasksByCategories: Map<String, List<Task>> = remember(uiState.tasksByCategory) {
                  uiState.tasksByCategory.mapValues { (_, tasks) ->
                    tasks.filter { it.id == BuiltInTaskId.LLM_CHAT || it.id == BuiltInTaskId.LLM_TINY_GARDEN }
                  }.filterValues { it.isNotEmpty() }
                }
                val filteredCategories: List<CategoryInfo> = remember(sortedCategories) {
                  sortedCategories.filter { filteredTasksByCategories.containsKey(it.id) }
                }

                val pagerState = rememberPagerState(pageCount = { filteredCategories.size })
                LaunchedEffect(pagerState.settledPage) {
                  selectedCategoryIndex = pagerState.settledPage
                }
                
                if (filteredCategories.size > 1) {
                  CategoryTabHeader(
                    sortedCategories = filteredCategories,
                    selectedIndex = selectedCategoryIndex,
                    enableAnimation = enableAnimation,
                    onCategorySelected = { index ->
                      selectedCategoryIndex = index
                      scope.launch { pagerState.animateScrollToPage(page = index) }
                    },
                  )
                }

                TaskList(
                  modelManagerViewModel = modelManagerViewModel,
                  pagerState = pagerState,
                  sortedCategories = filteredCategories,
                  tasksByCategories = filteredTasksByCategories,
                  enableAnimation = enableAnimation,
                  navigateToTaskScreen = navigateToTaskScreen,
                  gm4 = false, // Disable promo layout
                  grid = false, // Single tile looks better without grid
                )

                Spacer(modifier = Modifier.height(innerPadding.calculateBottomPadding() + 40.dp))
              }
            }

            Box(
              modifier =
                Modifier.fillMaxWidth()
                  .height(innerPadding.calculateBottomPadding())
                  .background(
                    Brush.verticalGradient(
                      colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surface)
                    )
                  )
                  .align(Alignment.BottomCenter)
            )
          }
        }
      }
    }
  }

  if (showTosDialog) {
    AppTosDialog(
      onTosAccepted = {
        showTosDialog = false
        tosViewModel.acceptTos()
      }
    )
  }

  if (showSettingsDialog) {
    SettingsDialog(
      curThemeOverride = modelManagerViewModel.readThemeOverride(),
      modelManagerViewModel = modelManagerViewModel,
      onDismissed = { showSettingsDialog = false },
    )
  }

  // PROJ_BLUE: model list error dialog disabled (offline mode)
}

@Composable
private fun CategoryTabHeader(
  sortedCategories: List<CategoryInfo>,
  selectedIndex: Int,
  enableAnimation: Boolean,
  onCategorySelected: (Int) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()

  val progress =
    if (!enableAnimation) 1f
    else
      rememberDelayedAnimationProgress(
        initialDelay = TASK_LIST_ANIMATION_START,
        animationDurationMs = CONTENT_COMPOSABLES_ANIMATION_DURATION,
        animationLabel = "task card animation",
      )

  LazyRow(
    state = listState,
    modifier =
      Modifier.fillMaxWidth().padding(bottom = 32.dp).graphicsLayer {
        alpha = progress
        translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
      },
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item(key = "spacer_start") { Spacer(modifier = Modifier.width(8.dp)) }
    itemsIndexed(items = sortedCategories) { index, category ->
      Row(
        modifier =
          Modifier.height(40.dp)
            .clip(CircleShape)
            .background(
              color =
                if (selectedIndex == index) MaterialTheme.customColors.tabHeaderBgColor
                else Color.Transparent
            )
            .clickable {
              onCategorySelected(index)

              scope.launch {
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                val targetItem = visibleItems.find {
                  it.index == index + 1
                }
                if (
                  targetItem == null ||
                    targetItem.offset < 0 ||
                    targetItem.offset + targetItem.size > listState.layoutInfo.viewportSize.width
                ) {
                  listState.animateScrollToItem(index = index)
                }
              }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        Text(
          getCategoryLabel(context = context, category = category),
          modifier = Modifier.padding(horizontal = 16.dp),
          style = MaterialTheme.typography.labelLarge,
          color =
            if (selectedIndex == index) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    item(key = "spacer_end") { Spacer(modifier = Modifier.width(8.dp)) }
  }
}

@Composable
private fun TaskList(
  modelManagerViewModel: ModelManagerViewModel,
  pagerState: PagerState,
  sortedCategories: List<CategoryInfo>,
  tasksByCategories: Map<String, List<Task>>,
  enableAnimation: Boolean,
  navigateToTaskScreen: (Task) -> Unit,
  gm4: Boolean = false,
  grid: Boolean = false,
) {
  val progress =
    if (!enableAnimation) 1f
    else
      rememberDelayedAnimationProgress(
        initialDelay = TASK_LIST_ANIMATION_START,
        animationDurationMs = CONTENT_COMPOSABLES_ANIMATION_DURATION,
        animationLabel = "task card animation",
      )

  var initialAnimationDone by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    delay(((TASK_CARD_ANIMATION_DURATION + TASK_CARD_ANIMATION_DELAY_OFFSET) * 5).toLong())
    initialAnimationDone = true
  }

  HorizontalPager(
    state = pagerState,
    verticalAlignment = Alignment.Top,
    contentPadding = PaddingValues(horizontal = 20.dp),
  ) { pageIndex ->
    val tasks = tasksByCategories[sortedCategories[pageIndex].id]!!
    if (grid) {
      Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
          Modifier.fillMaxWidth().padding(4.dp).graphicsLayer {
            translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
          },
      ) {
        for (i in tasks.indices step 2) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            TaskCard(
              task = tasks[i],
              index = i,
              animate =
                (pageIndex == 0 || pageIndex == 1) && !initialAnimationDone && enableAnimation,
              onClick = { navigateToTaskScreen(tasks[i]) },
              modifier = Modifier.weight(1f),
              square = true,
            )

            if (i + 1 < tasks.size) {
              TaskCard(
                task = tasks[i + 1],
                index = i + 1,
                animate =
                  (pageIndex == 0 || pageIndex == 1) && !initialAnimationDone && enableAnimation,
                onClick = { navigateToTaskScreen(tasks[i + 1]) },
                modifier = Modifier.weight(1f),
                square = true,
              )
            } else {
              Spacer(modifier = Modifier.weight(1f))
            }
          }
        }
      }
    } else {
      Column(
        modifier =
          Modifier.fillMaxWidth().padding(4.dp).graphicsLayer {
            translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
          },
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        for ((index, task) in tasks.withIndex()) {
          TaskCard(
            task = task,
            index = index,
            animate =
              (pageIndex == 0 || pageIndex == 1) && !initialAnimationDone && enableAnimation,
            onClick = { navigateToTaskScreen(task) },
            modifier = Modifier.fillMaxWidth(),
            square = false,
          )
        }
      }
    }
  }
}

@Composable
private fun TaskCard(
  task: Task,
  index: Int,
  animate: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  description: String = "",
  square: Boolean = false,
) {
  val modelCount by remember {
    derivedStateOf {
      val trigger = task.updateTrigger.value
      if (trigger >= 0) {
        task.models.size
      } else {
        0
      }
    }
  }
  val modelCountLabel by remember {
    derivedStateOf {
      when (modelCount) {
        1 -> "1 Model"
        else -> "%d Models".format(modelCount)
      }
    }
  }
  var curModelCountLabel by remember { mutableStateOf("") }
  var modelCountLabelVisible by remember { mutableStateOf(true) }

  LaunchedEffect(modelCountLabel) {
    if (curModelCountLabel.isEmpty()) {
      curModelCountLabel = modelCountLabel
    } else {
      modelCountLabelVisible = false
      delay(TASK_COUNT_ANIMATION_DURATION.toLong())
      curModelCountLabel = modelCountLabel
      modelCountLabelVisible = true
    }
  }

  val progress =
    if (animate)
      rememberDelayedAnimationProgress(
        initialDelay = TASK_LIST_ANIMATION_START + index * TASK_CARD_ANIMATION_DELAY_OFFSET,
        animationDurationMs = TASK_CARD_ANIMATION_DURATION,
        animationLabel = "task card animation",
      )
    else 1f

  val cbTask = stringResource(R.string.cd_task_card, task.label, task.models.size)
  Card(
    modifier =
      modifier
        .clip(RoundedCornerShape(0.dp))
        .clickable(onClick = onClick)
        .graphicsLayer { alpha = progress }
        .semantics { contentDescription = cbTask },
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.customColors.taskCardBgColor
      ),
  ) {
    if (square) {
      Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        TaskIcon(task = task, width = 40.dp)
        Column() {
          Text(
            curModelCountLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            modifier = Modifier.clearAndSetSemantics {},
          )
          Text(
            task.label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            task.shortDescription,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 14.sp),
            modifier = Modifier.clearAndSetSemantics {},
            minLines = 2,
            maxLines = 2,
            autoSize =
              TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 12.sp, stepSize = 1.sp),
          )
        }
      }
    } else {
      Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        if (description.isNotEmpty()) {
          TaskIcon(task = task, width = 40.dp)

          Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                task.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
              )
              if (task.newFeature) {
                Box(
                  modifier =
                    Modifier.offset(y = (-6).dp, x = 6.dp)
                      .clip(RoundedCornerShape(8.dp))
                      .background(MaterialTheme.customColors.newFeatureContainerColor)
                      .padding(horizontal = 12.dp)
                      .height(26.dp),
                  contentAlignment = Alignment.Center,
                ) {
                  Text(
                    "New",
                    color = MaterialTheme.customColors.newFeatureTextColor,
                    style = MaterialTheme.typography.labelLarge,
                  )
                }
              }
            }
            Text(
              description,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style =
                MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
              modifier = Modifier.clearAndSetSemantics {},
            )
          }
        } else {
          Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                task.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
              )
              if (task.experimental) {
                Icon(
                  painter = painterResource(R.drawable.ic_experiment),
                  contentDescription = "Experimental",
                  modifier = Modifier.size(20.dp).padding(start = 4.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Text(
              curModelCountLabel,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.clearAndSetSemantics {},
            )
          }

          TaskIcon(task = task, width = 40.dp)
        }
      }
    }
  }
}

private fun getCategoryLabel(context: Context, category: CategoryInfo): String {
  val stringRes = category.labelStringRes
  val label = category.label
  if (stringRes != null) {
    return context.getString(stringRes)
  } else if (label != null) {
    return label
  }
  return context.getString(R.string.category_unlabeled)
}
