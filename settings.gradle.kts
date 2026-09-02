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

rootProject.name = "greenhouse"

include(":app")

include(":androidaudioplugin")
project(":androidaudioplugin").projectDir = file("external/aap-core/androidaudioplugin")

include(":androidaudioplugin-ui-compose")
project(":androidaudioplugin-ui-compose").projectDir = file("external/aap-core/androidaudioplugin-ui-compose")
