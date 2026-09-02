plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    kotlin("android")
}

val courierVersion: String = providers.gradleProperty("courier.versionName").get()
val courierBuild: Int = providers.gradleProperty("courier.buildNumber").get().toInt()

android {
    namespace = "courier.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.courier.app"
        minSdk = 24
        targetSdk = 35
        versionCode = courierBuild
        versionName = courierVersion

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties"
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }
}

// ---------------------------------------------------------------------------
// Release packaging — mirrors desktopApp's publishDesktopRelease.
//
// Uses assembleDebug because that is what has actually been shipped: the release
// buildType has no signing config, so an assembleRelease APK would be unsigned
// and non-installable. Switch this to assembleRelease once signing is set up.
// ---------------------------------------------------------------------------

val androidReleaseDir = rootProject.layout.projectDirectory.dir("release")

val publishAndroidVersioned by tasks.registering(Copy::class) {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug")) { include("*.apk") }
    into(androidReleaseDir)
    rename { "Courier-Android-v$courierVersion.apk" }
}

val publishAndroidLatest by tasks.registering(Copy::class) {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug")) { include("*.apk") }
    into(androidReleaseDir)
    rename { "Courier-Android-latest.apk" }
}

tasks.register("publishAndroidRelease") {
    group = "courier"
    description = "Builds the Android APK and publishes it to release/ as both " +
        "Courier-Android-v$courierVersion.apk and Courier-Android-latest.apk."
    dependsOn(publishAndroidVersioned, publishAndroidLatest)

    val outFile = androidReleaseDir.file("Courier-Android-latest.apk").asFile
    doLast {
        if (!outFile.isFile) {
            throw GradleException("publishAndroidRelease produced no APK at ${outFile.absolutePath}.")
        }
        logger.lifecycle("Published ${outFile.absolutePath} (${outFile.length() / 1024 / 1024} MB)")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.youtubedl.android.library)
    implementation(libs.youtubedl.android.ffmpeg)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
}
