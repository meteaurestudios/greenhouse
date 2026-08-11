pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven("https://plugins.gradle.org/m2/")
        maven("https://jitpack.io")
    }
}

rootProject.name = "aap-test-host"

include(":app")

include(":androidaudioplugin")
project(":androidaudioplugin").projectDir = file("external/aap-core/androidaudioplugin")

include(":androidaudioplugin-ui-compose")
project(":androidaudioplugin-ui-compose").projectDir = file("external/aap-core/androidaudioplugin-ui-compose")

include(":androidaudioplugin-manager")
project(":androidaudioplugin-manager").projectDir = file("external/aap-core/androidaudioplugin-manager")

include(":androidaudioplugin-midi-device-service")
project(":androidaudioplugin-midi-device-service").projectDir = file("external/aap-core/androidaudioplugin-midi-device-service")

include(":androidaudioplugin-js-controller")
project(":androidaudioplugin-js-controller").projectDir = file("external/aap-core/androidaudioplugin-js-controller")
