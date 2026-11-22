package io.github.yukino1111.colorosframelocker

import android.content.SharedPreferences

object PrefsContract {
    const val PREFS_NAME = "config"

    const val KEY_ENABLED = "enabled"
    const val KEY_TARGET_HZ_MODE = "target_hz_mode"
    const val KEY_IDLE_POLICY = "idle_policy"
    const val KEY_IDLE_FLOOR_HZ = "idle_floor_hz"
    const val KEY_BLOCK_ADFR = "block_adfr"
    const val KEY_BLOCK_APP_SWITCH_DOWNGRADE = "block_app_switch_downgrade"
    const val KEY_IGNORE_PENCIL_LIMIT = "ignore_pencil_limit"
    const val KEY_ALLOW_SAFETY_LIMITS = "allow_safety_limits"
    const val KEY_THERMAL_MODE = "thermal_mode"
    const val KEY_RESPECT_LOW_POWER = "respect_low_power"

    const val TARGET_HZ_AUTO_MAX = 0

    /** Respect ColorOS native high-temperature refresh rate limit (PRIORITY_HIGH_TEMPERATURE). */
    const val THERMAL_FOLLOW_SYSTEM = 0

    /** Ignore high-temperature limit and hold the user's target refresh rate. */
    const val THERMAL_IGNORE = -1

    /** Disable TouchIdle hardware/software frequency drops (maximum smoothness). */
    const val IDLE_BLOCK = 0

    /** Keep interactive/transition frames locked, allow TouchIdle drop after 3s idle. */
    const val IDLE_ALLOW_FLOOR = 1

    /** Preserve ColorOS native TouchIdle lowestRate without raising the floor. */
    const val IDLE_FLOOR_NATIVE = 0
}

data class LockerConfig(
    val enabled: Boolean = true,
    val targetHzMode: Int = PrefsContract.TARGET_HZ_AUTO_MAX,
    val idlePolicy: Int = PrefsContract.IDLE_BLOCK,
    val idleFloorHz: Int = 90,
    val blockAdfr: Boolean = true,
    val blockAppSwitchDowngrade: Boolean = true,
    val ignorePencilLimit: Boolean = true,
    val allowSafetyLimits: Boolean = true,
    val thermalMode: Int = PrefsContract.THERMAL_FOLLOW_SYSTEM,
    val respectLowPower: Boolean = true,
) {
    companion object {
        fun from(prefs: SharedPreferences?): LockerConfig {
            if (prefs == null) return LockerConfig()
            return LockerConfig(
                enabled = prefs.getBoolean(PrefsContract.KEY_ENABLED, true),
                targetHzMode = prefs.getInt(PrefsContract.KEY_TARGET_HZ_MODE, PrefsContract.TARGET_HZ_AUTO_MAX),
                idlePolicy = prefs.getInt(PrefsContract.KEY_IDLE_POLICY, PrefsContract.IDLE_BLOCK),
                idleFloorHz = prefs.getInt(PrefsContract.KEY_IDLE_FLOOR_HZ, 90),
                blockAdfr = prefs.getBoolean(PrefsContract.KEY_BLOCK_ADFR, true),
                blockAppSwitchDowngrade = prefs.getBoolean(PrefsContract.KEY_BLOCK_APP_SWITCH_DOWNGRADE, true),
                ignorePencilLimit = prefs.getBoolean(PrefsContract.KEY_IGNORE_PENCIL_LIMIT, true),
                allowSafetyLimits = prefs.getBoolean(PrefsContract.KEY_ALLOW_SAFETY_LIMITS, true),
                thermalMode = prefs.getInt(PrefsContract.KEY_THERMAL_MODE, PrefsContract.THERMAL_FOLLOW_SYSTEM),
                respectLowPower = prefs.getBoolean(PrefsContract.KEY_RESPECT_LOW_POWER, true),
            )
        }
    }
}
