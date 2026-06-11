package uk.shusek.krwa.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WasiPreviewTest {
    @Test
    fun preview2IsStableComponentModel() {
        assertTrue(WasiPreview.PREVIEW2.isStable())
        assertTrue(WasiPreview.PREVIEW2.isComponentModel())
    }

    @Test
    fun preview3IsStableComponentModel() {
        assertEquals("0.3.0", WasiPreview.PREVIEW3.version())
        assertTrue(WasiPreview.PREVIEW3.isStable())
        assertTrue(WasiPreview.PREVIEW3.isComponentModel())
        WasiPreview.PREVIEW3.requireStable()
    }
}
