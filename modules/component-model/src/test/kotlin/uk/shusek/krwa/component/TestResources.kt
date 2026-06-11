package uk.shusek.krwa.component

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal fun copyTestFixtureProject(name: String, target: Path) {
    val source = componentModelTestFixturesDir().resolve(name)
    require(Files.isDirectory(source)) { "Test fixture project not found: $source" }

    Files.walk(source).use { paths ->
        paths.forEach { path ->
            val relative = source.relativize(path)
            val destination = target.resolve(relative.toString())
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination)
            } else {
                Files.createDirectories(destination.parent)
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

private fun componentModelTestFixturesDir(): Path =
    repositoryRoot().resolve("modules/component-model/src/test/fixtures")

private fun repositoryRoot(): Path {
    var current = Path.of("").toAbsolutePath()
    while (current.parent != null && !Files.exists(current.resolve("gradlew"))) {
        current = current.parent
    }
    return current
}
