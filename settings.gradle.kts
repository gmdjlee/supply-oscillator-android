pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "supply-oscillator-android"

include(":app")

// kotlin_krx 로컬 모듈 (MIGRATION.md Section 6 참조)
include(":krxkt")
project(":krxkt").projectDir = File("../kotlin_krx")