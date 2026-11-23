package io.github.yukino1111.colorosframelocker

import android.content.SharedPreferences
import android.os.DeadObjectException
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ScopeRequestResult {
    data object Approved : ScopeRequestResult
    data class Failed(val reason: String) : ScopeRequestResult
}

data class FrameworkState(
    val bound: Boolean = false,
    val frameworkInfo: String = "",
    val hasSystemScope: Boolean = false,
    val scopeResult: ScopeRequestResult? = null,
)

/**
 * Holds the process-wide [XposedService] binding because [XposedServiceHelper.registerListener]
 * drains its internal binder cache on first registration and replaces the single static listener.
 */
object XposedServiceManager {
    private val lock = Any()
    private val registered = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    private var xposedService: XposedService? = null
    private var currentPrefs: SharedPreferences? = null

    private val _frameworkState = MutableStateFlow(FrameworkState())
    val frameworkState: StateFlow<FrameworkState> = _frameworkState.asStateFlow()

    private val _remotePrefs = MutableStateFlow<SharedPreferences?>(null)
    val remotePrefs: StateFlow<SharedPreferences?> = _remotePrefs.asStateFlow()

    private val serviceListener = object : XposedServiceHelper.OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            val prefs = runCatching { service.getRemotePreferences(PrefsContract.PREFS_NAME) }.getOrNull()
            val scope = runCatching { service.getScope() }.getOrDefault(emptyList())
            val info = runCatching {
                "${service.frameworkName} ${service.frameworkVersion} (API ${service.apiVersion})"
            }.getOrDefault("LSPosed")

            synchronized(lock) {
                xposedService = service
                currentPrefs = prefs
                _remotePrefs.value = prefs
                _frameworkState.value = FrameworkState(
                    bound = true,
                    frameworkInfo = info,
                    hasSystemScope = scope.contains("system"),
                    scopeResult = null,
                )
            }
        }

        override fun onServiceDied(service: XposedService) {
            synchronized(lock) {
                if (xposedService === service) {
                    xposedService = null
                    currentPrefs = null
                    _remotePrefs.value = null
                    _frameworkState.value = FrameworkState(bound = false)
                }
            }
        }
    }

    fun ensureRegistered() {
        if (registered.compareAndSet(false, true)) {
            XposedServiceHelper.registerListener(serviceListener)
        }
    }

    fun currentRemotePrefs(): SharedPreferences? = synchronized(lock) { currentPrefs }

    fun refreshScope() {
        val service = synchronized(lock) { xposedService } ?: return
        executor.execute {
            runCatching { service.getScope() }
                .onSuccess { scope ->
                    synchronized(lock) {
                        if (xposedService === service) {
                            val current = _frameworkState.value
                            val hasSystem = scope.contains("system")
                            if (current.hasSystemScope != hasSystem) {
                                _frameworkState.value = current.copy(hasSystemScope = hasSystem)
                            }
                        }
                    }
                }
                .onFailure { err ->
                    if (err is XposedService.ServiceException && err.cause is DeadObjectException) {
                        synchronized(lock) {
                            if (xposedService === service) {
                                xposedService = null
                                currentPrefs = null
                                _remotePrefs.value = null
                                _frameworkState.value = FrameworkState(bound = false)
                            }
                        }
                    }
                }
        }
    }

    fun requestSystemScope() {
        val service = synchronized(lock) { xposedService } ?: return
        runCatching {
            service.requestScope(
                listOf("system"),
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: MutableList<String>) {
                        val hasSystem = approved.contains("system") ||
                            runCatching { service.getScope().contains("system") }.getOrDefault(false)
                        synchronized(lock) {
                            if (xposedService === service) {
                                _frameworkState.value = _frameworkState.value.copy(
                                    hasSystemScope = hasSystem,
                                    scopeResult = ScopeRequestResult.Approved,
                                )
                            }
                        }
                    }

                    override fun onScopeRequestFailed(message: String) {
                        synchronized(lock) {
                            if (xposedService === service) {
                                _frameworkState.value = _frameworkState.value.copy(
                                    scopeResult = ScopeRequestResult.Failed(message),
                                )
                            }
                        }
                    }
                },
            )
        }.onFailure { err ->
            synchronized(lock) {
                if (xposedService === service) {
                    _frameworkState.value = _frameworkState.value.copy(
                        scopeResult = ScopeRequestResult.Failed(err.message ?: "Unknown error"),
                    )
                }
            }
        }
    }
}
