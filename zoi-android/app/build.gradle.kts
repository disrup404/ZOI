plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.    nodisrupt.zoi"
    compileSdk = 36

    defaultConfig {
               tionId =            disrup               tionIk = 26
        targetSdk = 35
        ver        ver        ver    onName         ver        ver        ver    onName         ver         }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementa    implementa    implementa    implemen    implementation("androidx.compose.ui:ui-tooling-preview    implementa    imn("androidx.    implementa    implementa    implementaplementation("androidx.compose.ui:ui-tooling")
}
