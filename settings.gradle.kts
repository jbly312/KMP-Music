rootProject.name = "VideoPlayerKMP"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":sync-protocol")
include(":server")

// В Docker собирается только сервер: без Compose-модулей сборка образа быстрее и легче
if (System.getenv("KMP_SERVER_ONLY") == null) {
    include(":desktopApp")
    include(":androidApp")
    include(":shared")
}