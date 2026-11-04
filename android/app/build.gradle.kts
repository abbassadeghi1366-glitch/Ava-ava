plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    namespace = "com.avaai"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.avaai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Override with -PAVA_BASE_URL=https://your-deployed-backend.example
        val avaBaseUrl = providers.gradleProperty("AVA_BASE_URL").orElse("https://YOUR-AVA-SERVER.onrender.com").get()
        buildConfigField("String", "BASE_URL", "\"$avaBaseUrl\"")
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Native 3D GLB rendering with Filament + Jetpack Compose
    implementation("io.github.sceneview:sceneview:4.26.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
