plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.media3:media3-transformer:1.11.0")
    implementation("androidx.media3:media3-common:1.11.0")
}

android {
    namespace = "com.thedailyflare.reel"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.thedailyflare.reel"
        minSdk = 26
        targetSdk = 35
        versionCode = 27
        versionName = "27"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
