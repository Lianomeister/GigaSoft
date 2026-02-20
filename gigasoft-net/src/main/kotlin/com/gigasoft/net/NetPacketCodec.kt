package com.gigasoft.net

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

class NetPacketCodec {
    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val responseWriter = mapper.writerFor(SessionResponsePacket::class.java)

    fun tryDecodeRequest(line: String): SessionRequestPacket? {
        val first = firstNonWhitespaceChar(line) ?: return null
        if (first != '{') return null
        return runCatching { decodeRequestStreaming(line) }.getOrNull()
    }

    fun encodeResponse(packet: SessionResponsePacket): String {
        return responseWriter.writeValueAsString(packet)
    }

    fun encodeResponseParts(
        requestId: String?,
        success: Boolean,
        code: String,
        message: String,
        payload: Map<String, String>
    ): String {
        val sb = StringBuilder(192)
        sb.append('{')
        sb.append("\"protocol\":\"gigasoft-standalone-net\",")
        sb.append("\"version\":1,")
        sb.append("\"requestId\":")
        if (requestId == null) {
            sb.append("null")
        } else {
            appendJsonString(sb, requestId)
        }
        sb.append(",\"success\":").append(if (success) "true" else "false")
        sb.append(",\"code\":")
        appendJsonString(sb, code)
        sb.append(",\"message\":")
        appendJsonString(sb, message)
        sb.append(",\"payload\":{")
        var first = true
        for ((k, v) in payload) {
            if (!first) sb.append(',')
            first = false
            appendJsonString(sb, k)
            sb.append(':')
            appendJsonString(sb, v)
        }
        sb.append("}}")
        return sb.toString()
    }

    private fun firstNonWhitespaceChar(input: String): Char? {
        for (c in input) {
            if (!c.isWhitespace()) return c
        }
        return null
    }

    private fun decodeRequestStreaming(input: String): SessionRequestPacket {
        var protocol = "gigasoft-standalone-net"
        var version = 1
        var requestId: String? = null
        var action: String? = null
        var payload: Map<String, String> = emptyMap()

        mapper.factory.createParser(input).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                error("Invalid request packet")
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                val fieldName = parser.currentName() ?: break
                val valueToken = parser.nextToken()
                when (fieldName) {
                    "protocol" -> if (valueToken == JsonToken.VALUE_STRING) {
                        protocol = parser.text
                    } else {
                        parser.skipChildren()
                    }
                    "version" -> version = parser.valueAsInt
                    "requestId" -> requestId = if (valueToken == JsonToken.VALUE_NULL) null else parser.valueAsString
                    "action" -> action = parser.valueAsString
                    "payload" -> payload = parsePayload(parser, valueToken)
                    else -> parser.skipChildren()
                }
            }
        }

        val safeAction = action?.takeIf { it.isNotBlank() } ?: error("Missing action")
        return SessionRequestPacket(
            protocol = protocol,
            version = version,
            requestId = requestId,
            action = safeAction,
            payload = payload
        )
    }

    private fun parsePayload(
        parser: com.fasterxml.jackson.core.JsonParser,
        startToken: JsonToken
    ): Map<String, String> {
        if (startToken != JsonToken.START_OBJECT) {
            parser.skipChildren()
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>(8)
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            val key = parser.currentName() ?: continue
            val valueToken = parser.nextToken()
            val value = if (valueToken == JsonToken.VALUE_NULL) "" else parser.valueAsString ?: ""
            out[key] = value
        }
        return if (out.isEmpty()) emptyMap() else out
    }

    private fun appendJsonString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch < ' ') {
                        val hex = ch.code.toString(16).padStart(4, '0')
                        sb.append("\\u").append(hex)
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }
}
