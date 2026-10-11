package link.hector.freellee.data

import android.util.Log
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata as HCMetadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import link.hector.freellee.DEFAULT_WATCH_NAME
import java.time.Duration
import java.time.Instant

/**
 * Converts a counter event (integer count + timestamp) into a Health Connect record
 * based on the assigned meaning type.
 *
 * Each meaning type maps to a specific Health Connect record with appropriate
 * unit conversion. This object is self-contained: to add a new meaning, add a
 * `MEANING_*` constant, an entry in [meanings] and a branch in [convert].
 */
object CounterEventWriter {

    private const val TAG = "CounterEventWriter"

    // ── Per-meaning quantities ────────────────────────────────────────────
    // Single source of truth shared by convert() and preview().
    private const val METERS_PER_KM = 1000.0
    private const val KCAL_PER_ENERGY_UNIT = 100.0
    private const val KCAL_PASTA = 700.0
    private const val KCAL_PER_PIZZA_SLICE = 250.0
    private const val KCAL_NOODLES = 450.0
    private const val KCAL_SALAD = 300.0
    private const val KCAL_CEREAL = 200.0
    private const val KCAL_SNACK = 150.0
    private const val CAFFEINE_MG_ESPRESSO = 63.0
    private const val CAFFEINE_MG_COFFEE = 100.0
    private const val ML_PER_LITER = 1000.0
    private const val ML_PER_GLASS = 250.0

    // ── Meaning type constants ────────────────────────────────────────────
    // Each constant is a unique string used in PendingCounterEvent.assignedMeaning.

    // Distance (count = km)
    const val MEANING_DISTANCE_RUNNING = "distance_running"
    const val MEANING_DISTANCE_WALKING = "distance_walking"
    const val MEANING_DISTANCE_CYCLING = "distance_cycling"

    // Nutrition — Energy (count × 100 kcal)
    const val MEANING_NUTRITION_ENERGY = "nutrition_energy"

    // Nutrition — Standard meals (energy approximations)
    const val MEANING_NUTRITION_PASTA = "nutrition_pasta"
    const val MEANING_NUTRITION_PIZZA = "nutrition_pizza"
    const val MEANING_NUTRITION_NOODLES = "nutrition_noodles"
    const val MEANING_NUTRITION_SALAD = "nutrition_salad"
    const val MEANING_NUTRITION_CEREAL = "nutrition_cereal"
    const val MEANING_NUTRITION_SNACK = "nutrition_snack"

    // Nutrition — Coffee (caffeine)
    const val MEANING_NUTRITION_ESPRESSO = "nutrition_espresso"
    const val MEANING_NUTRITION_COFFEE = "nutrition_coffee"

    // Hydration (count = liters or glasses of 250ml)
    const val MEANING_HYDRATION_LITERS = "hydration_liters"
    const val MEANING_HYDRATION_GLASSES = "hydration_glasses"

    // Sexual Activity (count ignored)
    const val MEANING_SEXUAL_ACTIVITY = "sexual_activity"

    // ── Meaning catalog ───────────────────────────────────────────────────
    /**
     * A user-selectable meaning for a counter event: the stable [type] constant plus the
     * label/icon/category used to present it in the UI.
     */
    data class Meaning(
        val type: String,
        val label: String,
        val icon: String,
        val category: String,
    )

    /**
     * Every selectable meaning, in display order. Adding a new meaning is a single-line change
     * here plus its branch in [convert].
     */
    val meanings: List<Meaning> = listOf(
        // Distance (count = km)
        Meaning(MEANING_DISTANCE_RUNNING, "Running Distance", "🏃", "Distance"),
        Meaning(MEANING_DISTANCE_WALKING, "Walking Distance", "🚶", "Distance"),
        Meaning(MEANING_DISTANCE_CYCLING, "Cycling Distance", "🚴", "Distance"),

        // Nutrition (energy in ×100 kcal, otherwise item count)
        Meaning(MEANING_NUTRITION_ENERGY, "Energy (×100 kcal)", "🔥", "Nutrition"),
        Meaning(MEANING_NUTRITION_PASTA, "Pasta", "🍝", "Nutrition"),
        Meaning(MEANING_NUTRITION_PIZZA, "Pizza", "🍕", "Nutrition"),
        Meaning(MEANING_NUTRITION_NOODLES, "Noodles", "🍜", "Nutrition"),
        Meaning(MEANING_NUTRITION_SALAD, "Salad", "🥗", "Nutrition"),
        Meaning(MEANING_NUTRITION_CEREAL, "Breakfast Cereal", "🥣", "Nutrition"),
        Meaning(MEANING_NUTRITION_SNACK, "Snack", "🍿", "Nutrition"),
        Meaning(MEANING_NUTRITION_ESPRESSO, "Espresso", "☕", "Nutrition"),
        Meaning(MEANING_NUTRITION_COFFEE, "Coffee", "🫘", "Nutrition"),

        // Hydration (count = liters or 250 ml glasses)
        Meaning(MEANING_HYDRATION_LITERS, "Water (liters)", "💧", "Hydration"),
        Meaning(MEANING_HYDRATION_GLASSES, "Water (glasses)", "🥛", "Hydration"),

        // Sexual Activity (count ignored)
        Meaning(MEANING_SEXUAL_ACTIVITY, "Sexual Activity", "💜", "Sexual Activity"),
    )

    private val meaningsByType: Map<String, Meaning> = meanings.associateBy { it.type }

    /** Look up a meaning by its stable [Meaning.type] constant, or null if unknown. */
    fun meaning(type: String): Meaning? = meaningsByType[type]

    // ── Permission set for all counter event record types ─────────────────
    // Only the record types this module can actually produce are requested.
    val counterEventPermissions: Set<String> by lazy {
        setOf(
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(NutritionRecord::class),
            HealthPermission.getWritePermission(HydrationRecord::class),
            HealthPermission.getWritePermission(SexualActivityRecord::class),
        )
    }

    // ── Main conversion function ──────────────────────────────────────────
    /**
     * Converts a counter event into a Health Connect record.
     *
     * @param count The integer counter value from the watch.
     * @param timestamp Start time of the event (Unix epoch seconds).
     * @param meaningType One of the MEANING_* constants above.
     * @return A Health Connect record ready to be inserted, or null if the
     *         meaning type is unknown.
     */
    fun convert(
        count: Int,
        timestamp: Long,
        meaningType: String,
    ): Record? {
        return when (meaningType) {
            // ── Distance (count = km → meters) ────────────────────────
            MEANING_DISTANCE_RUNNING,
            MEANING_DISTANCE_WALKING,
            MEANING_DISTANCE_CYCLING -> {
                val meters = count * METERS_PER_KM
                DistanceRecord(
                    distance = Length.meters(meters),
                    startTime = Instant.ofEpochSecond(timestamp),
                    startZoneOffset = null,
                    endTime = Instant.ofEpochSecond(timestamp).plusNanos(1),
                    endZoneOffset = null,
                    metadata = createMetadata(),
                )
            }

            // ── Nutrition: Energy (count × 100 kcal) ──────────────────
            MEANING_NUTRITION_ENERGY -> {
                val kcal = count * KCAL_PER_ENERGY_UNIT
                NutritionRecord(
                    energy = Energy.kilocalories(kcal),
                    startTime = Instant.ofEpochSecond(timestamp),
                    startZoneOffset = null,
                    endTime = Instant.ofEpochSecond(timestamp).plusNanos(1),
                    endZoneOffset = null,
                    metadata = createMetadata(),
                    mealType = MealType.MEAL_TYPE_UNKNOWN,
                )
            }

            // ── Nutrition: Standard meals ─────────────────────────────
            MEANING_NUTRITION_PASTA -> nutritionMeal(
                name = "Pasta",
                energyKcal = count * KCAL_PASTA,
                mealType = MealType.MEAL_TYPE_UNKNOWN,
                timestamp = timestamp,
            )
            MEANING_NUTRITION_PIZZA -> nutritionMeal(
                name = "Pizza",
                energyKcal = count * KCAL_PER_PIZZA_SLICE,  // per slice
                mealType = MealType.MEAL_TYPE_UNKNOWN,
                timestamp = timestamp,
            )
            MEANING_NUTRITION_NOODLES -> nutritionMeal(
                name = "Noodles",
                energyKcal = count * KCAL_NOODLES,
                mealType = MealType.MEAL_TYPE_UNKNOWN,
                timestamp = timestamp,
            )
            MEANING_NUTRITION_SALAD -> nutritionMeal(
                name = "Salad",
                energyKcal = count * KCAL_SALAD,
                mealType = MealType.MEAL_TYPE_UNKNOWN,
                timestamp = timestamp,
            )
            MEANING_NUTRITION_CEREAL -> nutritionMeal(
                name = "Breakfast Cereal",
                energyKcal = count * KCAL_CEREAL,
                mealType = MealType.MEAL_TYPE_BREAKFAST,
                timestamp = timestamp,
            )
            MEANING_NUTRITION_SNACK -> nutritionMeal(
                name = "Snack",
                energyKcal = count * KCAL_SNACK,
                mealType = MealType.MEAL_TYPE_SNACK,
                timestamp = timestamp,
            )

            // ── Nutrition: Coffee (caffeine) ──────────────────────────
            MEANING_NUTRITION_ESPRESSO -> nutritionCoffee(
                name = "Espresso",
                caffeineMg = count * CAFFEINE_MG_ESPRESSO,
                startOffsetMinutes = 10,
                timestamp = timestamp,
            )
            MEANING_NUTRITION_COFFEE -> nutritionCoffee(
                name = "Coffee",
                caffeineMg = count * CAFFEINE_MG_COFFEE,
                startOffsetMinutes = 20,
                timestamp = timestamp,
            )

            // ── Hydration ─────────────────────────────────────────────
            MEANING_HYDRATION_LITERS -> {
                val ml = count * ML_PER_LITER
                HydrationRecord(
                    volume = Volume.milliliters(ml),
                    startTime = Instant.ofEpochSecond(timestamp),
                    startZoneOffset = null,
                    endTime = Instant.ofEpochSecond(timestamp).plusNanos(1),
                    endZoneOffset = null,
                    metadata = createMetadata(),
                )
            }
            MEANING_HYDRATION_GLASSES -> {
                val ml = count * ML_PER_GLASS
                HydrationRecord(
                    volume = Volume.milliliters(ml),
                    startTime = Instant.ofEpochSecond(timestamp),
                    startZoneOffset = null,
                    endTime = Instant.ofEpochSecond(timestamp).plusNanos(1),
                    endZoneOffset = null,
                    metadata = createMetadata(),
                )
            }

            // ── Sexual Activity (count ignored) ───────────────────────
            MEANING_SEXUAL_ACTIVITY -> {
                SexualActivityRecord(
                    time = Instant.ofEpochSecond(timestamp),
                    zoneOffset = null,
                    metadata = createMetadata(),
                )
            }

            // ── Unknown meaning ───────────────────────────────────────
            else -> {
                Log.w(TAG, "Unknown counter event meaning type: $meaningType")
                null
            }
        }
    }

    // ── Preview ───────────────────────────────────────────────────────────
    /**
     * A short, human-readable summary of what [convert] will write for [count], e.g. `"500 kcal"`
     * or `"750 ml"`. Returns null when the meaning carries no quantity (e.g. sexual activity) or
     * is unknown.
     */
    fun preview(count: Int, meaningType: String): String? = when (meaningType) {
        MEANING_DISTANCE_RUNNING,
        MEANING_DISTANCE_WALKING,
        MEANING_DISTANCE_CYCLING -> "${formatAmount(count.toDouble())} km"

        MEANING_NUTRITION_ENERGY -> "${formatAmount(count * KCAL_PER_ENERGY_UNIT)} kcal"
        MEANING_NUTRITION_PASTA -> "${formatAmount(count * KCAL_PASTA)} kcal"
        MEANING_NUTRITION_PIZZA -> "${formatAmount(count * KCAL_PER_PIZZA_SLICE)} kcal"
        MEANING_NUTRITION_NOODLES -> "${formatAmount(count * KCAL_NOODLES)} kcal"
        MEANING_NUTRITION_SALAD -> "${formatAmount(count * KCAL_SALAD)} kcal"
        MEANING_NUTRITION_CEREAL -> "${formatAmount(count * KCAL_CEREAL)} kcal"
        MEANING_NUTRITION_SNACK -> "${formatAmount(count * KCAL_SNACK)} kcal"

        MEANING_NUTRITION_ESPRESSO -> "${formatAmount(count * CAFFEINE_MG_ESPRESSO)} mg caffeine"
        MEANING_NUTRITION_COFFEE -> "${formatAmount(count * CAFFEINE_MG_COFFEE)} mg caffeine"

        MEANING_HYDRATION_LITERS -> "${formatAmount(count.toDouble())} L"
        MEANING_HYDRATION_GLASSES -> "${formatAmount(count * ML_PER_GLASS)} ml"

        else -> null
    }

    /** Formats a quantity with thousands separators and no trailing `.0`. */
    private fun formatAmount(value: Double): String =
        if (value % 1.0 == 0.0) "%,.0f".format(value) else "%,.1f".format(value)

    // ── Helper: Nutrition meal record ─────────────────────────────────────
    private fun nutritionMeal(
        name: String,
        energyKcal: Double,
        mealType: Int,
        timestamp: Long,
    ): NutritionRecord {
        return NutritionRecord(
            energy = Energy.kilocalories(energyKcal),
            startTime = Instant.ofEpochSecond(timestamp),
            startZoneOffset = null,
            endTime = Instant.ofEpochSecond(timestamp).plusNanos(1),
            endZoneOffset = null,
            metadata = createMetadata(),
            name = name,
            mealType = mealType,
        )
    }

    // ── Helper: Nutrition coffee (caffeine) record ────────────────────────
    private fun nutritionCoffee(
        name: String,
        caffeineMg: Double,
        startOffsetMinutes: Int,
        timestamp: Long,
    ): NutritionRecord {
        val startInstant = Instant.ofEpochSecond(timestamp)
        return NutritionRecord(
            caffeine = Mass.milligrams(caffeineMg),
            startTime = startInstant.minus(Duration.ofMinutes(startOffsetMinutes.toLong())),
            startZoneOffset = null,
            endTime = startInstant,
            endZoneOffset = null,
            metadata = createMetadata(),
            name = name,
            mealType = MealType.MEAL_TYPE_UNKNOWN,
        )
    }

    // ── Helper: Create metadata ───────────────────────────────────────────
    private fun createMetadata(): HCMetadata {
        val device = Device(
            type = Device.TYPE_WATCH,
            manufacturer = "Ollee Watch",
            model = DEFAULT_WATCH_NAME,
        )
        return HCMetadata.activelyRecorded(device = device)
    }
}
