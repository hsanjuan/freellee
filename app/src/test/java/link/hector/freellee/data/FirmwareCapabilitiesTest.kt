package link.hector.freellee.data

import link.hector.freellee.data.DeviceStore.RecordItem
import link.hector.freellee.data.DeviceStore.StepInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

class FirmwareCapabilitiesTest {

    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 11, 9)
    private fun baseEpoch(): Long = day.atTime(8, 0).atZone(zone).toEpochSecond()

    private fun tempRecords(intervalSeconds: Int, count: Int = 48): List<RecordItem> =
        (0 until count).map { index ->
            val start = baseEpoch() + index.toLong() * intervalSeconds
            RecordItem(
                type = 1,
                tStart = start,
                tEnd = start + intervalSeconds,
                value = (31.0 * 100).roundToInt(),
            )
        }

    private fun stepIntervals(intervalSeconds: Long, count: Int = 48): List<StepInterval> =
        (0 until count).map { index ->
            val start = baseEpoch() + index * intervalSeconds
            StepInterval(tStart = start, tEnd = start + intervalSeconds, steps = 10)
        }

    @Test
    fun `patched firmware is detected from fine step intervals`() {
        val profile = SamplingProfile(
            stepIntervalSeconds = inferStepIntervalSeconds(stepIntervals(900)),
            temperatureIntervalSeconds = inferTemperatureIntervalSeconds(tempRecords(240)),
        )

        assertEquals(900L, profile.stepIntervalSeconds)
        assertEquals(240, profile.temperatureIntervalSeconds)
        assertTrue(profile.hasAdvancedSteps)
        assertTrue(profile.hasAdvancedTemperature)
        assertTrue(profile.supportsSleepDetection)
    }

    @Test
    fun `stock hourly sampling is not advanced`() {
        val profile = SamplingProfile(
            stepIntervalSeconds = inferStepIntervalSeconds(stepIntervals(3600)),
            temperatureIntervalSeconds = inferTemperatureIntervalSeconds(tempRecords(3600, count = 24)),
        )

        assertEquals(3600L, profile.stepIntervalSeconds)
        assertEquals(3600, profile.temperatureIntervalSeconds)
        assertFalse(profile.hasAdvancedSteps)
        assertFalse(profile.hasAdvancedTemperature)
        assertFalse(profile.supportsSleepDetection)
    }

    @Test
    fun `hourly temperature sampling is inferred instead of falling back to four minutes`() {
        assertEquals(3600, inferTemperatureSampleSeconds(tempRecords(3600, count = 24)))
        assertEquals(3600, inferTemperatureIntervalSeconds(tempRecords(3600, count = 24)))
    }

    @Test
    fun `recent intervals describe the current firmware, not the old history`() {
        val start = baseEpoch()
        val oldHourly = (0 until 30).map { index ->
            val from = start + index * 3600L
            StepInterval(tStart = from, tEnd = from + 3600, steps = 10)
        }
        val newFine = (0 until 32).map { index ->
            val from = start + 30 * 3600L + index * 900L
            StepInterval(tStart = from, tEnd = from + 900, steps = 10)
        }

        assertEquals(900L, inferStepIntervalSeconds(oldHourly + newFine))
    }

    @Test
    fun `recent temperature readings describe the current sampling`() {
        val start = baseEpoch()
        val oldHourly = (0 until 50).map { index ->
            val from = start + index * 3600L
            RecordItem(type = 1, tStart = from, tEnd = from + 3600, value = 3100)
        }
        val newFine = (0 until 200).map { index ->
            val from = start + 50 * 3600L + index * 240L
            RecordItem(type = 1, tStart = from, tEnd = from + 240, value = 3100)
        }

        assertEquals(240, inferTemperatureIntervalSeconds(oldHourly + newFine))
    }

    @Test
    fun `sparse data leaves the sampling profile unknown`() {
        val profile = SamplingProfile(
            stepIntervalSeconds = inferStepIntervalSeconds(stepIntervals(900, count = 3)),
            temperatureIntervalSeconds = inferTemperatureIntervalSeconds(tempRecords(240, count = 3)),
        )

        assertNull(profile.stepIntervalSeconds)
        assertNull(profile.temperatureIntervalSeconds)
        assertFalse(profile.isKnown)
    }

    @Test
    fun `automatic mode follows the observed sampling`() {
        val advanced = SamplingProfile(stepIntervalSeconds = 900)
        val stock = SamplingProfile(stepIntervalSeconds = 3600)

        assertTrue(AdvancedModeSetting.AUTO.isAdvancedEnabled(advanced))
        assertFalse(AdvancedModeSetting.AUTO.isAdvancedEnabled(stock))
        assertFalse(AdvancedModeSetting.AUTO.isAdvancedEnabled(SamplingProfile.UNKNOWN))
    }

    @Test
    fun `manual override wins over the observed sampling`() {
        val stock = SamplingProfile(stepIntervalSeconds = 3600)
        val advanced = SamplingProfile(stepIntervalSeconds = 900)

        assertTrue(AdvancedModeSetting.ON.isAdvancedEnabled(stock))
        assertFalse(AdvancedModeSetting.OFF.isAdvancedEnabled(advanced))
    }

    @Test
    fun `sampling intervals are formatted for display`() {
        assertEquals("Unknown", formatSamplingInterval(null))
        assertEquals("15 min", formatSamplingInterval(900))
        assertEquals("4 min", formatSamplingInterval(240))
        assertEquals("1 h", formatSamplingInterval(3600))
    }
}
