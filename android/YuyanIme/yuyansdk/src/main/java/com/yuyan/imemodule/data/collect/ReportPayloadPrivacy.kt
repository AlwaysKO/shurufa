package com.yuyan.imemodule.data.collect

import kotlinx.serialization.json.*

/** Filter individual content fields, never scan Base64 or the JSON transport envelope. */
internal fun filterChatReportPayload(payload: String): String {
    val body = Json.parseToJsonElement(payload).jsonObject
    val messages = body.getValue("messages").jsonArray.filter { value ->
        val message = value.jsonObject
        CollectionConsent.allowsText(message["text"]?.jsonPrimitive?.contentOrNull) &&
            message["metadata"]?.jsonObject?.values.orEmpty().all { CollectionConsent.allowsText(it.jsonPrimitive.contentOrNull) }
    }
    return JsonObject(body + ("messages" to JsonArray(messages))).toString()
}
