plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.thedailyflare.reel"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.thedailyflare.reel"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "20"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
