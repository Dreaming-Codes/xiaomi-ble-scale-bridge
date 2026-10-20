pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "xiaomi-ble-scale-bridge"
include(":protocol", ":xiaomicloud", ":app")
