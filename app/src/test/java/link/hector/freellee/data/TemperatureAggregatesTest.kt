package link.hector.freellee.data

import link.hector.freellee.data.DeviceStore.RecordItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

class TemperatureAggregatesTest {

    private val zone = ZoneId.of("UTC")
    private val firstDay = LocalDate.of(2026, 1, 1)

    private fun record(date: LocalDate, hour: Int, minute: Int, celsius: Double): RecordItem {
        val start = date.atTime(hour, minute).atZone(zone).toEpochSecond()
        return RecordItem(
            type = 1,
            tStart = start,
            tEnd = start + 240,
            value = (celsius * 100).roundToInt(),
        )
    }

    private fun now(date: LocalDate = firstDay.plusDays(10)): Long =
        date.atTime(12, 0).atZone(zone).toEpochSecond()

    private fun nightRecords(date: LocalDate, celsius: Double): List<RecordItem> =
        (0 until 6).flatMap { hour ->
            (0 until 15).map { index ->
                record(date, hour, index * 4, celsius)
            }
        }

    @Test
    fun `day aggregates bucket by local date and use hourly medians`() {
        val day = firstDay
        val aggregates = buildTemperatureDayAggregates(
            records = listOf(
                record(day, 10, 0, 30.0),
                record(day, 10, 4, 32.0),
                record(day, 10, 8, 40.0),
                record(day, 11, 0, 20.0), // below the valid threshold
            ),
            nowEpochSecond = now(),
            zone = zone,
        )

        val aggregate = aggregates.single()
        assertEquals(day, aggregate.date)
        assertEquals(3, aggregate.validCount)
        assertEquals(360, aggregate.expectedCount)
        assertEquals(32.0, aggregate.hourlyMedians[10]!!, 0.001)
        assertEquals(3, aggregate.hourCount(10))
        assertNull(aggregate.hourlyMedians[11])
        assertEquals(day.atTime(10, 0).atZone(zone).toEpochSecond(), aggregate.firstEpochSecond)
        assertEquals(day.atTime(10, 12).atZone(zone).toEpochSecond(), aggregate.lastEpochSecond)
    }

    @Test
    fun `reference profile needs five days per clock hour`() {
        val fourDays = (0 until 4).map { offset ->
            record(firstDay.plusDays(offset.toLong()), 3, 0, 30.0 + offset)
        }
        val fourDayAggregates = buildTemperatureDayAggregates(
            records = fourDays,
            nowEpochSecond = now(),
            zone = zone,
        )
        assertNull(
            temperatureReferenceProfile(
                fourDayAggregates,
                beforeDate = firstDay.plusDays(5),
            )[3],
        )

        val fiveDays = fourDays + record(firstDay.plusDays(4), 3, 0, 34.0)
        val fiveDayAggregates = buildTemperatureDayAggregates(
            records = fiveDays,
            nowEpochSecond = now(),
            zone = zone,
        )
        assertEquals(
            32.0,
            temperatureReferenceProfile(
                fiveDayAggregates,
                beforeDate = firstDay.plusDays(5),
            )[3]!!,
            0.001,
        )
    }

    @Test
    fun `period baselines are frozen and do not drift on later syncs`() {
        val records = (0 until 6).flatMap { dayIndex ->
            val date = firstDay.plusDays(dayIndex.toLong())
            (0 until 6).map { hour ->
                record(date, hour, 0, 31.0 + dayIndex * 0.1)
            }
        }.toMutableList()
        val firstBuild = buildTemperatureDayAggregates(
            records = records,
            nowEpochSecond = now(),
            zone = zone,
        )
        val daySix = firstBuild.single { it.date == firstDay.plusDays(5) }
        val frozenBaseline = daySix.baselineCelsius(TemperaturePeriod.NIGHT)
        assertNotNull(frozenBaseline)
        // The six prior nights are 31.0 … 31.4 °C, so the median reference is 31.2 °C.
        assertEquals(31.2, frozenBaseline!!, 0.001)
        // Day six itself reads 31.5 °C, which must not feed its own baseline.
        assertEquals(31.5, daySix.hourlyMedians[0]!!, 0.001)

        records += record(firstDay.plusDays(5), 0, 4, 40.0)
        val secondBuild = buildTemperatureDayAggregates(
            records = records,
            existing = firstBuild,
            nowEpochSecond = now(),
            zone = zone,
        )
        val daySixAgain = secondBuild.single { it.date == firstDay.plusDays(5) }
        assertEquals(frozenBaseline, daySixAgain.baselineCelsius(TemperaturePeriod.NIGHT)!!, 0.0001)
        assertEquals(35.75, daySixAgain.hourlyMedians[0]!!, 0.001)
    }

    @Test
    fun `a truncated raw day does not shrink the stored aggregate`() {
        val fullDay = (0 until 6).flatMap { hour ->
            (0 until 15).map { index ->
                record(firstDay, hour, index * 4, 31.0)
            }
        }
        val firstBuild = buildTemperatureDayAggregates(
            records = fullDay,
            nowEpochSecond = now(),
            zone = zone,
        )
        assertEquals(90, firstBuild.single().validCount)

        val secondBuild = buildTemperatureDayAggregates(
            records = fullDay.take(10),
            existing = firstBuild,
            nowEpochSecond = now(),
            zone = zone,
        )

        assertEquals(90, secondBuild.single().validCount)
    }

    @Test
    fun `insights report the last night and a three-night average`() {
        val records = (0 until 8).flatMap { dayIndex ->
            val date = firstDay.plusDays(dayIndex.toLong())
            val celsius = if (dayIndex < 5) 31.0 else 31.5
            nightRecords(date, celsius)
        }
        val at = firstDay.plusDays(7).atTime(12, 0).atZone(zone).toEpochSecond()
        val aggregates = buildTemperatureDayAggregates(
            records = records,
            nowEpochSecond = at,
            zone = zone,
        )

        val insights = buildTemperatureInsights(aggregates, at, zone)

        val lastNight = insights.lastNight
        assertNotNull(lastNight)
        assertEquals(firstDay.plusDays(7), lastNight!!.date)
        assertEquals(0.5, lastNight.deviationCelsius, 0.001)
        assertEquals(90, lastNight.readingCount)
        assertEquals(1.0, lastNight.coverage, 0.001)
        assertTrue(lastNight.reliable)
        assertEquals(0.5, insights.threeNightDeviationCelsius!!, 0.001)
        assertEquals(3, insights.recentNights.size)
        assertEquals(
            listOf(
                TemperaturePeriod.NIGHT,
                TemperaturePeriod.MORNING,
                TemperaturePeriod.AFTERNOON,
                TemperaturePeriod.EVENING,
            ),
            insights.todayPeriods.map { it.period },
        )
        assertEquals(0.5, insights.todayPeriods.first().deviationCelsius!!, 0.001)
        assertNull(insights.todayPeriods[1].deviationCelsius)
    }

    @Test
    fun `the in-progress night is not reported before 06_00`() {
        val records = (0 until 8).flatMap { dayIndex ->
            nightRecords(firstDay.plusDays(dayIndex.toLong()), 31.0)
        }
        val at = firstDay.plusDays(7).atTime(3, 0).atZone(zone).toEpochSecond()
        val aggregates = buildTemperatureDayAggregates(
            records = records,
            nowEpochSecond = at,
            zone = zone,
        )

        val insights = buildTemperatureInsights(aggregates, at, zone)

        assertEquals(firstDay.plusDays(6), insights.lastNight!!.date)
        assertNull(insights.threeNightDeviationCelsius)
    }

    @Test
    fun `night reliability reflects coverage`() {
        val records = (0 until 6).flatMap { dayIndex ->
            val date = firstDay.plusDays(dayIndex.toLong())
            (0 until 4).flatMap { hour ->
                (0 until 3).map { index ->
                    record(date, hour, index * 4, 31.0)
                }
            }
        }
        val at = firstDay.plusDays(5).atTime(12, 0).atZone(zone).toEpochSecond()
        val aggregates = buildTemperatureDayAggregates(
            records = records,
            nowEpochSecond = at,
            zone = zone,
        )

        val insights = buildTemperatureInsights(aggregates, at, zone)

        val lastNight = insights.lastNight
        assertNotNull(lastNight)
        assertEquals(12.0 / 90.0, lastNight!!.coverage, 0.001)
        assertTrue(!lastNight.reliable)
    }

    @Test
    fun `unsynced time is not counted as missing readings`() {
        val day = firstDay.plusDays(10)
        val records = (0 until 3).flatMap { hour ->
            (0 until 15).map { index -> record(day, hour, index * 4, 31.0) }
        }
        val at = day.atTime(6, 0).atZone(zone).toEpochSecond()
        val aggregates = buildTemperatureDayAggregates(
            records = records,
            nowEpochSecond = at,
            zone = zone,
        )

        // Synced at 03:00: three hours of data are expected, and all of them are present.
        val syncedAtThree = buildTemperatureInsights(
            aggregates = aggregates,
            nowEpochSecond = at,
            zone = zone,
            syncedUntilEpochSecond = day.atTime(3, 0).atZone(zone).toEpochSecond(),
        )
        val today = syncedAtThree.dailyCoverage.single { it.date == day }
        assertEquals(45, today.validCount)
        assertEquals(45, today.expectedCount)
        assertEquals(1.0, today.coverage, 0.001)
        // The morning period has not been synced at all, so it has no expected readings.
        assertEquals(0, syncedAtThree.currentPeriod!!.expectedCount)
        val night = syncedAtThree.todayPeriods.single { it.period == TemperaturePeriod.NIGHT }
        assertEquals(1.0, night.coverage!!, 0.001)
        val morning = syncedAtThree.todayPeriods.single { it.period == TemperaturePeriod.MORNING }
        assertNull(morning.coverage)

        // Without a sync time the three unsynced hours look like missing readings.
        val syncedNow = buildTemperatureInsights(aggregates, at, zone)
        val todayNow = syncedNow.dailyCoverage.single { it.date == day }
        assertEquals(90, todayNow.expectedCount)
        assertEquals(0.5, todayNow.coverage, 0.001)
    }

    @Test
    fun `sample interval is inferred from consecutive readings`() {
        val records = (0 until 12).map { index ->
            record(firstDay, 0, index * 4, 31.0)
        }
        assertEquals(240, inferTemperatureSampleSeconds(records))
    }

    @Test
    fun `csv export has a header and one row per day`() {
        val aggregates = buildTemperatureDayAggregates(
            records = listOf(
                record(firstDay, 10, 0, 31.0),
                record(firstDay.plusDays(1), 10, 0, 32.0),
            ),
            nowEpochSecond = now(),
            zone = zone,
        )

        val lines = temperatureAggregatesCsv(aggregates).trim().lines()

        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("date,valid_readings,expected_readings,coverage"))
        assertTrue(lines[1].startsWith(firstDay.toString()))
        assertTrue(lines[2].startsWith(firstDay.plusDays(1).toString()))
    }
}
