plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "org.androidaudioplugin.greenhouse.ui"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.build.tools.get()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":greenhouse-host"))
    implementation(project(":androidaudioplugin-ui-compose"))

    implementation(libs.androidx.core.ktx)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    implementation(libs.ui.tooling.preview)
    api(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    api(libs.navigation.compose)
    implementation(libs.compose.audio.controls)

    debugImplementation(libs.ui.tooling)
}
