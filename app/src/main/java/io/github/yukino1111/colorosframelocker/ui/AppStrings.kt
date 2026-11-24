package io.github.yukino1111.colorosframelocker.ui

import androidx.compose.runtime.staticCompositionLocalOf

enum class AppLanguage(val code: String) {
    ZH("zh"),
    EN("en"),
    ;

    fun toggle(): AppLanguage = if (this == ZH) EN else ZH

    companion object {
        fun fromCode(
            code: String?,
            defaultZh: Boolean,
        ): AppLanguage =
            when (code) {
                ZH.code -> ZH
                EN.code -> EN
                else -> if (defaultZh) ZH else EN
            }
    }
}

data class AppStrings(
    val language: AppLanguage,
    val appTitle: String,
    val languageSwitchLabel: String,
    val languageSwitchCd: String,
    val statusNotBound: String,
    val statusMissingSystem: String,
    val statusActive: String,
    val statusActiveBody: String,
    val liveDisplayTitle: String,
    val frameworkMissingTitle: String,
    val scopeRequiredTitle: String,
    val frameworkMissingBody: String,
    val scopeRequiredBody: String,
    val requestScopeButton: String,
    val scopeApprovedMsg: String,
    val scopeFailedMsg: (String) -> String,
    val enableLockTitle: String,
    val enableLockSubtitle: String,
    val targetRefreshRateTitle: String,
    val autoMaxFormat: (Int) -> String,
    val touchIdleTitle: String,
    val touchIdleSubtitle: String,
    val idleBlockLabel: String,
    val idleAllowFloorLabel: String,
    val idleFloorTitle: String,
    val followSystemLabel: String,
    val thermalTitle: String,
    val thermalSubtitle: String,
    val thermalIgnoreLabel: String,
    val deepPolicyTitle: String,
    val blockAdfrTitle: String,
    val blockAdfrSubtitle: String,
    val blockAppSwitchTitle: String,
    val blockAppSwitchSubtitle: String,
    val ignorePencilTitle: String,
    val ignorePencilSubtitle: String,
    val allowSafetyTitle: String,
    val allowSafetySubtitle: String,
    val respectLowPowerTitle: String,
    val respectLowPowerSubtitle: String,
    val aboutTitle: String,
    val aboutRepoTitle: String,
    val aboutRepoUrl: String,
    val aboutLinkCopied: String,
    val aboutOpenFailed: String,
)

private val ZhStrings = AppStrings(
    language = AppLanguage.ZH,
    appTitle = "ColorOSFrameLocker",
    languageSwitchLabel = "中/EN",
    languageSwitchCd = "切换中英文",
    statusNotBound = "未接入框架",
    statusMissingSystem = "缺少 system",
    statusActive = "已激活",
    statusActiveBody = "已接管 system_server 刷新率策略",
    liveDisplayTitle = "实时屏幕",
    frameworkMissingTitle = "尚未检测到 LSPosed 服务",
    scopeRequiredTitle = "需要勾选系统框架 (system) 作用域",
    frameworkMissingBody = "请在 LSPosed 中启用本模块并勾选「系统框架 (system)」，完成后重启设备生效。",
    scopeRequiredBody = "模块通过 Modern Xposed API 102 在 system_server 中接管刷新率策略。",
    requestScopeButton = "申请勾选系统框架 (system)",
    scopeApprovedMsg = "已申请作用域，请重启设备或按提示生效",
    scopeFailedMsg = { reason -> "作用域申请未通过: $reason" },
    enableLockTitle = "启用全局高刷锁定",
    enableLockSubtitle = "关闭时立即恢复原生刷新率策略",
    targetRefreshRateTitle = "目标刷新率",
    autoMaxFormat = { maxHz -> "自动最高 (${maxHz}Hz)" },
    touchIdleTitle = "TouchIdle 静置策略",
    touchIdleSubtitle = "手离屏幕约 3 秒后保留或阻断硬件降频",
    idleBlockLabel = "禁用降频 (满帧)",
    idleAllowFloorLabel = "允许静置保底",
    idleFloorTitle = "静置保底帧率",
    followSystemLabel = "跟随系统",
    thermalTitle = "高温温控档位",
    thermalSubtitle = "触发高温保护时单独指定上限，或完全无视温控",
    thermalIgnoreLabel = "无视高温",
    deepPolicyTitle = "ColorOS 16 深度策略",
    blockAdfrTitle = "抑制 ADFR 动态降帧",
    blockAdfrSubtitle = "关闭 ADFR 与 FRTC 降频，减少静止掉帧",
    blockAppSwitchTitle = "阻止应用切换降档",
    blockAppSwitchSubtitle = "拦截 AppSwitch 与动画 boost 票据降帧",
    ignorePencilTitle = "忽略手写笔限帧",
    ignorePencilSubtitle = "平板连接手写笔时仍保持目标高刷",
    allowSafetyTitle = "保留 DC 调光与安全保护",
    allowSafetySubtitle = "遇 DC 调光、截屏保护时退让；AOD 与指纹始终优先",
    respectLowPowerTitle = "系统省电自动暂停",
    respectLowPowerSubtitle = "开启系统省电模式时自动暂停锁高刷",
    aboutTitle = "关于",
    aboutRepoTitle = "源代码仓库",
    aboutRepoUrl = "https://github.com/yukino1111/ColorOSFrameLocker",
    aboutLinkCopied = "仓库链接已复制到剪贴板",
    aboutOpenFailed = "未找到可用浏览器，链接已复制到剪贴板",
)

private val EnStrings = AppStrings(
    language = AppLanguage.EN,
    appTitle = "ColorOSFrameLocker",
    languageSwitchLabel = "中/EN",
    languageSwitchCd = "Switch language",
    statusNotBound = "Inactive",
    statusMissingSystem = "No system",
    statusActive = "Active",
    statusActiveBody = "system_server refresh-rate policy hook active",
    liveDisplayTitle = "Live Display",
    frameworkMissingTitle = "LSPosed Service Not Detected",
    scopeRequiredTitle = "System Framework (system) Scope Required",
    frameworkMissingBody = "Enable this module in LSPosed, select System Framework (system), and reboot to take effect.",
    scopeRequiredBody = "This module hooks system_server refresh rate policies via Modern Xposed API 102.",
    requestScopeButton = "Request system Scope",
    scopeApprovedMsg = "Scope requested. Reboot or follow the prompt to apply.",
    scopeFailedMsg = { reason -> "Scope request failed: $reason" },
    enableLockTitle = "Enable Global Refresh Lock",
    enableLockSubtitle = "Restore native refresh rate policy immediately when disabled",
    targetRefreshRateTitle = "Target Refresh Rate",
    autoMaxFormat = { maxHz -> "Auto Max (${maxHz}Hz)" },
    touchIdleTitle = "TouchIdle Policy",
    touchIdleSubtitle = "Keep or block hardware idle drops ~3s after touch ends",
    idleBlockLabel = "Block Drop (Full)",
    idleAllowFloorLabel = "Allow Idle Floor",
    idleFloorTitle = "Idle Floor Rate",
    followSystemLabel = "Follow System",
    thermalTitle = "High-Temp Policy",
    thermalSubtitle = "Set a custom cap or ignore thermal throttling when hot",
    thermalIgnoreLabel = "Ignore Thermal",
    deepPolicyTitle = "ColorOS 16 Policy Tweaks",
    blockAdfrTitle = "Suppress ADFR Dynamic Drop",
    blockAdfrSubtitle = "Disable ADFR and FRTC throttling to prevent idle drops",
    blockAppSwitchTitle = "Block App-Switch Downgrade",
    blockAppSwitchSubtitle = "Intercept AppSwitch and animation boost rate downgrades",
    ignorePencilTitle = "Ignore Stylus Rate Limit",
    ignorePencilSubtitle = "Keep target refresh rate even when a stylus is connected",
    allowSafetyTitle = "Preserve DC Dimming & Safety",
    allowSafetySubtitle = "Yield to DC dimming and secure display; AOD & FOD always win",
    respectLowPowerTitle = "Pause on Battery Saver",
    respectLowPowerSubtitle = "Automatically pause refresh rate locking in Battery Saver mode",
    aboutTitle = "About",
    aboutRepoTitle = "Source Repository",
    aboutRepoUrl = "https://github.com/yukino1111/ColorOSFrameLocker",
    aboutLinkCopied = "Repository URL copied to clipboard",
    aboutOpenFailed = "No browser found, URL copied to clipboard",
)

fun stringsFor(language: AppLanguage): AppStrings =
    when (language) {
        AppLanguage.ZH -> ZhStrings
        AppLanguage.EN -> EnStrings
    }

val LocalAppStrings = staticCompositionLocalOf { ZhStrings }
