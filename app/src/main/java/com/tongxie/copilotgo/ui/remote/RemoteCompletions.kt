package com.tongxie.copilotgo.ui.remote

internal class PendingWebCallback<T> {
    private var nextId = 0L
    private var pending: Pair<Long, (T?) -> Unit>? = null

    val isPending: Boolean get() = pending != null
    fun matches(id: Long): Boolean = pending?.first == id

    fun begin(callback: (T?) -> Unit): Long? {
        if (pending != null) {
            callback(null)
            return null
        }
        val id = ++nextId
        pending = id to callback
        return id
    }

    fun complete(id: Long, result: T?): Boolean {
        val current = pending ?: return false
        if (current.first != id) return false
        pending = null
        current.second(result)
        return true
    }

    fun cancel() {
        val current = pending ?: return
        pending = null
        current.second(null)
    }
}

/** The removal callback is the barrier; a Boolean false means there were no cookies. */
internal class CookieLogoutBarrier {
    private var pending = false
    private var generation = 0L

    fun begin(
        stopPage: () -> Unit,
        removeCookies: ((Boolean) -> Unit) -> Unit,
        afterRemoval: () -> Unit
    ): Boolean {
        if (pending) return false
        pending = true
        val id = ++generation
        stopPage()
        removeCookies {
            if (pending && generation == id) {
                pending = false
                afterRemoval()
            }
        }
        return true
    }

    fun invalidate() {
        pending = false
        generation++
    }
}
