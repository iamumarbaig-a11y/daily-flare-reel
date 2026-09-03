plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.media3:media3-transformer:1.10.1")
    implementation("androidx.media3:media3-common:1.10.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
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
        versionCode = 34
        versionName = "34"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
