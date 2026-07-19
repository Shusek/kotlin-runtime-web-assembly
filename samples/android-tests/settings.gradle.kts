pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

val krwaReleaseRepository = providers.gradleProperty("krwa.releaseRepository").orNull

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (krwaReleaseRepository != null) {
            exclusiveContent {
                forRepository {
                    maven {
                        name = "krwaReleaseStaging"
                        url = uri(krwaReleaseRepository)
                    }
                }
                filter {
                    includeGroup("uk.shusek.krwa")
                }
            }
        } else {
            mavenLocal()
        }
        google()
        mavenCentral()
    }
}

include(":device-tests")
