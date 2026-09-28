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

rootProject.name = "clearlane"

// :core is plain Kotlin on the JVM, so the routing maths can be unit tested
// without an emulator. :data owns the network and the UAE datasets. :app is
// only the Compose layer on top.
include(":core")
include(":data")
include(":app")
