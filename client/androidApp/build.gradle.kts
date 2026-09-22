plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "org.tomsense.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.tomsense.android"
        // API 29 is the floor for the modern VoiceInteractionSession assist
        // APIs this app is built around (onHandleAssist(AssistState)).
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-beta"
    }

    buildFeatures {
        // The launcher overlay protocol is AIDL; see feed/FeedOverlayService.kt.
        aidl = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.savedstate)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.okhttp)
    // shared declares these as `implementation`, so they are not on the
    // consumer's compile classpath — the app wires its own HttpClient and
    // needs them directly.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqldelight.android)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
}
