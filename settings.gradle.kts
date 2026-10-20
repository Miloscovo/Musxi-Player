pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "MusxiAndroid"
include(":app", ":core", ":accounts", ":platform-kugou", ":platform-qq", ":platform-netease", ":playback")
