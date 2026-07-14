// SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
// SPDX-License-Identifier: MPL-2.0

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://nexus.inductiveautomation.com/repository/public/")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven {
            url = uri("https://nexus.inductiveautomation.com/repository/public/")
        }
    }
}

rootProject.name = "fluxy-java"

val ignitionTarget = providers.gradleProperty("ignitionTarget").orElse("8.3").get()
if (ignitionTarget.startsWith("8.1")) {
    include(":gateway81")
} else {
    include(":gateway")
}
