package com.yuyan.imemodule.data.collect

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerConfigTest {

    @Test
    fun `debug build uses configured local url when present`() {
        assertEquals(
            "http://192.168.1.20:3000",
            resolveServerBaseUrl(
                buildUrl = "http://127.0.0.1:3000",
                allowOverride = true,
                configuredUrl = "  http://192.168.1.20:3000/  ",
            ),
        )
    }

    @Test
    fun `debug build falls back to build url for blank setting`() {
        assertEquals(
            "http://127.0.0.1:3000",
            resolveServerBaseUrl(
                buildUrl = "http://127.0.0.1:3000/",
                allowOverride = true,
                configuredUrl = "   ",
            ),
        )
    }

    @Test
    fun `release build ignores configured local url`() {
        assertEquals(
            "https://myapi.dog8ball.com",
            resolveServerBaseUrl(
                buildUrl = "https://myapi.dog8ball.com",
                allowOverride = false,
                configuredUrl = "http://127.0.0.1:3000",
            ),
        )
    }
}
