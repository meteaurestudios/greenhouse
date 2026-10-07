plugins {
    alias(libs.plugins.android.library)
}

val nativeLibraryName = "greenhouse-engine"
val nativeSourcesDir = layout.projectDirectory.dir("src/main/cpp")
val prefabHeadersDir = layout.buildDirectory.dir("generated/prefab-headers/$nativeLibraryName")

android {
    namespace = "org.androidaudioplugin.greenhouse.engine"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.build.tools.get()
    ndkVersion = libs.versions.ndk.get()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()

        consumerProguardFiles("consumer-rules.pro")

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

    buildFeatures {
        prefab = true
        prefabPublishing = true
    }

    // Native engine API (EngineApi.h) for other modules' C++ code, e.g. DSP running in a rack slot:
    // find_package(greenhouse-engine REQUIRED CONFIG), then link greenhouse-engine::greenhouse-engine
    prefab {
        create(nativeLibraryName) {
            headers = prefabHeadersDir.get().asFile.absolutePath
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":androidaudioplugin"))

    runtimeOnly(libs.libcxx.provider)

    api(libs.ktmidi)
    implementation(libs.coroutines.android)
    implementation(libs.oboe)

    testImplementation(libs.junit)
}

// The prefab package takes a directory of headers only: copy the public ones (EngineApi.h and what it
// includes) out of the native sources, keeping their layout
val copyPrefabHeaders = tasks.register<Sync>("copyPrefabHeaders") {
    from(nativeSourcesDir) {
        include("EngineApi.h", "slot/SlotProcessor.h")
    }
    into(prefabHeadersDir)
}

tasks.matching { it.name.startsWith("prefab") && it.name.endsWith("Package") }.configureEach {
    dependsOn(copyPrefabHeaders)
}
