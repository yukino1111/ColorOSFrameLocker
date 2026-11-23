package io.github.yukino1111.colorosframelocker

import android.app.Application

class FrameLockerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        XposedServiceManager.ensureRegistered()
    }
}
