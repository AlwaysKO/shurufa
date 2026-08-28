package com.yuyan.imemodule.view

import android.content.Context
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.adapter.CandidatesMenuAdapter
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.SkbFun
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.service.ImeService
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CandidatesBarTest {
    private lateinit var context: Context
    private lateinit var service: ImeService
    private var databaseSnapshot: List<SkbFun> = emptyList()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        ThemeManager.init(context.resources.configuration)
        YuyanEmojiCompat.init(context)
        EnvironmentSingleton.instance.initData(context)
        val dao = DataBaseKT.instance.skbFunDao()
        databaseSnapshot = dao.getAllMenu() + dao.getALlBarMenu()
        dao.deleteAll()
        dao.insertAll(
            listOf(
                SkbFun(SkbMenuMode.ClipBoard.name, isKeep = 1, position = 0),
            ),
        )
        DecodingInfo.candidatesLiveData.value = emptyList()
        service = Robolectric.buildService(ImeService::class.java).create().get()
    }

    @After
    fun tearDown() {
        service.onDestroy()
        DataBaseKT.instance.skbFunDao().run {
            deleteAll()
            insertAll(databaseSnapshot)
        }
        DecodingInfo.candidatesLiveData.value = emptyList()
    }

    @Test
    fun `空候选生产栏固定左右并完整消费含占位的工具模型`() {
        val inputView = service.onCreateInputView() as InputView
        val bar = inputView.mSkbCandidatesBarView

        bar.showCandidates()

        val adapter = bar.privateField<CandidatesMenuAdapter>("mCandidatesMenuAdapter")
        assertEquals(
            listOf(
                SkbMenuMode.Emojicon,
                SkbMenuMode.QuickKeyboard,
                SkbMenuMode.ClipBoard,
                null,
                SkbMenuMode.AiDoutu,
            ),
            adapter.items.map { it?.skbMenuMode },
        )
        val left = bar.privateField<View>("mIvMenuSetting")
        val right = bar.privateField<View>("mMenuRightArrowBtn")
        assertTrue(left.isClickable)
        assertTrue(right.isClickable)
        assertTrue(left.minimumWidth >= dp(44))
        assertTrue(right.minimumWidth >= dp(44))
        assertFalse(left.contentDescription.isNullOrBlank())
        assertFalse(right.contentDescription.isNullOrBlank())
        assertNotNull(left.background)
        assertNotNull(right.background)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> CandidatesBar.privateField(name: String): T =
        CandidatesBar::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(this) as T
        }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
