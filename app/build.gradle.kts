plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

android {
    namespace = "com.thedailyflare.reel"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.thedailyflare.reel"
        minSdk = 26
        targetSdk = 35
        versionCode = 45
        versionName = "45-size-optimized"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
