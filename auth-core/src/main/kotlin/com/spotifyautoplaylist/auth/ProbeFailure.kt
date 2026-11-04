package com.spotifyautoplaylist.auth

enum class ProbeFailure {
    INVALID_CLIENT_ID, PORT_IN_USE, CALLBACK_TIMEOUT, CANCELLED,
    AUTHORIZATION_DENIED, AUTHORIZATION_FAILED, BROWSER_UNAVAILABLE,
    NETWORK, TOKEN_HTTP, TOKEN_RESPONSE, INSUFFICIENT_SCOPE,
}

// Safe to display/log: never attach a raw exception, URL, response body or credentials.
class ProbeException(val reason: ProbeFailure, val httpStatus: Int? = null) :
    Exception(reason.name)
