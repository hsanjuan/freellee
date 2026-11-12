package link.hector.freellee

const val BLE_CONNECT_TIMEOUT_MS = 10_000L
const val DEFAULT_WATCH_NAME = "Ollee Watch"
const val BLE_WATCH_NAME_PREFIX = "Ollee"

/** Number of days of step intervals kept in the local store. */
const val STEP_RETENTION_DAYS = 30L

/** Number of days of raw skin-temperature records kept in the local store. */
const val TEMPERATURE_RETENTION_DAYS = 28L

/** Number of days of derived daily temperature aggregates kept in the local store. */
const val TEMPERATURE_AGGREGATE_RETENTION_DAYS = 400L

/** Number of days used to build the personal 24-hour temperature reference profile. */
const val TEMPERATURE_REFERENCE_WINDOW_DAYS = 28

/** Nominal watch sampling interval for skin temperature, in seconds. */
const val TEMPERATURE_SAMPLE_SECONDS = 240
