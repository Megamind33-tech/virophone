package com.viroreach.feature.calling

import com.google.gson.JsonParser
import org.json.JSONObject

data class SignalingParseResult(
    val message: SignalingMessage? = null,
    val serverAckError: String? = null,
    val warning: String? = null,
)

object SignalingFrameParser {
    fun parse(text: String): SignalingParseResult {
        return try {
            val json = JsonParser.parseString(text).asJsonObject
            if (!json.has("type")) {
                val error = json.get("error")?.takeIf { it.isJsonPrimitive }?.asString
                return if (error != null) {
                    SignalingParseResult(serverAckError = error)
                } else {
                    SignalingParseResult()
                }
            }
            SignalingParseResult(
                message = SignalingMessage(
                    type = json.get("type").asString,
                    callId = json.get("callId")?.asString
                        ?: json.get("roomId")?.asString
                        ?: "",
                    fromUserId = json.get("fromUserId")?.asString
                        ?: json.get("userId")?.asString,
                    fromDeviceId = json.get("fromDeviceId")?.asString
                        ?: json.get("deviceId")?.asString,
                    payload = json.get("payload")?.takeIf { it.isJsonObject }?.let {
                        JSONObject(it.toString())
                    } ?: json.get("participants")?.takeIf { it.isJsonArray }?.let {
                        JSONObject().put("participants", org.json.JSONArray(it.toString()))
                    },
                    roomId = json.get("roomId")?.asString,
                ),
            )
        } catch (e: Exception) {
            SignalingParseResult(warning = e.message?.take(120))
        }
    }
}
