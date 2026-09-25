plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "org.androidaudioplugin.greenhouse.host"
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":greenhouse-engine"))

    implementation(libs.androidx.core.ktx)
    api(libs.lifecycle.runtime.ktx)
    api(libs.activity.compose)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
