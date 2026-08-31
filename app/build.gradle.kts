plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.media3:media3-transformer:1.10.1")
    implementation("androidx.media3:media3-common:1.10.1")
}

android {
    namespace = "com.thedailyflare.reel"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.thedailyflare.reel"
        minSdk = 26
        targetSdk = 35
        versionCode = 31
        versionName = "31"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
