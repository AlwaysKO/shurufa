package com.yuyan.imemodule.view

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.R
import com.yuyan.imemodule.adapter.CandidatesMenuAdapter
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemePreset
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
        ThemeManager.setNormalModeTheme(ThemePreset.MaterialLight)
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
    fun `数据库工具栏按用户position并以主键稳定决胜`() {
        val dao = DataBaseKT.instance.skbFunDao()
        dao.deleteAll()
        dao.insertAll(
            listOf(
                SkbFun(SkbMenuMode.Phrases.name, isKeep = 1, position = 30),
                SkbFun(SkbMenuMode.Voice.name, isKeep = 1, position = 5),
                SkbFun(SkbMenuMode.TextEdit.name, isKeep = 1, position = 10),
                SkbFun(SkbMenuMode.ClipBoard.name, isKeep = 1, position = 10),
            ),
        )

        assertEquals(
            listOf(
                SkbMenuMode.Voice.name,
                SkbMenuMode.ClipBoard.name,
                SkbMenuMode.TextEdit.name,
                SkbMenuMode.Phrases.name,
            ),
            dao.getALlBarMenu().map(SkbFun::name),
        )
    }


    @Test
    fun `真实InputView浅深主题切换同步固定按钮工具项和AI面板`() {
        val inputView = service.onCreateInputView() as InputView
        val bar = inputView.mSkbCandidatesBarView
        bar.showCandidates()
        val adapter = bar.privateField<CandidatesMenuAdapter>("mCandidatesMenuAdapter")
        val aiPosition = adapter.items.indexOfFirst { it?.skbMenuMode == SkbMenuMode.AiDoutu }
        val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(aiPosition))
        adapter.onBindViewHolder(holder, aiPosition)
        val left = bar.privateField<View>("mIvMenuSetting")
        val right = bar.privateField<View>("mMenuRightArrowBtn")
        val panelTool = inputView.findViewById<View>(R.id.expression_tool_row)
        val panelEnable = inputView.findViewById<TextView>(R.id.expression_enable)
        val lightLeftPress = pressedColor(left)
        val lightRightPress = pressedColor(right)
        val lightItemPress = pressedColor(holder.itemView)
        val lightPanel = (panelTool.background as ColorDrawable).color
        val lightPanelText = panelEnable.currentTextColor

        ThemeManager.setNormalModeTheme(ThemePreset.MaterialDark)
        inputView.updateTheme()
        adapter.onBindViewHolder(holder, aiPosition)

        assertTrue(lightLeftPress != pressedColor(left))
        assertTrue(lightRightPress != pressedColor(right))
        assertTrue(lightItemPress != pressedColor(holder.itemView))
        assertTrue(lightPanel != (panelTool.background as ColorDrawable).color)
        assertTrue(lightPanelText != panelEnable.currentTextColor)
        assertEquals(ThemeManager.activeTheme.keyTextColor, holder.entranceIconImageView.imageTintList?.defaultColor)
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

    private fun pressedColor(view: View): Int {
        val background = view.background as StateListDrawable
        background.state = intArrayOf(android.R.attr.state_pressed)
        return (background.current as GradientDrawable).color?.defaultColor
            ?: error("pressed color missing")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> CandidatesBar.privateField(name: String): T =
        CandidatesBar::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(this) as T
        }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
