package com.yuyan.imemodule.expression.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExpressionTextSafeArea(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

@Serializable
data class ExpressionTextLayout(
    val minFontSize: Int,
    val maxFontSize: Int,
    val textColor: String,
    val strokeColor: String,
    val strokeWidth: Int,
    val alignment: String,
    val maxLines: Int,
)

@Serializable
data class ExpressionAsset(
    val id: String,
    val type: String,
    val format: String,
    val version: String,
    val fileName: String,
    val thumbnailFileName: String? = null,
    val sha256: String,
    val width: Int,
    val height: Int,
    val keywords: List<String> = emptyList(),
    val emotions: List<String> = emptyList(),
    val textSafeArea: ExpressionTextSafeArea? = null,
    val layout: ExpressionTextLayout? = null,
    val heat: Long = 0,
    val url: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
)

@Serializable
data class EmojiBase(
    val id: String,
    val name: String,
    val emotions: List<String> = emptyList(),
    val fileName: String,
    val sha256: String,
    val version: String,
    val width: Int,
    val height: Int,
    val sortOrder: Int,
    val url: String? = null,
)

@Serializable
data class EmojiCombination(
    val key: String,
    val firstId: String,
    val secondId: String,
    val fileName: String,
    val sha256: String,
    val version: String,
    val width: Int,
    val height: Int,
    val heat: Long = 0,
    val url: String? = null,
)

@Serializable
data class ExpressionCatalogDocument(
    val version: String,
    val templates: List<ExpressionAsset>,
    val emojiBases: List<EmojiBase>,
    val emojiCombinations: List<EmojiCombination>,
)
