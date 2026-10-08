package link.hector.freellee.ui.screens

/** A heading and body pair in the privacy policy. */
internal data class PolicySection(val heading: String, val body: String)

/**
 * The single in-app source of Freellee's privacy policy text.
 *
 * The repository's `PRIVACY.md` mirrors this content. [PrivacyPolicySyncTest] fails the build if
 * the two drift, so update both together (see the test for the exact comparison).
 */
internal object PrivacyPolicy {

    const val LAST_UPDATED = "8 October 2026"

    const val INTRO =
        "Freellee is an unofficial Android app for Ollee Watch boards. It runs entirely on your " +
            "phone, has no accounts and no internet access, and none of your data is sent to the " +
            "developer or to any third party."

    val sections: List<PolicySection> = listOf(
        PolicySection(
            "Data the app reads",
            "With your permission, Freellee reads activity data from your paired Ollee watch over " +
                "Bluetooth: step counts, heart rate, skin temperature and stopwatch/workout events. " +
                "This data is produced by your watch; the app does not collect it from anywhere else.",
        ),
        PolicySection(
            "How your data is stored",
            "Watch records are saved in Freellee's private app storage on your phone. If you grant " +
                "Health Connect access, Freellee also writes steps, heart rate, skin temperature " +
                "and exercise sessions into Android Health Connect, where they are available to " +
                "the health apps you choose.",
        ),
        PolicySection(
            "No sharing or tracking",
            "Freellee contains no analytics, advertising, crash reporting or tracking. It does not " +
                "request internet access, so it cannot transmit your data off the device, and it " +
                "never sells or shares your data with third parties.",
        ),
        PolicySection(
            "Why permissions are needed",
            "Bluetooth lets Freellee find, pair with and download data from your watch. Health " +
                "Connect lets it store the activity data described above. Both permissions are " +
                "optional: if you decline, the app keeps working with data stored locally and you " +
                "can grant access later.",
        ),
        PolicySection(
            "Your choices",
            "You can grant or revoke access at any time in Android Settings. For Health Connect, " +
                "open Health Connect, then App permissions, then Freellee.",
        ),
        PolicySection(
            "Deleting your data",
            "Uninstalling Freellee or clearing its storage in Android settings deletes the data it " +
                "stored locally. Data already written to Health Connect is managed by Health " +
                "Connect and can be deleted there.",
        ),
        PolicySection(
            "Health data",
            "Health and fitness data is sensitive. Freellee accesses it only to provide the " +
                "features described above and never for advertising or profiling.",
        ),
        PolicySection(
            "Changes to this policy",
            "This policy may be updated as the app evolves. The current version is always the one " +
                "shown here.",
        ),
        PolicySection(
            "Contact",
            "Freellee is maintained by Hector. Questions or data requests: code@hector.link. " +
                "Source code: https://github.com/hsanjuan/freellee.",
        ),
    )
}
