package uk.shusek.krwa.testing

import java.util.ArrayDeque

class ArgsAdapter private constructor() {
    private val stack = ArrayDeque<Long>()

    fun build(): LongArray {
        val result = LongArray(stack.size)
        var i = stack.size - 1
        while (!stack.isEmpty()) {
            result[i--] = stack.pop()
        }
        return result
    }

    fun add(args: LongArray): ArgsAdapter {
        for (arg in args) {
            stack.push(arg)
        }
        return this
    }

    fun add(arg: Long): ArgsAdapter {
        stack.push(arg)
        return this
    }

    companion object {
        @JvmStatic fun builder(): ArgsAdapter = ArgsAdapter()
    }
}
