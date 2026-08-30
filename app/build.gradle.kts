plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.thedailyflare.reel"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.thedailyflare.reel"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "18"
    }
}
