package io.github.yukino1111.colorosframelocker.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal object HookRegistry {
    private const val TAG = "ColorOSFrameLocker"

    @Volatile
    private var module: XposedModule? = null

    private val handles = ConcurrentHashMap<String, XposedInterface.HookHandle>()

    fun attach(xposedModule: XposedModule) {
        module = xposedModule
    }

    fun install(
        key: String,
        target: Executable,
        hooker: XposedInterface.Hooker,
    ): Boolean {
        val mod = module ?: return false
        handles.remove(key)?.unhook()
        val handle = mod.hook(target)
            .setId(key)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(hooker)
        handles[key] = handle
        return true
    }

    fun unhookAll() {
        val keys = handles.keys.toList()
        for (key in keys) {
            runCatching { handles.remove(key)?.unhook() }
        }
    }

    fun logI(message: String) {
        module?.log(Log.INFO, TAG, message) ?: Log.i(TAG, message)
    }

    fun logW(message: String, error: Throwable? = null) {
        val mod = module
        if (mod != null) {
            if (error != null) mod.log(Log.WARN, TAG, message, error)
            else mod.log(Log.WARN, TAG, message)
        } else {
            if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
        }
    }

    fun findClass(name: String, loader: ClassLoader): Class<*> =
        Class.forName(name, false, loader)

    fun findClassOrNull(name: String, loader: ClassLoader): Class<*>? =
        runCatching { findClass(name, loader) }.getOrNull()

    fun field(type: Class<*>, name: String): Field =
        type.getDeclaredField(name).apply { isAccessible = true }

    fun optionalField(type: Class<*>, name: String): Field? =
        runCatching { field(type, name) }.getOrNull()

    fun method(type: Class<*>, name: String, vararg paramTypes: Class<*>): Method =
        type.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true }

    fun optionalMethod(type: Class<*>, name: String, vararg paramTypes: Class<*>): Method? =
        runCatching { method(type, name, *paramTypes) }.getOrNull()
}
