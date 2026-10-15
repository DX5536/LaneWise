import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// API keys: local.properties (dev) or environment variables (GitHub Actions secrets).
// They are only defaults — every key can also be entered in the in-app settings.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// App version: bump APP_VERSION with docs/ROADMAP.md. CI adds the run number (0.4.1.57), local builds say "-local".
val appVersion = "0.4.3"
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val fullVersion = appVersion + (runNumber?.let { ".$it" } ?: "-local")
val gitSha: String = System.getenv("GITHUB_SHA")?.take(7) ?: runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim()
}.getOrDefault("dev")

// APK file names: LaneWise-v0.4.1.57-release.apk instead of app-release.apk.
base { archivesName.set("LaneWise-v$fullVersion") }

fun secret(name: String): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: "").replace("\"", "")

android {
    namespace = "de.lanewise"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.lanewise.app"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber ?: 1
        versionName = fullVersion
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")

        buildConfigField("String", "TOMTOM_API_KEY", "\"${secret("TOMTOM_API_KEY")}\"")
        buildConfigField("String", "HERE_API_KEY", "\"${secret("HERE_API_KEY")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("RELEASE_KEYSTORE_PATH")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) releaseSigning else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // `-PupdateVoiceInventory` rewrites tools/voice_pack/inventory/*.txt (see VoiceInventoryTest).
        unitTests.all { it.systemProperty("updateVoiceInventory", project.hasProperty("updateVoiceInventory").toString()) }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.maplibre.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
