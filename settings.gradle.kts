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
    }
}

rootProject.name = "SYLittleToolBox"

include(
    ":app",
    ":core:tool-api",
    ":tools:audio",
    ":tools:image",
    ":tools:quicknote",
    ":tools:textstats",
    ":tools:trail",
    ":tools:wifi",
)
