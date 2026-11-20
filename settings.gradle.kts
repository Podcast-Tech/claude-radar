pluginManagement {
    repositories {
        // Google-hosted mirror of Maven Central (avoids rate limits)
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Google-hosted mirror of Maven Central (avoids rate limits)
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        mavenCentral()
    }
}
rootProject.name = "claude-radar"
include(":app")
