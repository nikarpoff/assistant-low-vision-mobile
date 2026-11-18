pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack is used only for the WebRTC voice activity detector
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.gkonovalov.android-vad")
            }
        }
    }
}

rootProject.name = "assistant-low-vision-mobile"
include(":app")
