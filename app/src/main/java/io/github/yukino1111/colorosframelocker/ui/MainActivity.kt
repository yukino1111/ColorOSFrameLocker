package io.github.yukino1111.colorosframelocker.ui

import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yukino1111.colorosframelocker.BuildConfig
import io.github.yukino1111.colorosframelocker.FrameworkState
import io.github.yukino1111.colorosframelocker.LockerConfig
import io.github.yukino1111.colorosframelocker.PrefsContract
import io.github.yukino1111.colorosframelocker.R
import io.github.yukino1111.colorosframelocker.ScopeRequestResult
import io.github.yukino1111.colorosframelocker.XposedServiceManager
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

            val dark = isSystemInDarkTheme()
            val colors = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            val strings = stringsFor(language)
            MaterialTheme(colorScheme = colors) {
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = strings.appTitle,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "v${BuildConfig.VERSION_NAME} · ${Build.MODEL}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = onToggleLanguage,
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
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    val (label, container) = when {
                        !frameworkState.bound -> strings.statusNotBound to MaterialTheme.colorScheme.errorContainer
                        !frameworkState.hasSystemScope -> strings.statusMissingSystem to MaterialTheme.colorScheme.tertiaryContainer
                        else -> strings.statusActive to MaterialTheme.colorScheme.primaryContainer
                    }
                    Surface(
                        color = container,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.padding(end = 16.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
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
                .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
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
                            style = MaterialTheme.typography.labelLarge,
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
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
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
                    if (frameworkState.frameworkInfo.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                        Text(
                            text = frameworkState.frameworkInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (!frameworkState.bound || !frameworkState.hasSystemScope) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (!frameworkState.bound) {
                                strings.frameworkMissingTitle
                            } else {
                                strings.scopeRequiredTitle
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = if (!frameworkState.bound) {
                                strings.frameworkMissingBody
                            } else {
                                strings.scopeRequiredBody
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        if (frameworkState.bound && !frameworkState.hasSystemScope) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(onClick = onRequestSystemScope) {
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
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
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
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
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

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = strings.touchIdleTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = strings.touchIdleSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ChipFlowRow {
                        FilterChip(
                            selected = config.idlePolicy == PrefsContract.IDLE_BLOCK,
                            onClick = { onUpdateConfig(config.copy(idlePolicy = PrefsContract.IDLE_BLOCK)) },
                            label = { Text(strings.idleBlockLabel) },
                        )
                        FilterChip(
                            selected = config.idlePolicy == PrefsContract.IDLE_ALLOW_FLOOR,
                            onClick = { onUpdateConfig(config.copy(idlePolicy = PrefsContract.IDLE_ALLOW_FLOOR)) },
                            label = { Text(strings.idleAllowFloorLabel) },
                        )
                    }

                    if (config.idlePolicy == PrefsContract.IDLE_ALLOW_FLOOR) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        Text(
                            text = strings.idleFloorTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
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

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = strings.thermalTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = strings.thermalSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
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

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = strings.deepPolicyTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ToggleRow(
                        title = strings.blockAdfrTitle,
                        subtitle = strings.blockAdfrSubtitle,
                        checked = config.blockAdfr,
                        onCheckedChange = { onUpdateConfig(config.copy(blockAdfr = it)) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    ToggleRow(
                        title = strings.blockAppSwitchTitle,
                        subtitle = strings.blockAppSwitchSubtitle,
                        checked = config.blockAppSwitchDowngrade,
                        onCheckedChange = { onUpdateConfig(config.copy(blockAppSwitchDowngrade = it)) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    ToggleRow(
                        title = strings.ignorePencilTitle,
                        subtitle = strings.ignorePencilSubtitle,
                        checked = config.ignorePencilLimit,
                        onCheckedChange = { onUpdateConfig(config.copy(ignorePencilLimit = it)) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    ToggleRow(
                        title = strings.allowSafetyTitle,
                        subtitle = strings.allowSafetySubtitle,
                        checked = config.allowSafetyLimits,
                        onCheckedChange = { onUpdateConfig(config.copy(allowSafetyLimits = it)) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlowRow(
    content: @Composable FlowRowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
