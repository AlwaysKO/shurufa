package com.yuyan.imemodule.data.completion

import android.content.Context
import java.util.zip.GZIPInputStream

internal object OfflineAssociationCompletion {
    // 不使用 .gz 后缀：AAPT 会自动解压 .gz 并在 APK 中移除该后缀。
    private const val ASSET_PATH = "completion/offline_associations.tsv.gzip"

    @Volatile
    private var index: OfflineAssociationIndex? = null

    fun init(context: Context) {
        if (index != null) return
        try {
            val loaded = context.assets.open(ASSET_PATH).use { input ->
                GZIPInputStream(input).reader(Charsets.UTF_8).use(OfflineAssociationIndex::parse)
            }
            index = loaded
        } catch (_: Exception) {
            // 离线联想加载失败只降级到 Rime 原有联想，不影响输入。
        }
    }

    fun query(text: String): OfflineAssociationQuery =
        index?.query(text) ?: OfflineAssociationQuery.EMPTY
}
