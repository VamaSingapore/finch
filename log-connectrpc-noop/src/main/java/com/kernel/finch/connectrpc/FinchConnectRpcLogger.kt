package com.kernel.finch.connectrpc

import com.connectrpc.Interceptor
import com.connectrpc.StreamFunction
import com.connectrpc.UnaryFunction

/**
 * API-compatible no-op implementation for release builds.
 * Same package + class name as the debug logger so your app compiles unchanged.
 */
@Suppress("unused")
object FinchConnectRpcLogger {

    @JvmStatic
    val logger: Any? = factory() // you can also make this `null` if preferred

    @JvmStatic
    fun factory(): () -> Interceptor = {
        object : Interceptor {
            override fun unaryFunction(): UnaryFunction = UnaryFunction()
            override fun streamFunction(): StreamFunction = StreamFunction()
        }
    }
}
