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
    implementation(libs.jna)
    implementation(libs.jna.platform)
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
// Uber jar hygiene
//
// BouncyCastle ships SIGNED jars. Flattening them into an uber jar carries
// META-INF/BC2048KE.SF and .DSA along with them, and the JVM then refuses to
// load anything from the archive because the contents no longer match the
// signature. The symptom is maximally misleading:
//
//     Error: Could not find or load main class courier.desktop.MainKt
//     Caused by: java.lang.ClassNotFoundException: courier.desktop.MainKt
//
// even though MainKt.class is present and the manifest names it correctly.
// META-INF/INDEX.LIST breaks class lookup the same way: it tells the loader
// which packages live in which jar, and a copy inherited from a dependency is
// wrong for the merged archive.
//
// This shipped in v1.4.0 and v1.5.0 - both jars are unlaunchable. v1.3.0
// predates the BouncyCastle dependency and runs fine, which is what made the
// regression traceable.
// ---------------------------------------------------------------------------

// Applied to every Jar task in this project rather than to
// packageUberJarForCurrentOS by name: the Compose plugin registers that task
// lazily, so `tasks.named` at configuration time fails with "Task with name
// 'packageUberJarForCurrentOS' not found". Nothing we build should carry
// another project's signatures or package index anyway.
//
// This does not touch the MSI/EXE distributions, which ship dependency jars
// unmodified and whose signatures are therefore still valid.
// Note the fully qualified type. The Kotlin DSL's default `Jar` import is
// org.gradle.api.tasks.bundling.Jar, which is a SUBCLASS of the
// org.gradle.jvm.tasks.Jar that Compose registers this task as - so
// `withType<Jar>()` silently matches nothing and the build still succeeds
// while producing an unlaunchable jar.
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    exclude("META-INF/INDEX.LIST")
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
