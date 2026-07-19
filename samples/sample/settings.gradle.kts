pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

val krwaReleaseRepository = providers.gradleProperty("krwa.releaseRepository").orNull

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
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
        }
        mavenCentral()
    }
}

rootProject.name = "krwa-runtime-sample"

if (krwaReleaseRepository == null) {
    includeBuild("../..")
}
