enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven("https://jitpack.io")
            }
            filter {
                includeGroup("com.github.nikita36078")
            }
        }
    }
}
rootProject.name = "JL-Mod-Plus"
include(":app", ":dexlib")
