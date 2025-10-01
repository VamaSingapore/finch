package com.kernel.finch.connectrpc

import android.util.Log
import com.connectrpc.Headers
import com.connectrpc.Interceptor
import com.connectrpc.StreamFunction
import com.connectrpc.UnaryFunction
import com.connectrpc.http.HTTPResponse
import com.connectrpc.http.UnaryHTTPRequest
import okio.Buffer

/**
 * Finch logger for Connect RPC (client-side).
 *
 * Usage with Connect-Kotlin:
 *   interceptors = listOf(FinchConnectRpcLogger.factory())
 */
@Suppress("unused")
object FinchConnectRpcLogger {

    @JvmStatic
    val logger: Any? = factory()

    /** Connect-Kotlin requires a fresh Interceptor per request/stream. */
    @JvmStatic
    fun factory(): () -> Interceptor {
        return {
            object : Interceptor {
                override fun unaryFunction(): UnaryFunction {
                    val startedAtMs = System.currentTimeMillis()
                    var req: UnaryHTTPRequest? = null
                    return UnaryFunction(
                        requestFunction = { r ->
                            req = r
                            r // pass-through
                        },
                        responseFunction = { resp ->
                            runCatching { logUnary(req, resp, startedAtMs) }
                            resp // pass-through
                        }
                    )
                }

                override fun streamFunction(): StreamFunction {
                    // Minimal streaming pass-through; safe and compilable.
                    return StreamFunction(
                        requestFunction = { it },
                        requestBodyFunction = { it },
                        streamResultFunction = { it }
                    )
                }
            }
        }
    }

    private fun logUnary(req: UnaryHTTPRequest?, res: HTTPResponse, startedAtMs: Long) {
        val durationMs = System.currentTimeMillis() - startedAtMs

        // HTTP method from the request (almost always POST)
        val method = req?.httpMethod?.string ?: "POST"
        // Path from java.net.URL
        val path = req?.url?.path ?: ""
        // HTTP status code is on res.status
        val code = runCatching { res.status }.getOrDefault(-1)

        // Headers are com.connectrpc.Headers (treat them as Map<String, List<String>>)
        val reqHdrs = stringifyHeaders(req?.headers)
        val resHdrs = stringifyHeaders(res.headers)

        // Payload sizes. Unary request uses okio.Buffer; response commonly uses Buffer too.
        val reqBytes = runCatching { req?.message?.size ?: -1L }.getOrDefault(-1L)
        val resBytes = runCatching {
            // If HTTPResponse.message is Buffer, .size is Long; if ByteArray, cast size to Long.
            val m = res.message
            when (m) {
                is Buffer -> m.size
                is ByteArray -> m.size.toLong()
                else -> -1L
            }
        }.getOrDefault(-1L)

        val message = buildString {
            append("ConnectRPC[unary] $method $path -> $code in ${durationMs}ms")
            if (reqHdrs.isNotEmpty()) append("\n  requestHeaders=$reqHdrs")
            if (resHdrs.isNotEmpty()) append("\n  responseHeaders=$resHdrs")
            if (reqBytes >= 0) append("\n  requestBytes=$reqBytes")
            if (resBytes >= 0) append("\n  responseBytes=$resBytes")
        }

        finchLog(message)
    }

    private fun stringifyHeaders(headers: Headers?): String {
        if (headers == null || headers.isEmpty()) return ""
        return headers.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "$k=${v.joinToString()}"
        }
    }

    /**
     * Try to log into Finch's logger if present, else Logcat.
     * (Never throw from logging.)
     */
    private fun finchLog(message: String) {
        // Finch internal logger (if your build includes it)
        runCatching {
            val clazz = Class.forName("com.kernel0x.finch.log.FinchLogger")
            val method = clazz.getMethod("log", String::class.java)
            method.invoke(null, message)
        }.onFailure {
            // Fallback to Logcat
            runCatching { Log.d("Finch-ConnectRPC", message) }
        }
    }
}
