package com.yuyan.imemodule.data.usage

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35])
class UsageReportingPolicyTest {
    private val home = setOf("com.example.home")
    private fun row(duration: Long, pkg: String = "app") = UsageRecord("id", "usage", pkg, null, 10000, 10000 + duration, "switch")

    @Test fun `three seconds is excluded but three seconds plus one millisecond is kept`() {
        for (duration in listOf(1L, 2999L, 3000L)) assertFalse(shouldReportUsage(row(duration), home))
        assertTrue(shouldReportUsage(row(3001), home))
        assertFalse(shouldReportUsage(row(100000, home.single()), home))
        assertTrue(shouldReportUsage(row(1).copy(kind="gap", packageName=null), home))
    }

    @Test fun `returning home still closes the previous app without including time on desktop`() {
        val result = UsageSessionEngine.reduce(UsageState(cursor=10000), listOf(
            UsageEvent(10000, "resume", "app"), UsageEvent(20000, "resume", home.single()),
            UsageEvent(60000, "resume", "app"), UsageEvent(70000, "lock"),
        ), 71000)
        val reported = result.records.filter { shouldReportUsage(it, home) }
        assertEquals(listOf("app", "app"), reported.map { it.packageName })
        assertEquals(listOf(10000L, 10000L), reported.map { it.endMs-it.startMs })
    }

    @Test fun `desktop discovery uses home handlers including third party launchers`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = home.single(); name = "HomeActivity" }
        })
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            priority = -1000
            activityInfo = ActivityInfo().apply { packageName = "com.android.settings"; name = "com.android.settings.FallbackHome" }
        })
        val packages = usageHomePackages(app)
        assertTrue(packages.contains(home.single()))
        assertFalse(packages.contains("com.android.settings"))
    }
}
