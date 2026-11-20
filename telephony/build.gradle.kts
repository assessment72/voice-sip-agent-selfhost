plugins {
    alias(libs.plugins.android.library)
}

val compileSdkApi = providers.gradleProperty("android.compileSdk").orNull?.toInt() ?: 36

android {
    namespace = "io.github.sparkcyf.voicesipagent.telephony"
    compileSdk {
        version = release(compileSdkApi)
    }
    defaultConfig {
        minSdk = 30
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":voice-api"))
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
}
