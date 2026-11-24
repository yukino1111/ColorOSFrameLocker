package io.github.yukino1111.colorosframelocker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.yukino1111.colorosframelocker.BuildConfig
import io.github.yukino1111.colorosframelocker.FrameworkState
import io.github.yukino1111.colorosframelocker.LockerConfig
import io.github.yukino1111.colorosframelocker.PrefsContract
import io.github.yukino1111.colorosframelocker.R
import io.github.yukino1111.colorosframelocker.ScopeRequestResult
import io.github.yukino1111.colorosframelocker.XposedServiceManager
import io.github.yukino1111.colorosframelocker.ui.theme.FrameLockerTheme
import java.util.Locale
import kotlin.math.roundToInt

private data class DisplayStatusSnapshot(
    val currentHz: Float = 0f,
    val activeModeId: Int = -1,
    val width: Int = 0,
    val height: Int = 0,
    val supportedHzOptions: List<Int> = listOf(144, 120, 90, 60),
)

class MainActivity : ComponentActivity() {
    private var config by mutableStateOf(LockerConfig())
    private var displaySnapshot by mutableStateOf(DisplayStatusSnapshot())
    private var language by mutableStateOf(AppLanguage.ZH)

    private val uiPrefs by lazy {
        getSharedPreferences(UI_PREFS_NAME, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        XposedServiceManager.ensureRegistered()
        language = AppLanguage.fromCode(
            code = uiPrefs.getString(KEY_LANGUAGE, null),
            defaultZh = Locale.getDefault().language.startsWith("zh"),
        )
        config = LockerConfig.from(
            XposedServiceManager.currentRemotePrefs()
                ?: getSharedPreferences(PrefsContract.PREFS_NAME, MODE_PRIVATE),
        )
        refreshDisplaySnapshot()

        setContent {
            val frameworkState by XposedServiceManager.frameworkState.collectAsState()
            val activeRemotePrefs by XposedServiceManager.remotePrefs.collectAsState()
            LaunchedEffect(activeRemotePrefs) {
                activeRemotePrefs?.let { prefs ->
                    config = LockerConfig.from(prefs)
                }
            }

            val strings = stringsFor(language)
            FrameLockerTheme {
                CompositionLocalProvider(LocalAppStrings provides strings) {
                    FrameLockerScreen(
                        config = config,
                        frameworkState = frameworkState,
                        displaySnapshot = displaySnapshot,
                        onToggleLanguage = ::toggleLanguage,
                        onUpdateConfig = ::saveConfig,
                        onRequestSystemScope = XposedServiceManager::requestSystemScope,
                        onRefreshDisplay = ::refreshDisplaySnapshot,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        XposedServiceManager.refreshScope()
        refreshDisplaySnapshot()
    }

    private fun toggleLanguage() {
        val next = language.toggle()
        language = next
        uiPrefs.edit().putString(KEY_LANGUAGE, next.code).apply()
    }

    private fun refreshDisplaySnapshot() {
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val mode = display.mode
        val sameResRates = display.supportedModes
            .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .map { it.refreshRate.roundToInt() }
            .distinct()
            .sortedDescending()
        val fallbackRates = display.supportedModes
            .map { it.refreshRate.roundToInt() }
            .distinct()
            .sortedDescending()

        displaySnapshot = DisplayStatusSnapshot(
            currentHz = mode.refreshRate,
            activeModeId = mode.modeId,
            width = mode.physicalWidth,
            height = mode.physicalHeight,
            supportedHzOptions = sameResRates.ifEmpty { fallbackRates.ifEmpty { listOf(144, 120, 90, 60) } },
        )
    }

    private fun saveConfig(updated: LockerConfig) {
        config = updated
        writeToPrefs(getSharedPreferences(PrefsContract.PREFS_NAME, MODE_PRIVATE), updated)
        XposedServiceManager.currentRemotePrefs()?.let { writeToPrefs(it, updated) }
    }

    private fun writeToPrefs(prefs: SharedPreferences, cfg: LockerConfig) {
        prefs.edit()
            .putBoolean(PrefsContract.KEY_ENABLED, cfg.enabled)
            .putInt(PrefsContract.KEY_TARGET_HZ_MODE, cfg.targetHzMode)
            .putInt(PrefsContract.KEY_IDLE_POLICY, cfg.idlePolicy)
            .putInt(PrefsContract.KEY_IDLE_FLOOR_HZ, cfg.idleFloorHz)
            .putInt(PrefsContract.KEY_THERMAL_MODE, cfg.thermalMode)
            .putBoolean(PrefsContract.KEY_BLOCK_ADFR, cfg.blockAdfr)
            .putBoolean(PrefsContract.KEY_BLOCK_APP_SWITCH_DOWNGRADE, cfg.blockAppSwitchDowngrade)
            .putBoolean(PrefsContract.KEY_IGNORE_PENCIL_LIMIT, cfg.ignorePencilLimit)
            .putBoolean(PrefsContract.KEY_ALLOW_SAFETY_LIMITS, cfg.allowSafetyLimits)
            .putBoolean(PrefsContract.KEY_RESPECT_LOW_POWER, cfg.respectLowPower)
            .apply()
    }

    companion object {
        private const val UI_PREFS_NAME = "ui_prefs"
        private const val KEY_LANGUAGE = "app_language"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun FrameLockerScreen(
    config: LockerConfig,
    frameworkState: FrameworkState,
    displaySnapshot: DisplayStatusSnapshot,
    onToggleLanguage: () -> Unit,
    onUpdateConfig: (LockerConfig) -> Unit,
    onRequestSystemScope: () -> Unit,
    onRefreshDisplay: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val motionScheme = MaterialTheme.motionScheme
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showAboutDialog by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val dm = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    onRefreshDisplay()
                }
            }
        }
        dm?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose {
            dm?.unregisterDisplayListener(listener)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                scrollBehavior = scrollBehavior,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {},
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        showAboutDialog = true
                                    },
                                ),
                        ) {
                            Text(
                                text = strings.appTitle,
                                style = MaterialTheme.typography.titleLargeEmphasized,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "v${BuildConfig.VERSION_NAME} · ${Build.MODEL}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        FilledTonalButton(
                            onClick = onToggleLanguage,
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .semantics { contentDescription = strings.languageSwitchCd },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_translate_24),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = strings.languageSwitchLabel,
                                style = MaterialTheme.typography.labelMediumEmphasized,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .animateContentSize(animationSpec = motionScheme.defaultSpatialSpec()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val isActive = frameworkState.bound && frameworkState.hasSystemScope
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isActive) R.drawable.ic_check_circle_24 else R.drawable.ic_warning_24,
                            ),
                            contentDescription = null,
                            tint = if (isActive) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    isActive -> strings.statusActive
                                    !frameworkState.bound -> strings.frameworkMissingTitle
                                    else -> strings.scopeRequiredTitle
                                },
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                },
                            )
                            Text(
                                text = when {
                                    isActive -> {
                                        if (frameworkState.frameworkInfo.isNotEmpty()) {
                                            "${strings.statusActiveBody} · ${frameworkState.frameworkInfo}"
                                        } else {
                                            strings.statusActiveBody
                                        }
                                    }
                                    !frameworkState.bound -> strings.frameworkMissingBody
                                    else -> strings.scopeRequiredBody
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                },
                            )
                        }
                    }
                    if (frameworkState.bound && !frameworkState.hasSystemScope) {
                        Button(
                            onClick = onRequestSystemScope,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(strings.requestScopeButton)
                        }
                    }
                    frameworkState.scopeResult?.let { result ->
                        val msg = when (result) {
                            ScopeRequestResult.Approved -> strings.scopeApprovedMsg
                            is ScopeRequestResult.Failed -> strings.scopeFailedMsg(result.reason)
                        }
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = strings.liveDisplayTitle,
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "${displaySnapshot.width} × ${displaySnapshot.height}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = "${displaySnapshot.currentHz.roundToInt()} Hz",
                            style = MaterialTheme.typography.displaySmallEmphasized,
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = displaySnapshot.supportedHzOptions.joinToString(" / ") { "$it" } + " Hz",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ToggleRow(
                        title = strings.enableLockTitle,
                        subtitle = strings.enableLockSubtitle,
                        checked = config.enabled,
                        onCheckedChange = { onUpdateConfig(config.copy(enabled = it)) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        text = strings.targetRefreshRateTitle,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ChipFlowRow {
                        FilterChip(
                            selected = config.targetHzMode == PrefsContract.TARGET_HZ_AUTO_MAX,
                            onClick = {
                                onUpdateConfig(config.copy(targetHzMode = PrefsContract.TARGET_HZ_AUTO_MAX))
                            },
                            label = {
                                val maxHz = displaySnapshot.supportedHzOptions.firstOrNull() ?: 144
                                Text(strings.autoMaxFormat(maxHz))
                            },
                        )
                        displaySnapshot.supportedHzOptions.forEach { hz ->
                            FilterChip(
                                selected = config.targetHzMode == hz,
                                onClick = { onUpdateConfig(config.copy(targetHzMode = hz)) },
                                label = { Text("${hz}Hz") },
                            )
                        }
                    }
                }
            }

            val effectiveTargetHz = if (config.targetHzMode > PrefsContract.TARGET_HZ_AUTO_MAX) {
                config.targetHzMode
            } else {
                displaySnapshot.supportedHzOptions.firstOrNull() ?: 144
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(animationSpec = motionScheme.defaultSpatialSpec()),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = strings.touchIdleTitle,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = strings.touchIdleSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val toggleColors = ToggleButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        checkedContainerColor = MaterialTheme.colorScheme.primary,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    ) {
                        ToggleButton(
                            checked = config.idlePolicy == PrefsContract.IDLE_BLOCK,
                            onCheckedChange = {
                                onUpdateConfig(config.copy(idlePolicy = PrefsContract.IDLE_BLOCK))
                            },
                            modifier = Modifier.weight(1f),
                            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                            colors = toggleColors,
                        ) {
                            Text(
                                text = strings.idleBlockLabel,
                                style = MaterialTheme.typography.labelLargeEmphasized,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ToggleButton(
                            checked = config.idlePolicy == PrefsContract.IDLE_ALLOW_FLOOR,
                            onCheckedChange = {
                                onUpdateConfig(config.copy(idlePolicy = PrefsContract.IDLE_ALLOW_FLOOR))
                            },
                            modifier = Modifier.weight(1f),
                            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                            colors = toggleColors,
                        ) {
                            Text(
                                text = strings.idleAllowFloorLabel,
                                style = MaterialTheme.typography.labelLargeEmphasized,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = config.idlePolicy == PrefsContract.IDLE_ALLOW_FLOOR,
                        enter = expandVertically(animationSpec = motionScheme.defaultSpatialSpec()) +
                            fadeIn(animationSpec = motionScheme.defaultEffectsSpec()),
                        exit = shrinkVertically(animationSpec = motionScheme.fastSpatialSpec()) +
                            fadeOut(animationSpec = motionScheme.fastEffectsSpec()),
                    ) {
                        Column {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                            Text(
                                text = strings.idleFloorTitle,
                                style = MaterialTheme.typography.titleSmallEmphasized,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val floorChoices = displaySnapshot.supportedHzOptions
                                .filter { it <= effectiveTargetHz }
                                .ifEmpty { displaySnapshot.supportedHzOptions }
                            ChipFlowRow {
                                FilterChip(
                                    selected = config.idleFloorHz == PrefsContract.IDLE_FLOOR_NATIVE,
                                    onClick = {
                                        onUpdateConfig(config.copy(idleFloorHz = PrefsContract.IDLE_FLOOR_NATIVE))
                                    },
                                    label = { Text(strings.followSystemLabel) },
                                )
                                floorChoices.forEach { hz ->
                                    FilterChip(
                                        selected = config.idleFloorHz == hz,
                                        onClick = { onUpdateConfig(config.copy(idleFloorHz = hz)) },
                                        label = { Text("${hz}Hz") },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = strings.thermalTitle,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = strings.thermalSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val thermalChoices = displaySnapshot.supportedHzOptions
                        .filter { it in 60 until effectiveTargetHz }
                    ChipFlowRow {
                        FilterChip(
                            selected = config.thermalMode == PrefsContract.THERMAL_FOLLOW_SYSTEM,
                            onClick = {
                                onUpdateConfig(config.copy(thermalMode = PrefsContract.THERMAL_FOLLOW_SYSTEM))
                            },
                            label = { Text(strings.followSystemLabel) },
                        )
                        thermalChoices.forEach { hz ->
                            FilterChip(
                                selected = config.thermalMode == hz,
                                onClick = { onUpdateConfig(config.copy(thermalMode = hz)) },
                                label = { Text("${hz}Hz") },
                            )
                        }
                        FilterChip(
                            selected = config.thermalMode == PrefsContract.THERMAL_IGNORE,
                            onClick = {
                                onUpdateConfig(config.copy(thermalMode = PrefsContract.THERMAL_IGNORE))
                            },
                            label = { Text(strings.thermalIgnoreLabel) },
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = strings.deepPolicyTitle,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ToggleRow(
                        title = strings.blockAdfrTitle,
                        subtitle = strings.blockAdfrSubtitle,
                        checked = config.blockAdfr,
                        onCheckedChange = { onUpdateConfig(config.copy(blockAdfr = it)) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    ToggleRow(
                        title = strings.blockAppSwitchTitle,
                        subtitle = strings.blockAppSwitchSubtitle,
                        checked = config.blockAppSwitchDowngrade,
                        onCheckedChange = { onUpdateConfig(config.copy(blockAppSwitchDowngrade = it)) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    ToggleRow(
                        title = strings.ignorePencilTitle,
                        subtitle = strings.ignorePencilSubtitle,
                        checked = config.ignorePencilLimit,
                        onCheckedChange = { onUpdateConfig(config.copy(ignorePencilLimit = it)) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    ToggleRow(
                        title = strings.allowSafetyTitle,
                        subtitle = strings.allowSafetySubtitle,
                        checked = config.allowSafetyLimits,
                        onCheckedChange = { onUpdateConfig(config.copy(allowSafetyLimits = it)) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    ToggleRow(
                        title = strings.respectLowPowerTitle,
                        subtitle = strings.respectLowPowerSubtitle,
                        checked = config.respectLowPower,
                        onCheckedChange = { onUpdateConfig(config.copy(respectLowPower = it)) },
                    )
                }
            }
        }
    }

    if (showAboutDialog) {
        FrameLockerAboutDialog(onDismiss = { showAboutDialog = false })
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FrameLockerAboutDialog(onDismiss: () -> Unit) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val appIconBitmap = remember(context) {
        context.packageManager.getApplicationIcon(context.applicationInfo)
            .toBitmap(width = 144, height = 144)
            .asImageBitmap()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.aboutTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        bitmap = appIconBitmap,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.appTitle,
                            style = MaterialTheme.typography.titleLargeEmphasized,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelMediumEmphasized,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                    }
                }

                Card(
                    shape = MaterialTheme.shapes.large,
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .combinedClickable(
                            onClick = {
                                onDismiss()
                                openRepoUrl(context, strings.aboutRepoUrl, strings.aboutOpenFailed)
                            },
                            onLongClick = {
                                copyToClipboard(context, "ColorOSFrameLocker_Repo", strings.aboutRepoUrl)
                                Toast.makeText(context, strings.aboutLinkCopied, Toast.LENGTH_SHORT).show()
                            },
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                        ) {
                            Text(
                                text = strings.aboutRepoTitle,
                                style = MaterialTheme.typography.labelLargeEmphasized,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = strings.aboutRepoUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Icon(
                            painter = painterResource(R.drawable.ic_open_in_new_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
}

private fun openRepoUrl(context: Context, url: String, fallbackMsg: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    val opened = runCatching { context.startActivity(intent) }.isSuccess
    if (!opened) {
        copyToClipboard(context, "ColorOSFrameLocker_Repo", url)
        Toast.makeText(context, fallbackMsg, Toast.LENGTH_SHORT).show()
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlowRow(
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmallEmphasized)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
