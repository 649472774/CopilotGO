package com.tongxie.copilotgo.util

import android.util.Log
import com.tongxie.copilotgo.BuildConfig

object Logger {
    private const val DEFAULT_TAG = "CopilotGo"

    fun d(msg: String, tag: String = DEFAULT_TAG) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    fun i(msg: String, tag: String = DEFAULT_TAG) {
        Log.i(tag, msg)
    }

    fun w(msg: String, tag: String = DEFAULT_TAG, throwable: Throwable? = null) {
        if (throwable != null) Log.w(tag, msg, throwable) else Log.w(tag, msg)
    }

    fun e(msg: String, tag: String = DEFAULT_TAG, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, msg, throwable) else Log.e(tag, msg)
    }
}
