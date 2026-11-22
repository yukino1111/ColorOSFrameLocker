package io.github.yukino1111.colorosframelocker.hook

import android.graphics.Point
import android.view.Display
import io.github.yukino1111.colorosframelocker.PrefsContract
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DisplayModeInfo(
    val id: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)

internal object RefreshRateTargetResolver {
    private const val RATE_TOLERANCE = 1.2f

    fun fromDisplayModes(modes: Array<*>?): List<DisplayModeInfo> {
        if (modes == null) return emptyList()
        return modes.filterIsInstance<Display.Mode>().map {
            DisplayModeInfo(
                id = it.modeId,
                width = it.physicalWidth,
                height = it.physicalHeight,
                refreshRate = it.refreshRate,
            )
        }
    }

    fun modesForResolution(
        allModes: List<DisplayModeInfo>,
        resolution: Point?,
    ): List<DisplayModeInfo> {
        if (allModes.isEmpty()) return emptyList()
        if (resolution == null || resolution.x <= 0 || resolution.y <= 0) return allModes
        val exact = allModes.filter { it.width == resolution.x && it.height == resolution.y }
        if (exact.isNotEmpty()) return exact
        val targetMin = min(resolution.x, resolution.y)
        val targetMax = max(resolution.x, resolution.y)
        val rotated = allModes.filter {
            min(it.width, it.height) == targetMin && max(it.width, it.height) == targetMax
        }
        return rotated.ifEmpty { allModes }
    }

    fun pickTargetMode(
        candidates: List<DisplayModeInfo>,
        targetHzMode: Int,
    ): DisplayModeInfo? {
        if (candidates.isEmpty()) return null
        if (targetHzMode <= PrefsContract.TARGET_HZ_AUTO_MAX) {
            return candidates.maxByOrNull { it.refreshRate }
        }
        val targetF = targetHzMode.toFloat()
        val exactMatch = candidates
            .filter { abs(it.refreshRate - targetF) <= RATE_TOLERANCE }
            .maxByOrNull { it.refreshRate }
        if (exactMatch != null) return exactMatch

        val capped = candidates
            .filter { it.refreshRate <= targetF + 0.6f }
            .maxByOrNull { it.refreshRate }
        return capped ?: candidates.maxByOrNull { it.refreshRate }
    }

    fun findModeIdForRate(
        candidates: List<DisplayModeInfo>,
        rate: Float,
    ): Int? {
        if (candidates.isEmpty() || !rate.isFinite() || rate <= 0f) return null
        val exact = candidates
            .filter { abs(it.refreshRate - rate) <= RATE_TOLERANCE }
            .minByOrNull { abs(it.refreshRate - rate) }
        if (exact != null) return exact.id
        return candidates
            .filter { it.refreshRate <= rate + 0.6f }
            .maxByOrNull { it.refreshRate }
            ?.id
    }
}
