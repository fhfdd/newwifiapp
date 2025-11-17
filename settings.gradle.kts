pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS) // 优先使用settings的仓库
    repositories {
        google()       // 移动到这里
        mavenCentral() // 移动到这里
    }
}

rootProject.name = "IndoorNavBlind"
include(":app")