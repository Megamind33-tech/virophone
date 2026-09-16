package com.viroreach.app.auth

import com.viroreach.core.network.ApiDiagnostics
import com.viroreach.core.network.SanitizedApiFailure

object ConsumerAuthErrorMapper {
    fun fromThrowable(throwable: Throwable): String {
        if (throwable is IllegalArgumentException) {
            return "Please enter a valid phone number."
        }
        val failure = ApiDiagnostics.parseFailure("POST", "/api/v1/auth", throwable)
        if (failure.httpStatus > 0) {
            return fromFailure(failure)
        }
        val msg = throwable.message?.lowercase().orEmpty()
        return when {
            msg.contains("unable to resolve host") ||
                msg.contains("network") ||
                msg.contains("timeout") ||
                msg.contains("connection") ->
                "No Internet connection."
            msg.contains("invalid") && msg.contains("phone") ->
                "Please enter a valid phone number."
            else -> "Something went wrong. Please try again."
        }
    }

    fun fromFailure(failure: SanitizedApiFailure): String = when {
        failure.httpStatus == 429 -> "Too many attempts. Try again shortly."
        failure.httpStatus == 401 -> "We couldn't verify that code."
        failure.httpStatus in 500..599 -> "Something went wrong. Please try again."
        failure.errorCode == "NETWORK_ERROR" -> "No Internet connection."
        failure.errorCode == "INVALID_E164" -> "Please enter a valid phone number."
        failure.errorCode?.contains("OTP", ignoreCase = true) == true ->
            "We couldn't verify that code."
        else -> failure.message?.take(120)
            ?: "Something went wrong. Please try again."
    }
}
