package io.github.yukino1111.colorosframelocker.hook

import android.content.Context
import android.database.ContentObserver
import android.graphics.Point
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.SparseArray
import android.view.Display
import io.github.yukino1111.colorosframelocker.LockerConfig
import io.github.yukino1111.colorosframelocker.PrefsContract
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Core ColorOS 16 refresh rate policy & vote hooks in `system_server`.
 * Resolves the target mode dynamically from DisplayInfo.supportedModes + active display resolution
 * so one APK works across ColorOS 16 phones (120/144/165Hz) and tablets (144Hz).
 */
internal class RefreshRatePolicyHooks(
    private val configProvider: () -> LockerConfig,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    // OplusRefreshRatePolicyImpl
    private var policyImplClass: Class<*>? = null
    private var fieldContext: Field? = null
    private var fieldDisplayId: Field? = null
    private var fieldDisplayInfo: Field? = null
    private var fieldLock: Field? = null
    private var fieldService: Field? = null
    private var fieldConfigs: Field? = null
    private var fieldSettingsObserver: Field? = null
    private var fieldIpePencilState: Field? = null
    private var methodGetCurrentResolution: Method? = null
    private var methodGetConfigListChangeListener: Method? = null
    private var methodOnConfigListChange: Method? = null

    // android.view.DisplayInfo (hidden API accessed via reflection)
    private var displayInfoClass: Class<*>? = null
    private var fieldDisplayInfoState: Field? = null
    private var fieldDisplayInfoSupportedModes: Field? = null

    // OplusRefreshRateCore & Vote
    private var fieldCore: Field? = null
    private var fieldCoreVotes: Field? = null
    private var fieldCoreActiveVotes: Field? = null
    private var methodUpdateVoteLocked: Method? = null
    private var voteClass: Class<*>? = null
    private var methodForRefreshRate: Method? = null
    private var fieldVoteRefreshRate: Field? = null

    // OplusRefreshRateUtils
    private var methodGetRefreshRateIdByRate: Method? = null

    // PreferredRefreshRateData
    private var methodSetAppReqFirst: Method? = null
    private var methodEnableWinOverride: Method? = null
    private var methodSetDisableViewOverride: Method? = null
    private var methodSetLowFeqMode: Method? = null
    private var methodSetBrightnessBlock: Method? = null
    private var methodSetAppLowRefreshRate: Method? = null
    private var methodPutUsrOverrideRefreshRateId: Method? = null

    @Volatile
    private var defaultPolicy: Any? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var systemLowPowerOn: Boolean = false

    @Volatile
    private var observerRegistered: Boolean = false

    @Volatile
    private var lastTargetRateInt: Int = 144

    @Volatile
    private var coreInstance: Any? = null

    @Volatile
    private var lastNativeThermalVote: Any? = null

    @Volatile
    private var touchIdleBridge: TouchIdleBridge? = null

    private val pendingNotifyRunnable = Runnable { performConfigListChange() }

    fun install(loader: ClassLoader) {
        val policyCls = HookRegistry.findClassOrNull("com.android.server.wm.OplusRefreshRatePolicyImpl", loader)
        val coreCls = HookRegistry.findClassOrNull("com.android.server.wm.OplusRefreshRateCore", loader)
        val vCls = HookRegistry.findClassOrNull("com.android.server.wm.OplusRefreshRateCore\$Vote", loader)
        val utilsCls = HookRegistry.findClassOrNull("com.android.server.wm.OplusRefreshRateUtils", loader)
        val dataCls = HookRegistry.findClassOrNull(
            "com.android.server.wm.OplusRefreshRateConstants\$PreferredRefreshRateData",
            loader,
        )
        val dmServiceCls = HookRegistry.findClassOrNull("com.android.server.wm.OplusDisplayModeService", loader)
        val configsCls = HookRegistry.findClassOrNull("com.android.server.wm.OplusRefreshRateConfigs", loader)
        val listenerCls = HookRegistry.findClassOrNull(
            "com.android.server.wm.OplusRefreshRateConfigs\$ConfigListChangeListener",
            loader,
        )
        val settingsObsCls = HookRegistry.findClassOrNull(
            "com.android.server.wm.OplusRefreshRatePolicyImpl\$SettingsObserver",
            loader,
        )
        val adfrCls = HookRegistry.findClassOrNull("com.android.server.wm.OplusAdfrV32Controller", loader)
        val dInfoCls = HookRegistry.findClassOrNull("android.view.DisplayInfo", loader)
        displayInfoClass = dInfoCls
        if (dInfoCls != null) {
            fieldDisplayInfoState = HookRegistry.optionalField(dInfoCls, "state")
            fieldDisplayInfoSupportedModes = HookRegistry.optionalField(dInfoCls, "supportedModes")
        }

        if (policyCls == null || coreCls == null || vCls == null || utilsCls == null || dataCls == null) {
            HookRegistry.logW("Missing core ColorOS refresh rate classes; hooks skipped")
            return
        }

        policyImplClass = policyCls
        fieldContext = HookRegistry.optionalField(policyCls, "mContext")
        fieldDisplayId = HookRegistry.optionalField(policyCls, "mDisplayId")
        fieldDisplayInfo = HookRegistry.optionalField(policyCls, "mDisplayInfo")
        fieldLock = HookRegistry.optionalField(policyCls, "mLock")
        fieldService = HookRegistry.optionalField(policyCls, "sService")
        fieldConfigs = HookRegistry.optionalField(policyCls, "mConfigs")
        fieldSettingsObserver = HookRegistry.optionalField(policyCls, "mSettingsObserver")
        fieldCore = HookRegistry.optionalField(policyCls, "mCore")

        fieldCoreVotes = HookRegistry.optionalField(coreCls, "mVotes")
        fieldCoreActiveVotes = HookRegistry.optionalField(coreCls, "mActiveVotes")
        methodUpdateVoteLocked = HookRegistry.optionalMethod(
            coreCls,
            "updateVoteLocked",
            Int::class.javaPrimitiveType!!,
            vCls,
        )

        if (settingsObsCls != null) {
            fieldIpePencilState = HookRegistry.optionalField(settingsObsCls, "mIpePencilState")
        }
        if (dmServiceCls != null) {
            methodGetCurrentResolution = HookRegistry.optionalMethod(dmServiceCls, "getCurrentResolution")
        }
        if (configsCls != null) {
            methodGetConfigListChangeListener =
                HookRegistry.optionalMethod(configsCls, "getConfigListChangeListener")
        }
        if (listenerCls != null) {
            methodOnConfigListChange = HookRegistry.optionalMethod(listenerCls, "onConfigListChange")
        }

        voteClass = vCls
        methodForRefreshRate = HookRegistry.optionalMethod(vCls, "forRefreshRate", Int::class.javaPrimitiveType!!)
        fieldVoteRefreshRate = HookRegistry.optionalField(vCls, "mRefreshRate")

        methodGetRefreshRateIdByRate =
            HookRegistry.optionalMethod(utilsCls, "getRefreshRateIdByRate", Float::class.javaPrimitiveType!!)

        methodSetAppReqFirst = HookRegistry.optionalMethod(dataCls, "setAppReqFirst", Boolean::class.javaPrimitiveType!!)
        methodEnableWinOverride =
            HookRegistry.optionalMethod(dataCls, "enableWinOverride", Boolean::class.javaPrimitiveType!!)
        methodSetDisableViewOverride =
            HookRegistry.optionalMethod(dataCls, "setDisableViewOverride", Boolean::class.javaPrimitiveType!!)
        methodSetLowFeqMode = HookRegistry.optionalMethod(dataCls, "setLowFeqMode", Boolean::class.javaPrimitiveType!!)
        methodSetBrightnessBlock =
            HookRegistry.optionalMethod(dataCls, "setBrightnessBlock", Boolean::class.javaPrimitiveType!!)
        methodSetAppLowRefreshRate =
            HookRegistry.optionalMethod(dataCls, "setAppLowRefreshRate", Int::class.javaPrimitiveType!!)
        methodPutUsrOverrideRefreshRateId = HookRegistry.optionalMethod(
            dataCls,
            "putUsrOverrideRefreshRateId",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )

        val idleBridge = TouchIdleBridge(
            configProvider = configProvider,
            targetRateProvider = { lastTargetRateInt },
            lowPowerBlockedProvider = { isLowPowerBlocked() },
            displayStateProvider = { resolveDisplayState(defaultPolicy) },
            policyBlockingProvider = { hasPolicyBlockingState(defaultPolicy) },
        )
        touchIdleBridge = idleBridge
        idleBridge.install(loader)

        installPolicyHooks(policyCls)
        installCoreHooks(coreCls)
        if (adfrCls != null) {
            installAdfrHooks(adfrCls)
        }

        HookRegistry.logI("RefreshRatePolicyHooks installed successfully")
    }

    fun onConfigChanged() {
        refreshSystemLowPowerState()
        syncThermalVoteIfNeeded()
        touchIdleBridge?.reapply()
        scheduleConfigListChange()
    }

    private fun installPolicyHooks(policyCls: Class<*>) {
        HookRegistry.optionalMethod(policyCls, "onSystemReady")?.let { m ->
            HookRegistry.install("policy.onSystemReady", m) { chain ->
                val res = chain.proceed()
                bindPolicyIfDefaultDisplay(chain.thisObject)
                refreshSystemLowPowerState()
                touchIdleBridge?.reapply()
                scheduleConfigListChange()
                res
            }
        }

        HookRegistry.optionalMethod(
            policyCls,
            "getPreferredModeId",
            Float::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )?.let { m ->
            HookRegistry.install("policy.getPreferredModeId", m) { chain ->
                val policy = chain.thisObject
                bindPolicyIfDefaultDisplay(policy)
                val target = resolveTargetIfEligible(policy)
                if (target != null && !isPencilBlocked(policy)) {
                    return@install target.id
                }
                chain.proceed()
            }
        }

        HookRegistry.optionalMethod(policyCls, "getPreferredMinRefreshRate", Float::class.javaPrimitiveType!!)?.let { m ->
            HookRegistry.install("policy.getPreferredMinRefreshRate", m) { chain ->
                val policy = chain.thisObject
                val target = resolveTargetIfEligible(policy)
                if (target != null && !isPencilBlocked(policy)) {
                    return@install target.refreshRate
                }
                chain.proceed()
            }
        }

        HookRegistry.optionalMethod(policyCls, "getPreferredMaxRefreshRate", Float::class.javaPrimitiveType!!)?.let { m ->
            HookRegistry.install("policy.getPreferredMaxRefreshRate", m) { chain ->
                val policy = chain.thisObject
                val target = resolveTargetIfEligible(policy)
                if (target != null && !isPencilBlocked(policy)) {
                    return@install target.refreshRate
                }
                chain.proceed()
            }
        }

        displayInfoClass?.let { dInfoCls ->
            HookRegistry.optionalMethod(
                policyCls,
                "getFinalDisplayModeIdLocked",
                dInfoCls,
                Point::class.java,
            )?.let { m ->
                HookRegistry.install("policy.getFinalDisplayModeIdLocked", m) { chain ->
                    val policy = chain.thisObject
                    bindPolicyIfDefaultDisplay(policy)
                    val displayInfo = chain.getArg(0)
                    val resolution = chain.getArg(1) as? Point
                    val target = resolveTargetIfEligible(policy, displayInfo, resolution)
                    if (target != null && !isPencilBlocked(policy)) {
                        return@install target.id
                    }
                    chain.proceed()
                }
            }
        }

        HookRegistry.optionalMethod(
            policyCls,
            "getPreferredRefreshRateData",
            String::class.java,
            String::class.java,
        )?.let { m ->
            HookRegistry.install("policy.getPreferredRefreshRateData", m) { chain ->
                val data = chain.proceed() ?: return@install null
                val policy = chain.thisObject
                val target = resolveTargetIfEligible(policy) ?: return@install data
                if (isPencilBlocked(policy)) return@install data

                val rateId = getRateIdForRate(target.refreshRate)
                runCatching {
                    methodSetAppReqFirst?.invoke(data, false)
                    methodEnableWinOverride?.invoke(data, false)
                    methodSetDisableViewOverride?.invoke(data, true)
                    methodSetLowFeqMode?.invoke(data, false)
                    methodSetBrightnessBlock?.invoke(data, false)
                    methodSetAppLowRefreshRate?.invoke(data, 0)
                    if (rateId != null && rateId > 0) {
                        for (slot in 0..3) {
                            methodPutUsrOverrideRefreshRateId?.invoke(data, slot, rateId)
                        }
                    }
                }
                data
            }
        }

        HookRegistry.optionalMethod(
            policyCls,
            "applyVrrScene",
            Int::class.javaPrimitiveType!!,
            Display.Mode::class.java,
            Boolean::class.javaPrimitiveType!!,
        )?.let { m ->
            HookRegistry.install("policy.applyVrrScene", m) { chain ->
                val policy = chain.thisObject
                val target = resolveTargetIfEligible(policy)
                if (target != null && !isPencilBlocked(policy)) {
                    val args = chain.args.toTypedArray()
                    val modeObj = findDisplayModeObject(policy, target.id)
                    if (modeObj != null) {
                        args[1] = modeObj
                        return@install chain.proceed(args)
                    }
                }
                chain.proceed()
            }
        }
    }

    private fun installCoreHooks(coreCls: Class<*>) {
        val vCls = voteClass ?: return
        methodUpdateVoteLocked?.let { m ->
            HookRegistry.install("core.updateVoteLocked", m) { chain ->
                coreInstance = chain.thisObject
                val args = chain.args.toTypedArray()
                val priority = (args.getOrNull(0) as? Int) ?: return@install chain.proceed()
                val vote = args.getOrNull(1)

                if (priority == PRIORITY_HIGH_TEMP) {
                    lastNativeThermalVote = vote
                }

                // Preserve null vote clears unconditionally, and wake TouchIdle when FOD/AOD/thermal clears.
                if (vote == null) {
                    val res = chain.proceed()
                    if (priority == PRIORITY_FOD_SHOW || priority == PRIORITY_AOD || priority == PRIORITY_HIGH_TEMP) {
                        mainHandler.post { touchIdleBridge?.reapply() }
                    }
                    return@install res
                }

                if (!isControlActive() ||
                    !isDisplayStateOn(defaultPolicy) ||
                    touchIdleBridge?.isFodActive() == true ||
                    isNativeMustKeepPriority(priority)
                ) {
                    val res = chain.proceed()
                    if (isNativeMustKeepPriority(priority)) {
                        mainHandler.post { touchIdleBridge?.reapply() }
                    }
                    return@install res
                }

                val policy = defaultPolicy
                val cfg = configProvider()
                val scoped = resolveScopedModes(policy)
                val baseMode = RefreshRateTargetResolver.pickTargetMode(scoped, cfg.targetHzMode)

                if (priority == PRIORITY_HIGH_TEMP) {
                    val rewritten = resolveThermalRewriteVote(
                        nativeVote = vote,
                        baseMode = baseMode,
                        thermalMode = cfg.thermalMode,
                        scopedModes = scoped,
                    )
                    val res = if (rewritten != null) {
                        args[1] = rewritten
                        chain.proceed(args)
                    } else {
                        chain.proceed()
                    }
                    mainHandler.post { touchIdleBridge?.reapply() }
                    return@install res
                }

                if (cfg.allowSafetyLimits && isSafetyPriority(priority)) {
                    val res = chain.proceed()
                    mainHandler.post { touchIdleBridge?.reapply() }
                    return@install res
                }

                val targetRate = resolveTargetIfEligible(policy)?.refreshRate
                    ?: return@install chain.proceed()

                val shouldRewrite = when {
                    isAppSwitchBoostPriority(priority) -> {
                        val voteRate = readVoteRate(vote)
                        cfg.blockAppSwitchDowngrade && voteRate > 0f && voteRate < targetRate - 0.5f
                    }
                    isUserOrAppPriority(priority) -> true
                    !cfg.allowSafetyLimits && isSafetyPriority(priority) -> true
                    else -> false
                }

                if (shouldRewrite) {
                    val targetVote = buildTargetVote(targetRate)
                    if (targetVote != null) {
                        args[1] = targetVote
                        return@install chain.proceed(args)
                    }
                }
                chain.proceed()
            }
        }

        HookRegistry.optionalMethod(
            coreCls,
            "getPerfectRefreshRate",
            SparseArray::class.java,
            Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
        )?.let { m ->
            HookRegistry.install("core.getPerfectRefreshRate", m) { chain ->
                coreInstance = chain.thisObject
                val votes = chain.getArg(0) as? SparseArray<*>
                val target = resolveTargetIfEligible(defaultPolicy, explicitVotes = votes)
                    ?: return@install chain.proceed()
                target.refreshRate
            }
        }
    }

    private fun installAdfrHooks(adfrCls: Class<*>) {
        HookRegistry.optionalMethod(adfrCls, "setAdfrDirectorState", Int::class.javaPrimitiveType!!)?.let { m ->
            HookRegistry.install("adfr.setAdfrDirectorState", m) { chain ->
                if (configProvider().blockAdfr && resolveTargetIfEligible(defaultPolicy) != null) {
                    val args = chain.args.toTypedArray()
                    args[0] = 1
                    return@install chain.proceed(args)
                }
                chain.proceed()
            }
        }

        HookRegistry.optionalMethod(
            adfrCls,
            "adjustFrtcModeIfNeed",
            Display.Mode::class.java,
            Boolean::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )?.let { m ->
            HookRegistry.install("adfr.adjustFrtcModeIfNeed", m) { chain ->
                if (configProvider().blockAdfr && resolveTargetIfEligible(defaultPolicy) != null) {
                    val args = chain.args.toTypedArray()
                    args[2] = 2
                    return@install chain.proceed(args)
                }
                chain.proceed()
            }
        }
    }

    private fun bindPolicyIfDefaultDisplay(policy: Any?) {
        if (policy == null) return
        val displayId = runCatching { fieldDisplayId?.getInt(policy) }.getOrNull() ?: return
        if (displayId != Display.DEFAULT_DISPLAY) return

        if (defaultPolicy !== policy) {
            defaultPolicy = policy
        }

        if (appContext == null) {
            appContext = runCatching { fieldContext?.get(policy) as? Context }.getOrNull()
        }

        if (!observerRegistered) {
            val ctx = appContext
            if (ctx != null) {
                registerSettingsObserver(ctx)
            }
        }
    }

    private fun registerSettingsObserver(context: Context) {
        observerRegistered = true
        refreshSystemLowPowerState()
        runCatching {
            val resolver = context.contentResolver
            val observer = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    refreshSystemLowPowerState()
                    touchIdleBridge?.reapply()
                    scheduleConfigListChange()
                }
            }
            resolver.registerContentObserver(
                Settings.Global.getUriFor("low_power"),
                false,
                observer,
            )
            resolver.registerContentObserver(
                Settings.Secure.getUriFor("oplus_customize_screen_resolution_adjust"),
                false,
                observer,
            )
        }.onFailure {
            observerRegistered = false
            HookRegistry.logW("Failed registering Settings observer", it)
        }
    }

    private fun refreshSystemLowPowerState() {
        val ctx = appContext ?: return
        val lowPower = runCatching {
            Settings.Global.getInt(ctx.contentResolver, "low_power", 0) == 1
        }.getOrDefault(false)
        systemLowPowerOn = lowPower
    }

    private fun isLowPowerBlocked(): Boolean {
        return configProvider().respectLowPower && systemLowPowerOn
    }

    private fun isControlActive(): Boolean {
        return configProvider().enabled && !isLowPowerBlocked() && !isPencilBlocked(defaultPolicy)
    }

    private fun resolveTargetIfEligible(
        policy: Any?,
        explicitDisplayInfo: Any? = null,
        explicitResolution: Point? = null,
        explicitVotes: SparseArray<*>? = null,
    ): DisplayModeInfo? {
        if (policy == null || !isControlActive()) return null
        val displayId = runCatching { fieldDisplayId?.getInt(policy) }.getOrNull() ?: return null
        if (displayId != Display.DEFAULT_DISPLAY) return null
        if (isPencilBlocked(policy)) return null
        if (touchIdleBridge?.isFodActive() == true) return null

        val info = explicitDisplayInfo
            ?: runCatching { fieldDisplayInfo?.get(policy) }.getOrNull()
            ?: return null

        val state = runCatching { fieldDisplayInfoState?.getInt(info) }.getOrNull()
        if (state != null && state != Display.STATE_ON) {
            return null
        }

        val scoped = resolveScopedModes(policy, info, explicitResolution)
        if (scoped.isEmpty()) return null

        val cfg = configProvider()
        val baseMode = RefreshRateTargetResolver.pickTargetMode(scoped, cfg.targetHzMode) ?: return null
        val effectiveMode = applyVoteConstraints(policy, scoped, baseMode, cfg, explicitVotes) ?: return null

        val rounded = kotlin.math.round(effectiveMode.refreshRate).toInt()
        if (rounded > 0) {
            lastTargetRateInt = rounded
        }
        return effectiveMode
    }

    private fun resolveScopedModes(
        policy: Any?,
        explicitDisplayInfo: Any? = null,
        explicitResolution: Point? = null,
    ): List<DisplayModeInfo> {
        if (policy == null) return emptyList()
        val info = explicitDisplayInfo
            ?: runCatching { fieldDisplayInfo?.get(policy) }.getOrNull()
            ?: return emptyList()
        val modes = runCatching { fieldDisplayInfoSupportedModes?.get(info) as? Array<*> }.getOrNull()
        val allModes = RefreshRateTargetResolver.fromDisplayModes(modes)
        if (allModes.isEmpty()) return emptyList()
        val resolution = explicitResolution ?: resolveCurrentResolution()
        return RefreshRateTargetResolver.modesForResolution(allModes, resolution)
    }

    private fun resolveDisplayState(policy: Any?): Int? {
        val targetPolicy = policy ?: return null
        val info = runCatching { fieldDisplayInfo?.get(targetPolicy) }.getOrNull() ?: return null
        return runCatching { fieldDisplayInfoState?.getInt(info) }.getOrNull()
    }

    private fun isDisplayStateOn(policy: Any?): Boolean {
        val state = resolveDisplayState(policy) ?: return true
        return state == Display.STATE_ON
    }

    private fun hasPolicyBlockingState(policy: Any?): Boolean {
        if (!isControlActive()) return true
        if (policy == null) return false
        return resolveTargetIfEligible(policy) == null
    }

    private fun resolveCurrentResolution(): Point? {
        return runCatching {
            val service = fieldService?.get(null) ?: return null
            methodGetCurrentResolution?.invoke(service) as? Point
        }.getOrNull()
    }

    private fun findDisplayModeObject(policy: Any, modeId: Int): Display.Mode? {
        val info = runCatching { fieldDisplayInfo?.get(policy) }.getOrNull() ?: return null
        val modes = runCatching { fieldDisplayInfoSupportedModes?.get(info) as? Array<*> }.getOrNull() ?: return null
        return modes.filterIsInstance<Display.Mode>().firstOrNull { it.modeId == modeId }
    }

    private fun isPencilBlocked(policy: Any?): Boolean {
        if (policy == null || configProvider().ignorePencilLimit) return false
        val obs = runCatching { fieldSettingsObserver?.get(policy) }.getOrNull() ?: return false
        val state = runCatching { fieldIpePencilState?.getInt(obs) }.getOrNull() ?: 0
        return state == 1
    }

    private fun applyVoteConstraints(
        policy: Any,
        scoped: List<DisplayModeInfo>,
        baseMode: DisplayModeInfo,
        cfg: LockerConfig,
        explicitVotes: SparseArray<*>?,
    ): DisplayModeInfo? {
        val voteMaps = listOfNotNull(
            explicitVotes,
            readCoreVotes(policy, useActiveVotes = true),
            readCoreVotes(policy, useActiveVotes = false),
        )
        val baseRate = baseMode.refreshRate
        var thermalCapHz: Int? = null

        for (votes in voteMaps) {
            for (i in 0 until votes.size()) {
                val priority = votes.keyAt(i)
                val vote = votes.valueAt(i) ?: continue
                if (isNativeMustKeepPriority(priority)) {
                    return null
                }
                if (cfg.allowSafetyLimits && isSafetyPriority(priority)) {
                    val voteRate = readVoteRate(vote)
                    if (voteRate > 0f && voteRate < baseRate - 0.5f) {
                        return null
                    }
                }
                if (priority == PRIORITY_HIGH_TEMP) {
                    when (cfg.thermalMode) {
                        PrefsContract.THERMAL_FOLLOW_SYSTEM -> {
                            val effectiveVote = lastNativeThermalVote ?: vote
                            val thermalRate = readVoteRate(effectiveVote)
                            if (thermalRate > 0f && thermalRate < baseRate - 0.5f) {
                                return null
                            }
                        }
                        PrefsContract.THERMAL_IGNORE -> Unit
                        else -> if (cfg.thermalMode > 0) {
                            thermalCapHz = minOf(thermalCapHz ?: cfg.thermalMode, cfg.thermalMode)
                        }
                    }
                }
            }
        }

        val capHz = thermalCapHz ?: return baseMode
        val baseHz = kotlin.math.round(baseRate).toInt()
        if (capHz >= baseHz) return baseMode
        return RefreshRateTargetResolver.pickTargetMode(scoped, capHz) ?: baseMode
    }

    private fun resolveThermalRewriteVote(
        nativeVote: Any,
        baseMode: DisplayModeInfo?,
        thermalMode: Int,
        scopedModes: List<DisplayModeInfo>,
    ): Any? {
        val baseRate = baseMode?.refreshRate ?: return null
        return when {
            thermalMode == PrefsContract.THERMAL_FOLLOW_SYSTEM -> null
            thermalMode == PrefsContract.THERMAL_IGNORE -> buildTargetVote(baseRate)
            thermalMode > 0 -> {
                val baseHz = kotlin.math.round(baseRate).toInt()
                val capHz = minOf(baseHz, thermalMode)
                val cappedRate = RefreshRateTargetResolver.pickTargetMode(scopedModes, capHz)?.refreshRate
                    ?: capHz.toFloat()
                val nativeRate = readVoteRate(nativeVote)
                if (nativeRate > 0f && kotlin.math.abs(nativeRate - cappedRate) < 0.5f) {
                    null
                } else {
                    buildTargetVote(cappedRate)
                }
            }
            else -> null
        }
    }

    private fun readCoreVotes(policy: Any, useActiveVotes: Boolean): SparseArray<*>? {
        val core = coreInstance ?: runCatching { fieldCore?.get(policy) }.getOrNull() ?: return null
        val field = if (useActiveVotes) fieldCoreActiveVotes else fieldCoreVotes
        return runCatching { field?.get(core) as? SparseArray<*> }.getOrNull()
    }

    private fun readVoteRate(vote: Any?): Float {
        if (vote == null) return 0f
        return runCatching { fieldVoteRefreshRate?.getFloat(vote) }.getOrNull() ?: 0f
    }

    private fun syncThermalVoteIfNeeded() {
        val policy = defaultPolicy ?: return
        val core = coreInstance ?: runCatching { fieldCore?.get(policy) }.getOrNull() ?: return
        val updateMethod = methodUpdateVoteLocked ?: return
        val activeThermalVote = lastNativeThermalVote
            ?: readCoreVotes(policy, useActiveVotes = false)?.get(PRIORITY_HIGH_TEMP)
            ?: return

        val lock = runCatching { fieldLock?.get(policy) }.getOrNull()
        runCatching {
            if (lock != null) {
                synchronized(lock) {
                    updateMethod.invoke(core, PRIORITY_HIGH_TEMP, activeThermalVote)
                }
            } else {
                updateMethod.invoke(core, PRIORITY_HIGH_TEMP, activeThermalVote)
            }
        }.onFailure {
            HookRegistry.logW("Failed syncing thermal vote after config change", it)
        }
    }

    private fun buildTargetVote(rate: Float): Any? {
        val rateId = getRateIdForRate(rate) ?: return null
        if (rateId <= 0) return null
        return runCatching { methodForRefreshRate?.invoke(null, rateId) }.getOrNull()
    }

    private fun getRateIdForRate(rate: Float): Int? {
        return runCatching { methodGetRefreshRateIdByRate?.invoke(null, rate) as? Int }.getOrNull()
    }

    private fun scheduleConfigListChange() {
        mainHandler.removeCallbacks(pendingNotifyRunnable)
        mainHandler.postDelayed(pendingNotifyRunnable, 60L)
    }

    private fun performConfigListChange() {
        val policy = defaultPolicy ?: return
        val configs = runCatching { fieldConfigs?.get(policy) }.getOrNull() ?: return
        val listener = runCatching { methodGetConfigListChangeListener?.invoke(configs) }.getOrNull() ?: return
        val lock = runCatching { fieldLock?.get(policy) }.getOrNull()
        runCatching {
            if (lock != null) {
                synchronized(lock) {
                    methodOnConfigListChange?.invoke(listener)
                }
            } else {
                methodOnConfigListChange?.invoke(listener)
            }
        }.onFailure {
            HookRegistry.logW("Failed notifying ConfigListChangeListener", it)
        }
    }

    private fun isNativeMustKeepPriority(priority: Int): Boolean =
        priority == PRIORITY_AOD || priority == PRIORITY_FOD_SHOW || priority == 140 || priority == 150

    private fun isSafetyPriority(priority: Int): Boolean =
        priority == 50 || priority == 60 || priority == 67 ||
            priority == 70 || priority == 110 || priority == 120

    private fun isAppSwitchBoostPriority(priority: Int): Boolean =
        priority == 40 || priority == 90

    private fun isUserOrAppPriority(priority: Int): Boolean =
        priority == 0 || priority == 10 || priority == 20 ||
            priority == 25 || priority == 30 || priority == 100

    private companion object {
        const val PRIORITY_HIGH_TEMP = 80
        const val PRIORITY_AOD = 121
        const val PRIORITY_FOD_SHOW = 130
    }
}
