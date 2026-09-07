package com.tongxie.copilotgo.data.tools.net

enum class ToolNetworkPolicy {
    PUBLIC_HTTPS,

    /**
     * Only for an explicitly configured tool endpoint, never for URLs supplied by a page or model.
     * Allows private/loopback addresses with valid HTTPS, but not link-local, metadata, multicast,
     * unspecified or reserved addresses. This permission is not transferable through redirects.
     */
    TRUSTED_LAN_HTTPS
}
