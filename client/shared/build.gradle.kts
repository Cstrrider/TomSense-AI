plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        // AGP defaults javac to 1.8 while the Kotlin plugin picks the JDK's
        // own version, and the mismatch is a hard error. Pin both to 17.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)

                // Compose Multiplatform: the conversation view is written
                // once and hosted by both the Android app and the desktop
                // overlay. Native owns audio, assistant role and the local
                // DB; only the rich chat surface is shared.
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sqldelight.android)
                // Tier-0, on-device. The reason "set a timer" never touches
                // the network (spec §4).
                implementation(libs.mediapipe.genai)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.sqldelight.sqlite)
            }
        }
    }
}

android {
    namespace = "org.tomsense.shared"
    compileSdk = 35
    defaultConfig { minSdk = 29 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("TomsenseDb") {
            packageName.set("org.tomsense.db")

            // The .sqm migrations define the schema; Schema.sq holds only
            // queries. This removes the drift a separate CREATE-statement file
            // invites, where new installs get one schema and upgraded devices
            // get another, and the difference shows up only on a device that
            // has actually been upgraded. The app is already deployed with
            // real data, so the upgrade path is the one that has to be right.
            deriveSchemaFromMigrations.set(true)
        }
    }
}

/**
 * Print the device-tool schemas the client advertises.
 *
 * `./gradlew -q :shared:dumpToolSchemas > tools.json` gives the exact JSON the
 * app sends, so tool descriptions can be tested against a live model without
 * building an APK or running an emulator. See DumpToolSchemas.kt.
 */
tasks.register<JavaExec>("dumpToolSchemas") {
    group = "verification"
    description = "Print device-tool schemas as the client advertises them"
    val desktopMain = kotlin.targets.getByName("desktop").compilations.getByName("main")
    dependsOn(desktopMain.compileTaskProvider)
    classpath = files(desktopMain.output.allOutputs) +
        configurations.getByName("desktopRuntimeClasspath")
    mainClass.set("org.tomsense.tools.DumpToolSchemasKt")
}
