package com.yuyan.imemodule.adapter

import androidx.recyclerview.widget.RecyclerView
import androidx.emoji2.text.EmojiCompat
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.inputmethod.core.CandidateListItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CandidatesBarPresentationTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        AppPrefs.init(context.getSharedPreferences("candidate-presentation-test", 0))
        ThemeManager.init(context.resources.configuration)
        EmojiCompat.init(object : EmojiCompat.Config(EmojiCompat.MetadataRepoLoader { }) {})
        YuyanEmojiCompat.init(context)
        DecodingInfo.candidatesLiveData.value = listOf(
            CandidateListItem("", "常用词"), CandidateListItem("", "常用"),
        )
    }

    @After fun teardown() {
        DecodingInfo.candidatesLiveData.value = emptyList()
    }

    @Test fun `默认首候选高亮且复用到第二项时恢复普通颜色`() {
        val adapter = CandidatesBarAdapter(context)
        val holder = adapter.onCreateViewHolder(RecyclerView(context).apply { layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context) }, 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(ThemeManager.activeTheme.accentKeyBackgroundColor, holder.textView.currentTextColor)
        adapter.onBindViewHolder(holder, 1)
        assertEquals(ThemeManager.activeTheme.keyTextColor, holder.textView.currentTextColor)
    }

    @Test fun `方向键激活第二候选后重置恢复首候选高亮`() {
        val adapter = CandidatesBarAdapter(context)
        val holder = adapter.onCreateViewHolder(RecyclerView(context).apply { layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context) }, 0)
        adapter.activeCandidates(2)
        adapter.onBindViewHolder(holder, 1)
        assertEquals(ThemeManager.activeTheme.accentKeyBackgroundColor, holder.textView.currentTextColor)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(ThemeManager.activeTheme.keyTextColor, holder.textView.currentTextColor)
        adapter.activeCandidates(0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(ThemeManager.activeTheme.accentKeyBackgroundColor, holder.textView.currentTextColor)
    }
}
