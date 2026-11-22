-keep class io.github.yukino1111.colorosframelocker.hook.XposedEntry {
    public <init>();
    *;
}
-keep class * implements io.github.libxposed.api.XposedModuleInterface { *; }
-keep class * extends io.github.libxposed.api.XposedModule { *; }
-dontwarn io.github.libxposed.api.**
