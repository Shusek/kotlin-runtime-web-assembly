package uk.shusek.krwa.sample

import kotlin.test.Test
class IosShowcaseTest {
    @Test
    fun runsCoreRuntimeShowcaseOnIos() {
        printShowcaseReport(
            "Kotlin Runtime Web Assembly iOS simulator showcase",
            runKmpShowcase(),
        )
    }
}
