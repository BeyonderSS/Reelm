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
        // NewPipe Extractor (+ its nanojson fork) only publish to JitPack
        maven("https://jitpack.io") { content { includeGroup("com.github.TeamNewPipe") } }
    }
}

rootProject.name = "AniStream"

include(":app")
include(":core:network")
include(":core:parser")
include(":core:database")
include(":core:common")
include(":core:media")
include(":core:account")
include(":core:ui")
include(":core:trailer")
include(":domain")
include(":player")
include(":feature:home")
include(":feature:search")
include(":feature:anime-detail")
include(":feature:player")
include(":feature:favorites")
include(":feature:downloads")
include(":feature:schedule")
include(":feature:genre-browse")
include(":feature:movies")
include(":feature:livetv")
include(":feature:party")
include(":feature:admin")
include(":feature:tv")
