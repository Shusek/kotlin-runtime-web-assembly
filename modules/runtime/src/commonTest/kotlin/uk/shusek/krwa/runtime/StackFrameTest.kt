package uk.shusek.krwa.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import uk.shusek.krwa.wasm.WasmParser
import uk.shusek.krwa.wasm.types.OpCode

class StackFrameTest {
    @Test
    fun i32AddCombinesTopTwoStackSlotsInPlace() {
        val stack = stackOf(10, 20, 30)

        stack.i32Add()

        assertEquals(listOf(10L, 50L), stack.values())
    }

    @Test
    fun i32SubCombinesTopTwoStackSlotsInPlace() {
        val stack = stackOf(10, 20, 30)

        stack.i32Sub()

        assertEquals(listOf(10L, -10L), stack.values())
    }

    @Test
    fun discardToSizeKeepingTopPreservesTopValue() {
        val stack = stackOf(10, 20, 30)

        stack.discardToSizeKeepingTop(1)

        assertEquals(listOf(10L, 30L), stack.values())
    }

    @Test
    fun discardToSizeKeepingTop2PreservesTopValuesInOrder() {
        val stack = stackOf(10, 20, 30, 40)

        stack.discardToSizeKeepingTop2(1)

        assertEquals(listOf(10L, 30L, 40L), stack.values())
    }

    @Test
    fun discardToSizeKeepingTopFallbackPreservesLegacyZeroFill() {
        val single = MStack()
        single.discardToSizeKeepingTop(0)

        assertEquals(listOf(0L), single.values())

        val pair = stackOf(10)
        pair.discardToSizeKeepingTop2(0)

        assertEquals(listOf(0L, 10L), pair.values())
    }

    @Test
    fun controlTransferWithoutResultsDiscardsToFrameHeight() {
        val stack = stackOf(10, 20, 30)
        val ctrlFrame = ctrlFrame(height = 1, endValues = 0)

        StackFrame.doControlTransfer(ctrlFrame, stack)

        assertEquals(listOf(10L), stack.values())
    }

    @Test
    fun controlTransferWithSingleResultPreservesResult() {
        val stack = stackOf(10, 20, 30)
        val ctrlFrame = ctrlFrame(height = 1, endValues = 1)

        StackFrame.doControlTransfer(ctrlFrame, stack)

        assertEquals(listOf(10L, 30L), stack.values())
    }

    @Test
    fun controlTransferWithTwoResultsPreservesResultOrder() {
        val stack = stackOf(10, 20, 30, 40)
        val ctrlFrame = ctrlFrame(height = 1, endValues = 2)

        StackFrame.doControlTransfer(ctrlFrame, stack)

        assertEquals(listOf(10L, 30L, 40L), stack.values())
    }

    @Test
    fun controlTransferWithMultipleResultsPreservesResultOrder() {
        val stack = stackOf(10, 20, 30, 40, 50)
        val ctrlFrame = ctrlFrame(height = 1, endValues = 3)

        StackFrame.doControlTransfer(ctrlFrame, stack)

        assertEquals(listOf(10L, 30L, 40L, 50L), stack.values())
    }

    @Test
    fun branchToBlockLeavesTargetFrameWithoutTransferringValues() {
        val frame = stackFrame()
        val stack = stackOf(10, 20, 30)
        frame.pushCtrl(OpCode.CALL, startValues = 0, returnValues = 0, height = 0)
        frame.pushCtrl(OpCode.BLOCK, startValues = 0, returnValues = 1, height = 1)

        frame.branchTo(0, stack)

        assertEquals(2, frame.ctrlStackSize())
        assertEquals(listOf(10L, 20L, 30L), stack.values())
    }

    @Test
    fun branchToLoopTransfersValuesAndLeavesLoopFrame() {
        val frame = stackFrame()
        val stack = stackOf(10, 20, 30)
        frame.pushCtrl(OpCode.CALL, startValues = 0, returnValues = 0, height = 0)
        frame.pushCtrl(OpCode.LOOP, startValues = 0, returnValues = 1, height = 1)
        frame.pushCtrl(OpCode.BLOCK, startValues = 0, returnValues = 0, height = 2)

        frame.branchTo(1, stack)

        assertEquals(2, frame.ctrlStackSize())
        assertEquals(listOf(10L, 30L), stack.values())
    }

    @Test
    fun popCtrlTillCallClearsNestedControlFrames() {
        val frame = stackFrame()
        frame.pushCtrl(OpCode.CALL, startValues = 0, returnValues = 0, height = 0)
        frame.pushCtrl(OpCode.BLOCK, startValues = 0, returnValues = 0, height = 1)
        frame.pushCtrl(OpCode.LOOP, startValues = 0, returnValues = 0, height = 2)

        val ctrlFrame = frame.popCtrlTillCall()

        assertEquals(OpCode.CALL, ctrlFrame.opCode)
        assertEquals(0, frame.ctrlStackSize())
    }

    @Test
    fun popCtrlTillCallAndTransferClearsNestedControlFramesAndPreservesReturnValue() {
        val frame = stackFrame()
        val stack = stackOf(10, 20, 30)
        frame.pushCtrl(OpCode.CALL, startValues = 0, returnValues = 1, height = 1)
        frame.pushCtrl(OpCode.BLOCK, startValues = 0, returnValues = 0, height = 2)
        frame.pushCtrl(OpCode.LOOP, startValues = 0, returnValues = 0, height = 3)

        frame.popCtrlTillCallAndTransfer(stack)

        assertEquals(0, frame.ctrlStackSize())
        assertEquals(listOf(10L, 30L), stack.values())
    }

    private fun ctrlFrame(
        height: Int,
        startValues: Int = 0,
        endValues: Int,
    ): CtrlFrame = CtrlFrame(
        opCode = OpCode.BLOCK,
        startValues = startValues,
        endValues = endValues,
        height = height,
    )

    private fun stackOf(vararg values: Long): MStack = MStack().apply {
        values.forEach(::push)
    }

    private fun stackFrame(): StackFrame =
        StackFrame(
            Instance.builder(WasmParser.parse(EMPTY_WASM))
                .withInitialize(false)
                .withStart(false)
                .build(),
            0,
            longArrayOf(),
        )

    private fun MStack.values(): List<Long> = array().copyOf(size()).toList()

    private companion object {
        val EMPTY_WASM = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
