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
    }
}

rootProject.name = "iPhoneMiror"

include(
    ":app",
    ":core",
    ":domain",
    ":data",
    ":airplay-protocol",
    ":connection",
    ":mirroring",
    ":device-discovery",
    ":input-controller",
)
