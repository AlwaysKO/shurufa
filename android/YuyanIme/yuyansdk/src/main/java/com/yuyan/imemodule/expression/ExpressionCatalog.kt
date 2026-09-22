package com.yuyan.imemodule.expression

import android.content.Context
import com.yuyan.imemodule.expression.model.EmojiCombination
import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import java.io.InputStream
import kotlinx.serialization.json.Json

class ExpressionCatalog(
    val document: ExpressionCatalogDocument,
) {
    /** 自动推荐只接受完整配置说法；不能由图片旧关键词复活删除的说法。 */
    fun recommend(query: String, limit: Int = 20): List<ExpressionAsset> {
        val normalized = ExpressionQueryMatching.normalizeAutomatic(query)
        if (normalized.isEmpty() || limit <= 0) return emptyList()
        configuredRecommendation(normalized, limit)?.let { return it }
        // 只有没有组元数据的旧 APK/旧服务端目录才允许内置精确别名兼容。
        val aliases = ExpressionQueryMatching.groups.firstOrNull { normalized in it }.orEmpty() + normalized
        return document.templates.filter { asset ->
            asset.id !in document.retiredTemplateIds && asset.type == "prebuilt" &&
                (asset.keywords + listOfNotNull(asset.embeddedText)).any {
                    ExpressionQueryMatching.normalizeAutomatic(it) in aliases
                }
        }.sortedWith(compareByDescending<ExpressionAsset> { it.sourceType == "owner-upload" }
            .thenByDescending { it.format == "gif" }.thenByDescending { it.heat }).take(limit)
    }

    private fun configuredRecommendation(query: String, limit: Int): List<ExpressionAsset>? {
        val groups = document.recommendationGroups ?: return null
        val assets = document.templates.filterNot { it.id in document.retiredTemplateIds }.associateBy { it.id }
        return groups.filter { group -> group.aliases.any { ExpressionQueryMatching.normalizeAutomatic(it) == query } }
            .flatMap { it.assetIds }.distinct().mapNotNull(assets::get).take(limit)
    }

    /** 用户主动搜索保留宽松召回；精确命中组时仍以后台顺序为准。 */
    fun search(query: String, limit: Int = 20): List<ExpressionAsset> {
        val exact = ExpressionQueryMatching.normalizeAutomatic(query)
        val groups = document.recommendationGroups
        if (groups != null && groups.any { group -> group.aliases.any { ExpressionQueryMatching.normalizeAutomatic(it) == exact } }) {
            return configuredRecommendation(exact, limit.coerceAtLeast(0)).orEmpty()
        }
        val normalizedQuery = ExpressionQueryMatching.normalize(query)
        if (normalizedQuery.isEmpty() || limit <= 0) return emptyList()
        val indexed = document.templates
            .filterNot { it.id in document.retiredTemplateIds }
            .mapIndexed { index, asset -> RankedAsset(asset, index) }
        val prebuilt = indexed.filter { ranked ->
            ranked.asset.type == "prebuilt" && (
                ranked.asset.embeddedText?.let(ExpressionQueryMatching::normalize) == normalizedQuery ||
                    (ranked.asset.sourceType == "owner-upload" && ranked.asset.keywords.any {
                        ExpressionQueryMatching.normalize(it) == normalizedQuery
                    })
                )
        }
        if (prebuilt.isNotEmpty()) return rank(prebuilt, limit)
        val related = indexed.filter { it.asset.type == "prebuilt" }
            .map { ranked -> ScoredAsset(ranked,
                ExpressionQueryMatching.score(normalizedQuery, ranked.asset.keywords + listOfNotNull(ranked.asset.embeddedText)), 0L) }
            .filter { it.relevance > 0 }
            .sortedWith(compareByDescending<ScoredAsset> { it.relevance }
                .thenByDescending { it.ranked.asset.format == "gif" }
                .thenByDescending { it.ranked.asset.heat }
                .thenBy { it.ranked.index })
        if (related.isNotEmpty()) return related.take(limit).map { it.ranked.asset }

        if (ExpressionSynthesisIntent.matches(normalizedQuery)) {
            val pool = synthesisTemplates(normalizedQuery)
            if (pool.isNotEmpty()) return pool.take(limit)
        }

        return indexed
            .filter { it.asset.type == "synthesis-template" }
            .map { ranked ->
                ScoredAsset(
                    ranked = ranked,
                    relevance = ExpressionQueryMatching.score(normalizedQuery, ranked.asset.keywords),
                    queryOrder = stableQueryOrder(normalizedQuery, ranked.asset.id),
                )
            }
            .filter { it.relevance > 0 }
            .sortedWith(
                compareByDescending<ScoredAsset> { it.relevance }
                    .thenByDescending { it.ranked.asset.heat }
                    .thenBy { it.queryOrder }
                    .thenBy { it.ranked.index },
            )
            .take(limit)
            .map { it.ranked.asset }
    }

    /** 手动 DIY 展示全部无字可编辑 GIF；相关项前置，不用查询词过滤掉其他情绪。 */
    fun synthesisTemplates(query: String): List<ExpressionAsset> {
        val text = ExpressionQueryMatching.normalize(query)
        if (text.isEmpty()) return emptyList()
        return document.templates.filter {
            it.id !in document.retiredTemplateIds && it.type == "synthesis-template" && it.format == "gif" && it.embeddedText.isNullOrBlank() &&
                it.textSafeArea != null && it.layout != null
        }.sortedByDescending { ExpressionQueryMatching.score(text, it.keywords) }
    }


    private fun rank(assets: List<RankedAsset>, limit: Int): List<ExpressionAsset> =
        assets
            .sortedWith(
                compareByDescending<RankedAsset> { it.asset.format == "gif" }
                    .thenByDescending { it.asset.heat }
                    .thenBy { it.index },
            )
            .take(limit)
            .map { it.asset }

    fun findCombination(firstId: String, secondId: String): EmojiCombination? =
        document.emojiCombinations.firstOrNull { it.key == "${firstId}__${secondId}" }

    fun merge(remote: ExpressionCatalogDocument): ExpressionCatalog = if (remote.complete) {
        ExpressionCatalog(remote.copy(templates = remote.templates.filterNot { it.id in remote.retiredTemplateIds }))
    } else ExpressionCatalog(
        ExpressionCatalogDocument(
            version = remote.version,
            recommendationGroups = remote.recommendationGroups ?: document.recommendationGroups,
            templates = mergeBy(document.templates, remote.templates) { it.id }
                .filterNot { it.id in document.retiredTemplateIds || it.id in remote.retiredTemplateIds },
            retiredTemplateIds = (document.retiredTemplateIds + remote.retiredTemplateIds).distinct(),
            emojiBases = mergeBy(document.emojiBases, remote.emojiBases) { it.id },
            emojiCombinations = mergeBy(
                document.emojiCombinations,
                remote.emojiCombinations,
            ) { it.key },
        ),
    )

    private data class RankedAsset(
        val asset: ExpressionAsset,
        val index: Int,
    )

    private data class ScoredAsset(
        val ranked: RankedAsset,
        val relevance: Double,
        val queryOrder: Long,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(value: String): ExpressionCatalog =
            ExpressionCatalog(json.decodeFromString<ExpressionCatalogDocument>(value))

        fun fromInputStream(input: InputStream): ExpressionCatalog =
            input.bufferedReader().use { fromJson(it.readText()) }

        fun fromAssets(context: Context): ExpressionCatalog =
            context.assets.open("expression/catalog.json").use(::fromInputStream)

        /** 与服务端 31 倍哈希一致，并转成无符号排序值。 */
        private fun stableQueryOrder(query: String, id: String): Long =
            ((query.hashCode() xor id.hashCode()) * 0x45d9f3b).toLong() and 0xffffffffL

        private fun <T> mergeBy(
            local: List<T>,
            remote: List<T>,
            key: (T) -> String,
        ): List<T> {
            val result = local.toMutableList()
            val indexes = result.mapIndexed { index, item -> key(item) to index }.toMap().toMutableMap()
            for (item in remote) {
                val itemKey = key(item)
                val index = indexes[itemKey]
                if (index == null) {
                    indexes[itemKey] = result.size
                    result += item
                } else {
                    result[index] = item
                }
            }
            return result
        }
    }
}
