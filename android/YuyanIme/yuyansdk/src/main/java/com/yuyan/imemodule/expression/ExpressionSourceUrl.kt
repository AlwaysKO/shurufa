package com.yuyan.imemodule.expression

internal fun resolveExpressionRemoteSource(baseUrl: String, source: String): String =
    if (source.expressionSourceScheme() in SUPPORTED_SOURCE_SCHEMES) {
        source
    } else {
        "${baseUrl.trimEnd('/')}/${source.trimStart('/')}"
    }

internal fun isLocalGifExpressionSource(source: String): Boolean =
    source.expressionSourceScheme() in LOCAL_SOURCE_SCHEMES &&
        source.substringBefore('#').substringBefore('?').endsWith(".gif", ignoreCase = true)

private fun String.expressionSourceScheme(): String =
    substringBefore("://", missingDelimiterValue = "").lowercase()

private val LOCAL_SOURCE_SCHEMES = setOf("file", "content")
private val SUPPORTED_SOURCE_SCHEMES = setOf("http", "https", "file", "content")
