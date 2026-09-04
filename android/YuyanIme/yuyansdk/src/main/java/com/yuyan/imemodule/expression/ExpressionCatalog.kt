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
    fun recommend(query: String, limit: Int = 20): List<ExpressionAsset> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty() || limit <= 0) return emptyList()
        val indexed = document.templates
            .mapIndexed { index, asset -> RankedAsset(asset, index) }
        val prebuilt = indexed.filter { ranked ->
            ranked.asset.type == "prebuilt" &&
                ranked.asset.embeddedText?.let(::normalize) == normalizedQuery
        }
        if (prebuilt.isNotEmpty()) return rank(prebuilt, limit)
        return indexed
            .filter { it.asset.type == "synthesis-template" }
            .map { ranked ->
                ScoredAsset(
                    ranked = ranked,
                    relevance = relevance(ranked.asset, normalizedQuery),
                    queryOrder = stableQueryOrder(normalizedQuery, ranked.asset.id),
                )
            }
            .sortedWith(
                compareByDescending<ScoredAsset> { it.relevance }
                    .thenByDescending { it.ranked.asset.heat }
                    .thenBy { it.queryOrder }
                    .thenBy { it.ranked.index },
            )
            .take(limit)
            .map { it.ranked.asset }
    }

    fun search(query: String): List<ExpressionAsset> = recommend(query)

    private fun rank(assets: List<RankedAsset>, limit: Int): List<ExpressionAsset> =
        assets
            .sortedWith(
                compareByDescending<RankedAsset> { it.asset.heat }
                    .thenBy { it.index },
            )
            .take(limit)
            .map { it.asset }

    fun findCombination(firstId: String, secondId: String): EmojiCombination? =
        document.emojiCombinations.firstOrNull { it.key == "${firstId}__${secondId}" }

    fun merge(remote: ExpressionCatalogDocument): ExpressionCatalog = ExpressionCatalog(
        ExpressionCatalogDocument(
            version = remote.version,
            templates = mergeBy(document.templates, remote.templates) { it.id },
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

        private fun normalize(value: String): String = value
            .trim()
            .lowercase()
            .filterNot { it.isWhitespace() || isPunctuationOrSymbol(it) }

        private fun isPunctuationOrSymbol(value: Char): Boolean = when (Character.getType(value)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt(),
            -> true
            else -> false
        }

        private fun relevance(asset: ExpressionAsset, query: String): Double =
            asset.keywords.maxOfOrNull { keywordValue ->
                val keyword = normalize(keywordValue)
                when {
                    keyword.isEmpty() -> 0.0
                    keyword == query -> 1_000.0
                    keyword.contains(query) || query.contains(keyword) ->
                        700.0 + minOf(keyword.length, query.length)
                    else -> {
                        val queryChars = query.toSet()
                        val boundaryBonus = if (query.lastOrNull()?.let(keyword::endsWith) == true) 10.0 else 0.0
                        keyword.toSet().count(queryChars::contains) * 100.0 /
                            queryChars.size.coerceAtLeast(1) + boundaryBonus
                    }
                }
            } ?: 0.0

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
