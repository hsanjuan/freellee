package link.hector.freellee.data

import link.hector.freellee.data.DeviceStore.RecordItem
import link.hector.freellee.data.DeviceStore.StepInterval

/**
 * Step intervals at or below this are treated as coming from a patched firmware. Stock firmware
 * reports one interval per hour, which is too coarse for reliable sleep boundaries.
 */
const val ADVANCED_STEP_INTERVAL_SECONDS = 20L * 60L

/** Temperature sampling at or below this is treated as coming from a patched firmware. */
const val ADVANCED_TEMPERATURE_INTERVAL_SECONDS = 20 * 60

/** How the user wants advanced features handled, persisted across launches. */
enum class AdvancedModeSetting {
    /** Follow what the watch is actually reporting. */
    AUTO,

    /** Always enable advanced features, even when the watch looks unpatched. */
    ON,

    /** Never enable advanced features. */
    OFF,
}

/**
 * What the stored data says about the watch's sampling. [stepIntervalSeconds] is the median
 * length of a step interval, [temperatureIntervalSeconds] the median gap between temperature
 * readings. Either can be null while there is not enough data to tell.
 */
data class SamplingProfile(
    val stepIntervalSeconds: Long? = null,
    val temperatureIntervalSeconds: Int? = null,
) {
    val hasAdvancedSteps: Boolean
        get() = stepIntervalSeconds != null &&
            stepIntervalSeconds <= ADVANCED_STEP_INTERVAL_SECONDS

    val hasAdvancedTemperature: Boolean
        get() = temperatureIntervalSeconds != null &&
            temperatureIntervalSeconds <= ADVANCED_TEMPERATURE_INTERVAL_SECONDS

    /** Sleep detection needs fine-grained step data; temperature density only refines coverage. */
    val supportsSleepDetection: Boolean get() = hasAdvancedSteps

    val isKnown: Boolean get() = stepIntervalSeconds != null || temperatureIntervalSeconds != null

    companion object {
        val UNKNOWN = SamplingProfile()
    }
}

/** Resolves the effective setting against the observed sampling. */
fun AdvancedModeSetting.isAdvancedEnabled(profile: SamplingProfile): Boolean = when (this) {
    AdvancedModeSetting.ON -> true
    AdvancedModeSetting.OFF -> false
    AdvancedModeSetting.AUTO -> profile.supportsSleepDetection
}

/** How many of the most recent intervals are used to describe the current firmware. */
private const val STEP_SAMPLING_WINDOW = 32

/** How many of the most recent readings are used to describe the current sampling rate. */
private const val TEMPERATURE_SAMPLING_WINDOW = 200

/**
 * Median length of the most recent step intervals, or null when there is not enough data to
 * tell. Only recent evidence is used: after flashing a patched firmware the historical hourly
 * intervals must not keep the profile on "stock" for days.
 */
fun inferStepIntervalSeconds(
    intervals: List<StepInterval>,
    minimumSamples: Int = 10,
): Long? {
    val durations = intervals
        .asSequence()
        .sortedByDescending { it.tStart }
        .take(STEP_SAMPLING_WINDOW)
        .map { it.tEnd - it.tStart }
        .filter { it in 60..7_200 }
        .sorted()
        .toList()
    if (durations.size < minimumSamples) return null
    return durations[durations.size / 2]
}

/** Temperature sampling interval inferred from recent readings, or null if unknown. */
fun inferTemperatureIntervalSeconds(records: List<RecordItem>): Int? {
    val starts = records
        .asSequence()
        .filter { it.value >= MIN_VALID_TEMPERATURE }
        .sortedByDescending { it.tStart }
        .take(TEMPERATURE_SAMPLING_WINDOW)
        .map { it.tStart }
        .distinct()
        .sorted()
        .toList()
    val deltas = starts.zipWithNext { previous, next -> next - previous }
        .filter { it in 30..7_200 }
        .sorted()
    if (deltas.size < 10) return null
    return deltas[deltas.size / 2].toInt()
}

/** Human-readable interval, e.g. "15 min", "1 h". */
fun formatSamplingInterval(seconds: Long?): String {
    if (seconds == null || seconds <= 0) return "Unknown"
    if (seconds < 3_600) {
        val minutes = seconds / 60
        return if (minutes >= 1) "$minutes min" else "$seconds s"
    }
    val hours = seconds / 3_600.0
    return if (hours % 1.0 == 0.0) "${hours.toInt()} h" else "%.1f h".format(java.util.Locale.US, hours)
}
