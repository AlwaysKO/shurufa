package com.yuyan.imemodule.expression.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    val embeddedText: String? = null,
    val textSafeArea: ExpressionTextSafeArea? = null,
    val layout: ExpressionTextLayout? = null,
    val heat: Long = 0,
    val url: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @Transient val resolvedPreviewUrl: String? = null,
    /** 推荐副本只在预览阶段展示原件；点击模板仍贴完整原句，不写入 catalog。 */
    @Transient val originalForRecommendation: Boolean = false,
    /** 仅决定原件分发；remote 元数据和首帧缩略图仍保留在 APK。 */
    val distribution: String = "bundled",
    val sourceType: String? = null,
    /** 完整目录的预览只读校验后本地文件，不能交由Glide绕过SHA缓存下载。 */
    @Transient val localPreviewOnly: Boolean = false,
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
data class ExpressionRecommendationGroup(
    val keyword: String,
    val aliases: List<String> = emptyList(),
    val assetIds: List<String> = emptyList(),
)

@Serializable
data class ExpressionCatalogDocument(
    val version: String,
    val templates: List<ExpressionAsset>,
    val emojiBases: List<EmojiBase>,
    val emojiCombinations: List<EmojiCombination>,
    val retiredTemplateIds: List<String> = emptyList(),
    val complete: Boolean = false,
    /** null/缺省仅兼容旧目录；空数组是权威清空，不能回退关键词。 */
    val recommendationGroups: List<ExpressionRecommendationGroup>? = null,
    /** 后台底图顺序；缺省目录沿用旧相关性排序。 */
    val synthesisOrder: List<String>? = null,
)
