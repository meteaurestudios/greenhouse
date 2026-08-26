import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")

    if (file.exists()) {
        file.inputStream().use { load(it) }
    }

}

fun signingProp(vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key ->
        System.getenv(key)
            ?: keystoreProperties.getProperty(key)
            ?: (findProperty(key) as? String)
    }
}

val releaseStoreFilePath = signingProp("release.keystore.path", "RELEASE_KEYSTORE_PATH")
val releaseStorePassword = signingProp("release.keystore.password", "RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProp("release.key.alias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingProp("release.key.password", "RELEASE_KEY_PASSWORD")

android {
    namespace = "org.androidaudioplugin.host"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.build.tools.get()
    ndkVersion = libs.versions.ndk.get()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "org.androidaudioplugin.host"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            version = libs.versions.cmake.get()
            path("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        val storeFileObj = releaseStoreFilePath?.let { path ->
            val directFile = file(path)

            if (directFile.exists()) {
                directFile
            } else {
                rootProject.file(path).takeIf { it.exists() }
            }

        }

        if (storeFileObj != null && !releaseStorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank()) {
            create("release") {
                storeFile = storeFileObj
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword ?: releaseStorePassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }

    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            val releaseSigningConfig = signingConfigs.findByName("release")

            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }

            installation {
                enableBaselineProfile = false
            }
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/assets/dexopt/**"
        }
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {
    implementation(project(":androidaudioplugin"))
    implementation(project(":androidaudioplugin-ui-compose"))

    runtimeOnly(libs.libcxx.provider)

    implementation(libs.androidx.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)

    implementation(libs.compose.audio.controls)
    implementation(libs.ktmidi)
    implementation(libs.oboe)

    debugImplementation(libs.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.test.espresso.core)
}
