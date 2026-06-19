settingsEvaluated {
    dependencyResolutionManagement {
        repositories {
            exclusiveContent {
                forRepository {
                    mavenLocal()
                }
                filter {
                    includeModule("uk.shusek.krwa", "runtime")
                    includeModule("uk.shusek.krwa", "runtime-jvm")
                }
            }
        }
    }
}
