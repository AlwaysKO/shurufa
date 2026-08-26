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
        return rank(if (prebuilt.isNotEmpty()) prebuilt else indexed.filter {
            it.asset.type == "synthesis-template"
        }, limit)
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

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(value: String): ExpressionCatalog =
            ExpressionCatalog(json.decodeFromString<ExpressionCatalogDocument>(value))

        fun fromInputStream(input: InputStream): ExpressionCatalog =
            input.bufferedReader().use { fromJson(it.readText()) }

        fun fromAssets(context: Context): ExpressionCatalog =
            context.assets.open("expression/catalog.json").use(::fromInputStream)

        private fun normalize(value: String): String = value.trim().lowercase()

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
