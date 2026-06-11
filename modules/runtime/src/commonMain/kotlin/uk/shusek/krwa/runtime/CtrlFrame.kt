package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.OpCode

class CtrlFrame
constructor(
    // OpCode of the current Control Flow instruction
    val opCode: OpCode,
    // params or inputs
    val startValues: Int,
    // returns or outputs
    val endValues: Int,
    // the height of the stack before entering the current Control Flow instruction
    val height: Int,
    // the program counter of a TRY_TABLE block
    // TODO: do we have a better way of doing this?
    val pc: Int = 0,
)
