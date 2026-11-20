plugins {
    alias(libs.plugins.android.library)
}

val compileSdkApi = providers.gradleProperty("android.compileSdk").orNull?.toInt() ?: 37

android {
    namespace = "io.github.sparkcyf.voicesipagent.privileged"
    compileSdk {
        version = release(compileSdkApi)
    }
    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures {
        aidl = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":voice-api"))
    api(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
}
