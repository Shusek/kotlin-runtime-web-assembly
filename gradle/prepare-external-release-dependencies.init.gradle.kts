import org.gradle.api.artifacts.component.ModuleComponentIdentifier

fun Throwable.referencesMissingKrwaModule(): Boolean {
    var failure: Throwable? = this
    while (failure != null) {
        if (failure.message?.contains("uk.shusek.krwa:") == true) {
            return true
        }
        failure = failure.cause
    }
    return false
}

gradle.projectsEvaluated {
    val prepareExternalReleaseDependencies =
        rootProject.tasks.register("prepareExternalReleaseDependencies") {
            group = "build setup"
            description =
                "Resolves external dependency artifacts needed to run this nested build offline."
        }
    val allowMissingKrwa =
        rootProject.providers
            .gradleProperty("krwa.prepare.allowMissingKrwa")
            .map(String::toBoolean)
            .getOrElse(false)

    rootProject.allprojects.sortedBy { project -> project.path }.forEach { project ->
        val prepareProjectExternalReleaseDependencies =
            project.tasks.register("prepareProjectExternalReleaseDependencies") {
                group = "build setup"
                description =
                    "Resolves external dependency artifacts required by ${project.path} offline."
                doLast {
                    val failures = mutableListOf<String>()
                    project.configurations
                        .filter { configuration ->
                            configuration.isCanBeResolved &&
                                !configuration.name.endsWith("CInterop")
                        }
                        .sortedBy { configuration -> configuration.name }
                        .forEach { configuration ->
                            runCatching {
                                if (allowMissingKrwa) {
                                    val artifacts =
                                        configuration.incoming
                                            .artifactView {
                                                isLenient = true
                                                componentFilter { component ->
                                                    component is ModuleComponentIdentifier &&
                                                        component.group != "uk.shusek.krwa"
                                                }
                                            }
                                            .artifacts
                                    artifacts.artifactFiles.files
                                    artifacts.failures
                                        .filterNot(Throwable::referencesMissingKrwaModule)
                                        .forEach { failure -> throw failure }
                                } else {
                                    configuration.incoming
                                        .artifactView {
                                            componentFilter { component ->
                                                component is ModuleComponentIdentifier
                                            }
                                        }
                                        .artifacts
                                        .artifactFiles
                                        .files
                                }
                            }.onFailure { failure ->
                                failures +=
                                    "${project.path}:${configuration.name}: " +
                                        (failure.message ?: failure.javaClass.name)
                            }
                        }

                    check(failures.isEmpty()) {
                        "Could not prepare nested-build dependency artifacts:\n" +
                            failures.joinToString(separator = "\n")
                    }
                }
            }
        prepareExternalReleaseDependencies.configure {
            dependsOn(prepareProjectExternalReleaseDependencies)
        }
    }
}
