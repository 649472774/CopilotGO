package com.tongxie.copilotgo.data.tools

internal data class BoundedToolText(val text: String, val truncated: Boolean)

internal fun boundToolText(value: String, maxBytes: Int): BoundedToolText {
    var index = 0
    var bytes = 0
    while (index < value.length) {
        val point = value.codePointAt(index)
        val width = when {
            point <= 0x7f -> 1
            point <= 0x7ff -> 2
            point <= 0xffff -> 3
            else -> 4
        }
        if (bytes + width > maxBytes) return BoundedToolText(value.substring(0, index), true)
        bytes += width
        index += Character.charCount(point)
    }
    return BoundedToolText(value, false)
}
