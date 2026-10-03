pluginManagement {
    // Kotlin 2.4 metadata requires R8 >= 9.1.29. Keep AGP/Gradle stable and
    // override only the embedded D8/R8, following the upstream R8 recipe.
    // https://developer.android.com/build/kotlin-support
    // https://r8.googlesource.com/r8/+/refs/heads/main/README.md#replacing-r8-in-agp
    buildscript {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.android.tools:r8:9.1.31") }
    }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Takupoke"
include(":app", ":core")
