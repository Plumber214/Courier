import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(libs.kotlinx.coroutines.swing)
}

val courierVersion: String = providers.gradleProperty("courier.versionName").get()

compose.desktop {
    application {
        mainClass = "courier.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Courier"
            packageVersion = courierVersion
            description = "Minimal Video Downloader for YouTube, TikTok, Instagram, and Facebook"
            vendor = "Courier"
        }
    }
}

// ---------------------------------------------------------------------------
// Release packaging
//
// `packageUberJarForCurrentOS` writes to build/compose/jars/, which ACCUMULATES
// one jar per version ever built and is never cleaned. Nothing previously copied
// from there into release/, so release/ drifted behind the build by whole
// versions and users ran stale code. These tasks close that gap.
//
// The `include` below is pinned to the current version on purpose: a blanket
// "*.jar" would pick nondeterministically from the accumulated jars.
// ---------------------------------------------------------------------------

val releaseDir = rootProject.layout.projectDirectory.dir("release")
val uberJarPattern = "*-$courierVersion.jar"

val publishDesktopVersioned by tasks.registering(Copy::class) {
    dependsOn("packageUberJarForCurrentOS")
    from(layout.buildDirectory.dir("compose/jars")) { include(uberJarPattern) }
    into(releaseDir)
    rename { "Courier-Desktop-v$courierVersion.jar" }
}

val publishDesktopLatest by tasks.registering(Copy::class) {
    dependsOn("packageUberJarForCurrentOS")
    from(layout.buildDirectory.dir("compose/jars")) { include(uberJarPattern) }
    into(releaseDir)
    rename { "Courier-Desktop-latest.jar" }
}

tasks.register("publishDesktopRelease") {
    group = "courier"
    description = "Builds the desktop uber jar and publishes it to release/ as both " +
        "Courier-Desktop-v$courierVersion.jar and Courier-Desktop-latest.jar."
    dependsOn(publishDesktopVersioned, publishDesktopLatest)

    val outFile = releaseDir.file("Courier-Desktop-latest.jar").asFile
    doLast {
        if (!outFile.isFile) {
            throw GradleException(
                "publishDesktopRelease produced no jar at ${outFile.absolutePath}. " +
                    "Expected build/compose/jars/$uberJarPattern to exist."
            )
        }
        logger.lifecycle("Published ${outFile.absolutePath} (${outFile.length() / 1024 / 1024} MB)")
    }
}
