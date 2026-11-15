plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.sparkcyf.voicesipagent.app"
    compileSdk {
        version = release(37)
    }

    flavorDimensions += "llmRuntime"
    productFlavors {
        create("standard") {
            dimension = "llmRuntime"
            // Retains the app's existing Android 11+ compatibility.
        }
        create("onDeviceAi") {
            dimension = "llmRuntime"
            // Arm AI Chat declares minSdk 33; isolate it from the standard build.
            minSdk = 33
        }
    }

    defaultConfig {
        applicationId = "io.github.sparkcyf.voicesipagent"
        minSdk = 30
        targetSdk = 36
        versionCode = 104
        versionName = "0.2.1-selfhost1"
        // Filter all transitive JNI libraries as well as PJSIP's own module outputs.
        ndk {
            //abiFilters += listOf("arm64-v8a", "x86_64")
            abiFilters += "arm64-v8a"
        }
    }

    // Release signing comes from ~/.gradle/gradle.properties (never from the repo); without it the
    // release APK is built unsigned.
    val signingProps = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { providers.gradleProperty("vsa.signing.$it").orNull }
    val releaseSigning = if (signingProps.values.all { it != null }) {
        signingConfigs.create("release") {
            storeFile = file(signingProps.getValue("storeFile")!!)
            storePassword = signingProps["storePassword"]
            keyAlias = signingProps["keyAlias"]
            keyPassword = signingProps["keyPassword"]
        }
    } else null

    buildTypes {
        release {
            signingConfig = releaseSigning
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "META-INF/*.kotlin_module"
    }
}

dependencies {
    implementation(project(":voice-api"))
    implementation(project(":webrtc"))
    implementation("io.github.webrtc-sdk:android-prefixed-stripped:150.7871.04")
    implementation(project(":sip"))
    implementation(project(":gateway"))
    implementation(project(":telephony"))
    implementation(project(":privileged"))
    // Native Android ASR/TTS runtime; model files remain user-imported and offline.
    implementation("com.github.k2fsa:sherpa-onnx:2.0.1")
    // Declared by this module's manifest; keep it on the app compile classpath for lint.
    implementation(libs.shizuku.provider)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    // Official Arm llama.cpp-backed GGUF runtime. Kept in the API-33+ variant only.
    add("onDeviceAiImplementation", "com.arm:ai-chat:0.1.0")

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
}
