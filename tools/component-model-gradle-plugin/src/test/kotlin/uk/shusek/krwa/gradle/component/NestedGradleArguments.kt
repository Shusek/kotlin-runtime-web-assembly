package uk.shusek.krwa.gradle.component

import org.gradle.testkit.runner.GradleRunner

internal fun GradleRunner.withReleaseGateArguments(
    vararg arguments: String,
): GradleRunner =
    withArguments(
        buildList {
            addAll(arguments)
            if (java.lang.Boolean.getBoolean("krwa.gradle.offline")) {
                add("--offline")
            }
        },
    )
