package io.github.yukino1111.colorosframelocker.hook

import android.content.SharedPreferences
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.yukino1111.colorosframelocker.LockerConfig
import io.github.yukino1111.colorosframelocker.PrefsContract

/**
 * Modern Xposed API 102 entrypoint loaded inside `system_server`.
 */
class XposedEntry : XposedModule() {

    @Volatile
    private var currentConfig: LockerConfig = LockerConfig()

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    @Volatile
    private var policyHooks: RefreshRatePolicyHooks? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        val updated = LockerConfig.from(prefs)
        if (updated == currentConfig) return@OnSharedPreferenceChangeListener
        currentConfig = updated
        HookRegistry.logI("Config updated via RemotePreferences: $updated")
        policyHooks?.onConfigChanged()
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        HookRegistry.attach(this)
        HookRegistry.logI("Loaded in process=${param.processName}, isSystemServer=${param.isSystemServer}")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        HookRegistry.attach(this)
        val prefs = runCatching { getRemotePreferences(PrefsContract.PREFS_NAME) }.getOrNull()
        remotePrefs = prefs
        currentConfig = LockerConfig.from(prefs)
        prefs?.registerOnSharedPreferenceChangeListener(prefsListener)

        val hooks = RefreshRatePolicyHooks { currentConfig }
        hooks.install(param.classLoader)
        policyHooks = hooks
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        HookRegistry.logI("Preparing for hot reload: unhooking all hooks")
        runCatching { remotePrefs?.unregisterOnSharedPreferenceChangeListener(prefsListener) }
        HookRegistry.unhookAll()
        policyHooks = null
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        HookRegistry.logI("Hot reload finished")
    }
}
