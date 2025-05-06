package com.kernel.finch.common.loggers.utils

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.util.*

object GsonUtil {

    private val gson: Gson by lazy {
        GsonBuilder()
            .setPrettyPrinting()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            // Uses Gson's built-in Date adapter under the hood:
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
            .create()
    }

    val instance: Gson
        get() = gson
}
