package com.yuyan.imemodule.data.collect

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppNameResolverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun install(pkg: String, label: String?) {
        val info = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg; applicationInfo = info
        })
    }

    @Test fun `读取系统中文和英文名称而不是包名后缀`() {
        install("org.example.chat", "  我的聊天  ")
        install("org.example.notes", "My Notes")
        val resolver = AppNameResolver()
        assertEquals("我的聊天", resolver.resolve(context, "org.example.chat"))
        assertEquals("My Notes", resolver.resolve(context, "org.example.notes"))
    }

    @Test fun `未安装无标签及空包名返回空并不阻塞采集`() {
        install("org.example.empty", null)
        val resolver = AppNameResolver()
        assertNull(resolver.resolve(context, null))
        assertNull(resolver.resolve(context, ""))
        assertNull(resolver.resolve(context, "org.example.missing"))
        assertNull(resolver.resolve(context, "org.example.empty"))
    }

    @Test fun `去掉控制字符且不采集超长应用名称`() {
        install("org.example.control", "测\u0000试\n应用")
        install("org.example.long", "甲".repeat(121))
        val resolver = AppNameResolver()
        assertEquals("测试应用", resolver.resolve(context, "org.example.control"))
        assertNull(resolver.resolve(context, "org.example.long"))
    }

    @Test fun `名称随持久队列及事件批次传输且旧事件仍可读取`() {
        val event = MobileEvent(id = UUID.randomUUID().toString(), deviceId = "device", eventType = "commit",
            packageName = "org.example.chat", appName = "我的聊天", text = "你好", occurredAt = "2026-09-16T12:00:00Z")
        val store = LocalInputStore(context, "app-names-${UUID.randomUUID()}.db")
        try {
            store.enqueue(event, listOf("https://test.invalid"))
            val saved = store.pending("https://test.invalid").single()
            assertEquals("我的聊天", saved.appName)
            val encoded = Json.encodeToString(EventBatch.serializer(), EventBatch("device", listOf(saved)))
            assertTrue(encoded.contains("\"app_name\":\"我的聊天\""))
            val old = """{"id":"old","device_id":"device","event_type":"commit","occurred_at":"2026-09-16T12:00:00Z"}"""
            assertNull(Json.decodeFromString(MobileEvent.serializer(), old).appName)
            assertEquals("我的聊天", Json.parseToJsonElement(Json.encodeToString(MobileEvent.serializer(), saved)).jsonObject["app_name"]!!.jsonPrimitive.content)
        } finally { store.close() }
    }
}
