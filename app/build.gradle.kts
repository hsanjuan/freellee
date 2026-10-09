import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.detekt") version "2.0.0-alpha.6"
}

detekt {
    config.setFrom("$projectDir/../config/detekt.yml")
}

extensions.configure<ApplicationExtension> {
    // Load local.properties for signing config
    val localPropsFile = rootProject.file("local.properties")
    val localPropsMap = if (localPropsFile.exists()) {
        localPropsFile.readLines()
            .filter { it.contains("=") && !it.trim().startsWith("#") }
            .associate { line ->
                val (key, value) = line.split("=", limit = 2)
                key.trim() to value.trim()
            }
    } else emptyMap<String, String>()
    namespace = "link.hector.freellee"
    compileSdk = 37

    defaultConfig {
        applicationId = "link.hector.freellee"
        minSdk = 34
        targetSdk = 37
        versionCode = 3
        versionName = "0.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Reproducible builds: don't embed the dependency metadata block, which contains build
    // information that varies between build machines.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Reproducible builds: ship the prebuilt AndroidX native libraries exactly as published
    // instead of re-stripping them with a machine-specific NDK version.
    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }

    signingConfigs {
        val sStorePath = findProperty("android.injected.signing.store.file") as String?
            ?: localPropsMap["signingStoreFile"]
        val sStorePass = findProperty("android.injected.signing.store.password") as String?
            ?: localPropsMap["signingStorePassword"]
        val sKeyAlias = findProperty("android.injected.signing.key.alias") as String?
            ?: localPropsMap["signingKeyAlias"]
        val sKeyPass = findProperty("android.injected.signing.key.password") as String?
            ?: localPropsMap["signingKeyPassword"]

        if (sStorePath != null && sStorePass != null && sKeyAlias != null && sKeyPass != null) {
            create("release") {
                storeFile = file(sStorePath)
                storePassword = sStorePass
                keyAlias = sKeyAlias
                keyPassword = sKeyPass
            }
            create("debugRelease") {
                storeFile = file(sStorePath)
                storePassword = sStorePass
                keyAlias = sKeyAlias
                keyPassword = sKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Reproducible builds: the embedded VCS info records the (machine-specific) build
            // path, so it differs between the developer CI and F-Droid's build server.
            vcsInfo.include = false
            val sc = signingConfigs.findByName("release")
            if (sc != null) {
                signingConfig = sc
            }
        }
        debug {
            isMinifyEnabled = false
            val sc = signingConfigs.findByName("debugRelease")
            if (sc != null) {
                signingConfig = sc
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// Reproducible builds: the generated ART/baseline profiles are not deterministic across machines,
// so disable the related tasks. See https://f-droid.org/en/docs/Reproducible_Builds/
tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.datastore:datastore-preferences:1.2.1")

    implementation("androidx.health.connect:connect-client:1.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("app.cash.turbine:turbine:1.2.1")
}
