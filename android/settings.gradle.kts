pluginManagement {
    repositories {
        google()
        // repo1.maven.org is Maven Central's primary host; used explicitly because
        // repo.maven.apache.org does not resolve on some networks.
        maven("https://repo1.maven.org/maven2/")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Google's repo for the groups it hosts. tasks-vision is excluded because its 17 MB AAR is
        // fetched with a resumable download into ~/.m2 on slow networks (docs/phase0/runbook.md).
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\..*")
                excludeModule("com.google.mediapipe", "tasks-vision")
            }
        }
        maven("https://repo1.maven.org/maven2/")
        mavenLocal { content { includeModule("com.google.mediapipe", "tasks-vision") } }
    }
}
rootProject.name = "MotionHubSensor"
include(":core", ":app", ":tvapp")
