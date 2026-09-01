plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}

// ---------------------------------------------------------------------------
// Version consistency
//
// gradle.properties drives the build; courier.util.AppVersion drives what the
// running app reports about itself. If they disagree, the app lies about which
// build it is — which is exactly the failure mode that made a stale jar look
// like broken code. Fail the build instead.
// ---------------------------------------------------------------------------

val courierVersion: String = providers.gradleProperty("courier.versionName").get()
val courierBuild: String = providers.gradleProperty("courier.buildNumber").get()

val checkVersionConsistency by tasks.registering {
    group = "courier"
    description = "Fails if AppVersion.kt disagrees with gradle.properties."

    val appVersionFile = layout.projectDirectory
        .file("shared/src/commonMain/kotlin/courier/util/AppVersion.kt").asFile
    inputs.file(appVersionFile)
    inputs.property("versionName", courierVersion)
    inputs.property("buildNumber", courierBuild)
    outputs.upToDateWhen { false }

    doLast {
        val text = appVersionFile.readText()
        fun constOf(name: String): String? =
            Regex("""const\s+val\s+$name\s*=\s*"?([^"\n]+)"?""")
                .find(text)?.groupValues?.get(1)?.trim()

        val declaredVersion = constOf("VERSION_NAME")
        val declaredBuild = constOf("BUILD_NUMBER")

        val problems = buildList {
            if (declaredVersion != courierVersion) {
                add("VERSION_NAME is \"$declaredVersion\" but courier.versionName is \"$courierVersion\"")
            }
            if (declaredBuild != courierBuild) {
                add("BUILD_NUMBER is $declaredBuild but courier.buildNumber is $courierBuild")
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "AppVersion.kt is out of sync with gradle.properties:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\nUpdate ${appVersionFile.path} to match."
            )
        }
    }
}

tasks.register("publishRelease") {
    group = "courier"
    description = "Builds and publishes both desktop and Android artifacts to release/."
    dependsOn(checkVersionConsistency)
    dependsOn(":desktopApp:publishDesktopRelease", ":androidApp:publishAndroidRelease")
}
