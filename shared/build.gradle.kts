plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val openjfxVersion = "21.0.2"

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    jvm("desktop") {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmCommonMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.bcpkix.jdk18on)
                implementation(libs.bcprov.jdk18on)
                implementation(libs.jmdns)
            }
        }

        // Tests for jvmCommonMain code. Without this they have to live in
        // commonTest, where they only compile because desktopTest happens to
        // pull commonTest sources in against the desktop classpath - and would
        // break the moment Android unit tests are run.
        val jvmCommonTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val androidUnitTest by getting { dependsOn(jvmCommonTest) }
        val desktopTest by getting { dependsOn(jvmCommonTest) }

        val androidMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.appcompat)
                implementation(libs.ktor.client.cio)
                implementation(libs.youtubedl.android.library)
                implementation(libs.youtubedl.android.ffmpeg)
            }
        }

        val desktopMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation("org.openjfx:javafx-media:$openjfxVersion:win")
                implementation("org.openjfx:javafx-swing:$openjfxVersion:win")
                implementation("org.openjfx:javafx-graphics:$openjfxVersion:win")
                implementation("org.openjfx:javafx-controls:$openjfxVersion:win")
                implementation("org.openjfx:javafx-base:$openjfxVersion:win")
            }
        }
    }
}

android {
    namespace = "courier.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    packaging {
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
