package io.github.yukino1111.colorosframelocker.hook

import android.content.Context
import android.view.Display
import io.github.yukino1111.colorosframelocker.LockerConfig
import io.github.yukino1111.colorosframelocker.PrefsContract
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Intercepts ColorOS OPlusVrr TouchIdle configuration so idle frequency drops can be either
 * completely blocked (full-lock smoothness) or raised to a user-selected floor Hz, while stepping
 * aside cleanly whenever the display is off/AOD or optical FOD / safety protections are active.
 */
internal class TouchIdleBridge(
    private val configProvider: () -> LockerConfig,
    private val targetRateProvider: () -> Int,
    private val lowPowerBlockedProvider: () -> Boolean,
    private val displayStateProvider: () -> Int?,
    private val policyBlockingProvider: () -> Boolean,
) {
    private var sfControllerClass: Class<*>? = null
    private var sfGetInstanceMethod: Method? = null
    private var sfSetTouchIdleConfigMethod: Method? = null
    private var sfSetTouchIdleShouldPauseMethod: Method? = null

    private var refreshRateServiceClass: Class<*>? = null
    private var rrsGetDefaultMethod: Method? = null
    private var rrsExternalManagerField: Field? = null

    private var externalManagerClass: Class<*>? = null
    private var extScreenStateField: Field? = null
    private var extCurStatusField: Field? = null
    private var extTouchIdleKeepHightField: Field? = null
    private var extTouchIdleShouldPauseField: Field? = null
    private var extHandleTouchIdleConfigMethod: Method? = null

    private var beanClass: Class<*>? = null
    private var beanCopyCtor: Constructor<*>? = null
    private var beanGetLowestRate: Method? = null
    private var beanSetEnable: Method? = null
    private var beanSetHwEnable: Method? = null
    private var beanSetSwEnable: Method? = null
    private var beanSetAdfrEnable: Method? = null
    private var beanSet144To120Enable: Method? = null
    private var beanSetLowestRate: Method? = null
    private var beanSetLockRate: Method? = null

    @Volatile
    private var sfController: Any? = null

    @Volatile
    private var externalManager: Any? = null

    @Volatile
    private var lastOriginalBean: Any? = null

    @Volatile
    private var lastShouldPause: Boolean? = null

    @Volatile
    private var lastScreenState: Int = Display.STATE_UNKNOWN

    @Volatile
    private var lastFodStatus: Int = 0

    private val reapplying = ThreadLocal.withInitial { false }

    fun install(loader: ClassLoader) {
        val sfCls = HookRegistry.findClassOrNull("com.oplus.vrr.OPlusSurfaceFlingerController", loader)
        val rrsCls = HookRegistry.findClassOrNull("com.oplus.vrr.OPlusRefreshRateService", loader)
        val extCls = HookRegistry.findClassOrNull("com.oplus.vrr.OPlusExternalRefreshRateManager", loader)
        val tbCls = HookRegistry.findClassOrNull("com.oplus.vrr.bean.TouchIdleBean", loader)

        if (sfCls == null || tbCls == null) {
            HookRegistry.logW("OPlusSurfaceFlingerController or TouchIdleBean missing; TouchIdle bridge disabled")
            return
        }

        sfControllerClass = sfCls
        sfGetInstanceMethod = HookRegistry.optionalMethod(sfCls, "getInstance")
        sfSetTouchIdleConfigMethod = HookRegistry.optionalMethod(sfCls, "setTouchIdleConfig", tbCls)
        sfSetTouchIdleShouldPauseMethod =
            HookRegistry.optionalMethod(sfCls, "setTouchIdleShouldPause", Boolean::class.javaPrimitiveType!!)

        beanClass = tbCls
        beanCopyCtor = runCatching { tbCls.getDeclaredConstructor(tbCls).apply { isAccessible = true } }.getOrNull()
        beanGetLowestRate = HookRegistry.optionalMethod(tbCls, "getLowestRate")
        beanSetEnable = HookRegistry.optionalMethod(tbCls, "setEnable", Boolean::class.javaPrimitiveType!!)
        beanSetHwEnable = HookRegistry.optionalMethod(tbCls, "setHwEnable", Boolean::class.javaPrimitiveType!!)
        beanSetSwEnable = HookRegistry.optionalMethod(tbCls, "setSwEnable", Boolean::class.javaPrimitiveType!!)
        beanSetAdfrEnable = HookRegistry.optionalMethod(tbCls, "setAdfrEnable", Boolean::class.javaPrimitiveType!!)
        beanSet144To120Enable =
            HookRegistry.optionalMethod(tbCls, "set144To120Enable", Boolean::class.javaPrimitiveType!!)
        beanSetLowestRate = HookRegistry.optionalMethod(tbCls, "setLowestRate", Int::class.javaPrimitiveType!!)
        beanSetLockRate = HookRegistry.optionalMethod(tbCls, "setLockRate", Int::class.javaPrimitiveType!!)

        if (rrsCls != null) {
            refreshRateServiceClass = rrsCls
            rrsGetDefaultMethod = HookRegistry.optionalMethod(rrsCls, "getDefault")
            rrsExternalManagerField = HookRegistry.optionalField(rrsCls, "mExternalRefreshRateManager")
        }

        if (extCls != null) {
            externalManagerClass = extCls
            extScreenStateField = HookRegistry.optionalField(extCls, "mScreenState")
            extCurStatusField = HookRegistry.optionalField(extCls, "mCurStatus")
            extTouchIdleKeepHightField = HookRegistry.optionalField(extCls, "mTouchIdleKeepHight")
            extTouchIdleShouldPauseField = HookRegistry.optionalField(extCls, "mTouchIdleShouldPause")
            extHandleTouchIdleConfigMethod =
                HookRegistry.optionalMethod(extCls, "handleTouchIdleConfig", Boolean::class.javaPrimitiveType!!)
        }

        sfSetTouchIdleConfigMethod?.let { method ->
            HookRegistry.install("vrr.setTouchIdleConfig", method) { chain ->
                sfController = chain.thisObject
                if (reapplying.get() == true) {
                    return@install chain.proceed()
                }
                val args = chain.args.toTypedArray()
                val incoming = args.getOrNull(0)
                if (incoming != null) {
                    lastOriginalBean = cloneBean(incoming) ?: incoming
                    val modified = buildEffectiveBean(incoming)
                    if (modified !== incoming) {
                        args[0] = modified
                        return@install chain.proceed(args)
                    }
                }
                chain.proceed()
            }
        }

        sfSetTouchIdleShouldPauseMethod?.let { method ->
            HookRegistry.install("vrr.setTouchIdleShouldPause", method) { chain ->
                sfController = chain.thisObject
                if (reapplying.get() == true) {
                    return@install chain.proceed()
                }
                val args = chain.args.toTypedArray()
                val incoming = (args.getOrNull(0) as? Boolean) ?: false
                lastShouldPause = incoming
                if (shouldBlockIdle()) {
                    args[0] = true
                    return@install chain.proceed(args)
                }
                chain.proceed()
            }
        }

        if (rrsCls != null) {
            HookRegistry.optionalMethod(rrsCls, "publish", Context::class.java)?.let { publishMethod ->
                HookRegistry.install("vrr.servicePublish", publishMethod) { chain ->
                    val res = chain.proceed()
                    captureExternalManager(chain.thisObject)
                    reapply()
                    res
                }
            }
        }

        if (extCls != null) {
            HookRegistry.optionalMethod(extCls, "screenStateChange", Int::class.javaPrimitiveType!!)?.let { scMethod ->
                HookRegistry.install("vrr.screenStateChange", scMethod) { chain ->
                    externalManager = chain.thisObject
                    val prevState = lastScreenState
                    val state = (chain.getArg(0) as? Int) ?: lastScreenState
                    lastScreenState = state
                    val res = chain.proceed()
                    // Reapply both on wake (STATE_ON) and when entering OFF/DOZE so SF restores native idle config.
                    if (state != prevState) {
                        reapply()
                    }
                    res
                }
            }

            HookRegistry.optionalMethod(extCls, "setStatus", Int::class.javaPrimitiveType!!)?.let { statusMethod ->
                HookRegistry.install("vrr.setStatus", statusMethod) { chain ->
                    externalManager = chain.thisObject
                    val prevStatus = lastFodStatus
                    val status = (chain.getArg(0) as? Int) ?: 0
                    lastFodStatus = status
                    val res = chain.proceed()
                    if (status != prevStatus) {
                        reapply()
                    }
                    res
                }
            }
        }

        lazyBind()
        HookRegistry.logI("TouchIdleBridge installed (sf=${sfController != null}, ext=${externalManager != null})")
    }

    fun reapply() {
        lazyBind()
        val ext = externalManager
        val sf = sfController

        if (lastOriginalBean == null && ext != null && extHandleTouchIdleConfigMethod != null) {
            // Trigger native TouchIdle rebuild once so setTouchIdleConfig hook captures baseline bean
            runCatching { extHandleTouchIdleConfigMethod?.invoke(ext, false) }
        }

        val controller = sf ?: return
        val baseline = lastOriginalBean

        reapplying.set(true)
        try {
            if (baseline != null) {
                val beanToApply = if (isControlActive()) {
                    buildEffectiveBean(baseline)
                } else {
                    cloneBean(baseline) ?: baseline
                }
                sfSetTouchIdleConfigMethod?.invoke(controller, beanToApply)
            }
            val shouldPause = if (shouldBlockIdle()) true else (lastShouldPause ?: false)
            if (ext != null) {
                runCatching { extTouchIdleShouldPauseField?.setBoolean(ext, shouldPause) }
            }
            sfSetTouchIdleShouldPauseMethod?.invoke(controller, shouldPause)
        } catch (t: Throwable) {
            HookRegistry.logW("TouchIdleBridge.reapply failed", t)
        } finally {
            reapplying.set(false)
        }
    }

    private fun lazyBind() {
        if (sfController == null) {
            sfController = runCatching { sfGetInstanceMethod?.invoke(null) }.getOrNull()
        }
        if (externalManager == null) {
            val service = runCatching { rrsGetDefaultMethod?.invoke(null) }.getOrNull()
            if (service != null) {
                captureExternalManager(service)
            }
        }
    }

    private fun captureExternalManager(serviceInstance: Any?) {
        val mgr = runCatching { rrsExternalManagerField?.get(serviceInstance) }.getOrNull() ?: return
        externalManager = mgr
        runCatching {
            val state = extScreenStateField?.getInt(mgr)
            if (state != null) lastScreenState = state
            val status = extCurStatusField?.getInt(mgr)
            if (status != null) lastFodStatus = status
        }
    }

    fun isFodActive(): Boolean {
        val ext = externalManager
        val status = if (ext != null) {
            runCatching { extCurStatusField?.getInt(ext) }.getOrNull() ?: lastFodStatus
        } else {
            lastFodStatus
        }
        if ((status and 1) != 0) return true
        if (ext != null) {
            val keepHigh = runCatching { extTouchIdleKeepHightField?.getBoolean(ext) }.getOrNull()
            if (keepHigh == true) return true
        }
        return false
    }

    private fun currentScreenState(): Int {
        val ext = externalManager
        if (ext != null) {
            val fieldState = runCatching { extScreenStateField?.getInt(ext) }.getOrNull()
            if (fieldState != null) {
                lastScreenState = fieldState
                return fieldState
            }
        }
        if (lastScreenState != Display.STATE_UNKNOWN) {
            return lastScreenState
        }
        return displayStateProvider() ?: Display.STATE_OFF
    }

    private fun isControlActive(): Boolean {
        val cfg = configProvider()
        return cfg.enabled &&
            !lowPowerBlockedProvider() &&
            currentScreenState() == Display.STATE_ON &&
            !isFodActive() &&
            !policyBlockingProvider()
    }

    private fun shouldBlockIdle(): Boolean {
        if (!isControlActive()) return false
        return configProvider().idlePolicy == PrefsContract.IDLE_BLOCK
    }

    private fun cloneBean(src: Any): Any? {
        val ctor = beanCopyCtor ?: return null
        return runCatching { ctor.newInstance(src) }.getOrNull()
    }

    private fun buildEffectiveBean(original: Any): Any {
        if (!isControlActive()) return original
        val copy = cloneBean(original) ?: return original
        val cfg = configProvider()
        val targetRate = targetRateProvider().coerceAtLeast(60)

        runCatching {
            if (cfg.blockAppSwitchDowngrade) {
                beanSet144To120Enable?.invoke(copy, false)
            }
            if (cfg.blockAdfr) {
                beanSetAdfrEnable?.invoke(copy, false)
            }

            if (cfg.idlePolicy == PrefsContract.IDLE_BLOCK) {
                beanSetEnable?.invoke(copy, false)
                beanSetHwEnable?.invoke(copy, false)
                beanSetSwEnable?.invoke(copy, false)
                beanSetLowestRate?.invoke(copy, targetRate)
                beanSetLockRate?.invoke(copy, targetRate)
            } else {
                if (cfg.idleFloorHz > PrefsContract.IDLE_FLOOR_NATIVE) {
                    val floor = cfg.idleFloorHz.coerceIn(1, targetRate)
                    beanSetLowestRate?.invoke(copy, floor)
                }
                beanSetLockRate?.invoke(copy, targetRate)
            }
        }.onFailure {
            HookRegistry.logW("Failed rewriting TouchIdleBean", it)
        }
        return copy
    }
}
