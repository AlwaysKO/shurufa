package com.yuyan.imemodule.utils

import android.view.KeyEvent
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.entity.keyboard.SoftKeyToggle
import com.yuyan.imemodule.entity.keyboard.SoftKeyboard
import com.yuyan.imemodule.entity.keyboard.ToggleState
import com.yuyan.imemodule.entity.keyboard.KeyType
import com.yuyan.imemodule.entity.keyboard.LongPressAction
import com.yuyan.imemodule.keyboard.KeyPreset
import com.yuyan.imemodule.keyboard.KeyboardData
import com.yuyan.imemodule.keyboard.KeyGeometry
import com.yuyan.imemodule.keyboard.SogouT9Layout
import com.yuyan.imemodule.keyboard.SogouQwertyLayout
import com.yuyan.imemodule.keyboard.SogouAuxiliaryLayouts
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.DoublePinyinSchemaMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.keyboard.doubleAbcMnemonicPreset
import com.yuyan.imemodule.keyboard.doubleFlyMnemonicPreset
import com.yuyan.imemodule.keyboard.doubleMSMnemonicPreset
import com.yuyan.imemodule.keyboard.doubleNaturalMnemonicPreset
import com.yuyan.imemodule.keyboard.doubleSogouMnemonicPreset
import com.yuyan.imemodule.keyboard.doubleZiguangMnemonicPreset
import com.yuyan.imemodule.keyboard.lx17MnemonicPreset
import com.yuyan.imemodule.prefs.behavior.SkbStyleMode
import java.util.LinkedList

/**
 * 键盘加载类  包括中文9键  中文26键 英文26键
 */
class KeyboardLoaderUtil private constructor() {
    private var rimeValue: String? = null
    private var mSkbValue: Int = 0
    private var numberLine: Boolean = false
    private var skbStyleMode: SkbStyleMode = SkbStyleMode.Yuyan
    fun clearKeyboardMap() {
        mSoftKeyboardMap.clear()
    }

    private fun loadBaseSkb(skbValue: Int): SoftKeyboard {
        skbStyleMode = ThemeManager.prefs.skbStyleMode.getValue()
        mSkbValue = skbValue
        // shift键状态
        // 直输状态
        val shiftToggleStates = LinkedList<ToggleState>()
        shiftToggleStates.add(ToggleState(0))
        shiftToggleStates.add(ToggleState(1))
        shiftToggleStates.add(ToggleState(2))
        // 拼写模式
        shiftToggleStates.add(ToggleState(3))
        shiftToggleStates.add(ToggleState(4))
        shiftToggleStates.add(ToggleState(5))

        val softKeyboard: SoftKeyboard?
        numberLine = AppPrefs.getInstance().keyboardSetting.abcNumberLine.getValue()
        val rows: MutableList<List<SoftKey>> = LinkedList()
        if (numberLine) {
            val qwertyKeys = createNumberLineKeys(arrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0))
            rows.add(qwertyKeys.asList())
        }
        when(skbValue){
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN -> {  // 1000  拼音全键
                rimeValue = AppPrefs.getInstance().internal.pinyinModeRime.getValue()
                var keyBeans = mutableListOf<SoftKey>()
                val keys = when (rimeValue) {
                    CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.mspy,
                    CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.sogou,
                    CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.ziguang -> arrayListOf(
                        arrayOf(45, 51, 33, 46, 48, 53, 49, 37, 43, 44),
                        arrayOf(29, 47, 32, 34, 35, 36, 38, 39, 40),
                        arrayOf(74, 54, 52, 31, 50, 30, 42, 41, KeyEvent.KEYCODE_DEL),)
                    else -> {
                        KeyboardData.layoutQwertyCn[skbStyleMode]!!
                    }
                }
                val geometryRows = SogouQwertyLayout.rowGeometry
                var qwertyKeys = createQwertyPYKeys(keys[0])
                applyVisualGeometry(qwertyKeys.asList(), geometryRows[0].keys)
                keyBeans.addAll(qwertyKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                qwertyKeys = createQwertyPYKeys(keys[1])
                applyVisualGeometry(qwertyKeys.asList(), geometryRows[1].keys)
                keyBeans.addAll(qwertyKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                qwertyKeys = createQwertyPYKeys(keys[2])
                if(skbStyleMode == SkbStyleMode.Google) {
                    val softKeyToggle = createKeyToggle(KeyEvent.KEYCODE_SHIFT_LEFT)
                    softKeyToggle.setToggleStates(shiftToggleStates)
                    qwertyKeys[0] = softKeyToggle
                }
                applyVisualGeometry(qwertyKeys.asList(), geometryRows[2].keys)
                keyBeans.addAll(qwertyKeys)
                rows.add(keyBeans)
                keyBeans = lastRows(skbValue)
                applyVisualGeometry(keyBeans, geometryRows[3].keys)
                rows.add(keyBeans)
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN -> {  // 2000  T9键键
                var keyBeans: MutableList<SoftKey> = LinkedList()
                val keys = SogouT9Layout.keyRows
                val geometryRows = SogouT9Layout.rowGeometry
                var t9Key = createT9Keys(keys[0].toTypedArray())
                applyCandidateHolderGeometry(t9Key.first())
                applyVisualGeometry(t9Key.drop(1), geometryRows[0].keys)
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                t9Key = createT9Keys(keys[1].toTypedArray())
                applyVisualGeometry(t9Key.asList(), geometryRows[1].keys)
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                t9Key = createT9Keys(keys[2].toTypedArray())
                t9Key.last().apply {
                    stateId = 7
                }
                applyVisualGeometry(t9Key.asList(), geometryRows[2].keys)
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = sogouT9LastRow()
                applyVisualGeometry(keyBeans, geometryRows[3].keys)
                rows.add(keyBeans)

            }
            InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING -> {// 3000 手写键盘
                rows.addAll(createAuxiliaryRows(SogouAuxiliaryLayouts.handwriting, KeyPreset.t9PYKeyPreset, voiceSpace = true))
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC -> {// 4000 英文全键
                var keyBeans: MutableList<SoftKey> = LinkedList()
                val keys = KeyboardData.layoutQwertyEn[skbStyleMode]!!
                val geometryRows = SogouQwertyLayout.rowGeometry
                var qwertyKeys = createQwertyKeys(keys[0])
                applyVisualGeometry(qwertyKeys.asList(), geometryRows[0].keys)
                keyBeans.addAll(qwertyKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                qwertyKeys = createQwertyKeys(keys[1])
                applyVisualGeometry(qwertyKeys.asList(), geometryRows[1].keys)
                keyBeans.addAll(qwertyKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                val softKeyToggle = createKeyToggle(KeyEvent.KEYCODE_SHIFT_LEFT)
                softKeyToggle.setToggleStates(shiftToggleStates)
                keyBeans.add(softKeyToggle)
                keyBeans.addAll(createQwertyKeys(keys[2]))
                applyVisualGeometry(keyBeans, geometryRows[2].keys)
                rows.add(keyBeans)
                keyBeans = lastRows(skbValue)
                keyBeans[keyBeans.size -2].stateId = 1
                applyVisualGeometry(keyBeans, geometryRows[3].keys)
                rows.add(keyBeans)
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER -> {  // 5000 数字键盘
                rows.addAll(createAuxiliaryRows(SogouAuxiliaryLayouts.number, KeyPreset.t9NumberKeyPreset))
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_LX17 -> {     // 6000 乱序17键盘
                var keyBeans: MutableList<SoftKey> = LinkedList()
                if(AppPrefs.getInstance().keyboardSetting.lx17WithLeftPrefix.getValue()) {
                    val keys = KeyboardData.layoutLX17CnWithLeftPrefix[skbStyleMode]!!
                    var lX17Keys = createLX17Keys(keys[0])
                    lX17Keys.first().apply {
                        widthF = 0.1457f
                        heightF = 0.75f
                    }
                    lX17Keys[1].mLeftF = 0.1457f
                    keyBeans.addAll(lX17Keys)
                    rows.add(keyBeans)
                    keyBeans = LinkedList()
                    lX17Keys = createLX17Keys(keys[1])
                    lX17Keys.first().mLeftF = 0.1457f
                    keyBeans.addAll(lX17Keys)
                    rows.add(keyBeans)
                    keyBeans = LinkedList()
                    lX17Keys = createLX17Keys(keys[2])
                    lX17Keys.first().mLeftF = 0.1457f
                    keyBeans.addAll(lX17Keys)
                    rows.add(keyBeans)
                    keyBeans = lastRows(skbValue)
                    rows.add(keyBeans)
                } else {
                    val keys =  KeyboardData.layoutLX17Cn[skbStyleMode]!!
                    var lX17Keys = createLX17Keys(keys[0], 0.165f)
                    keyBeans.addAll(lX17Keys)
                    rows.add(keyBeans)
                    keyBeans = LinkedList()
                    lX17Keys = createLX17Keys(keys[1], 0.165f)
                    keyBeans.addAll(lX17Keys)
                    rows.add(keyBeans)
                    keyBeans = LinkedList()
                    lX17Keys = createLX17Keys(keys[2], 0.165f)
                    keyBeans.addAll(lX17Keys)
                    rows.add(keyBeans)
                    keyBeans = lastRows(skbValue)
                    rows.add(keyBeans)
                }
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_STROKE -> {  // 7000  笔画键盘
                rows.addAll(createAuxiliaryRows(SogouAuxiliaryLayouts.stroke, KeyPreset.strokeKeyPreset, voiceSpace = true))
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_TEXTEDIT -> {     // 8000 文本编辑键盘
                rows.addAll(createAuxiliaryRows(SogouAuxiliaryLayouts.textEdit, KeyPreset.textEditKeyPreset))
            }
        }
        val numberLineSkb = when(skbStyleMode){
            SkbStyleMode.Yuyan -> numberLine
            SkbStyleMode.Samsung -> numberLine
            SkbStyleMode.Google -> numberLine
        }
        softKeyboard = getSoftKeyboard(rows, numberLineSkb)
        mSoftKeyboardMap[skbValue] = softKeyboard
        return softKeyboard
    }

    // 键盘最后一行（各键盘统一，数字键盘稍微不同）
    private fun lastRows(skbValue: Int): MutableList<SoftKey> {
        if (
            skbValue == InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN ||
            skbValue == InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC
        ) {
            return sogouQwertyLastRow(skbValue)
        }
        val softKeyToggle = createEnterToggle()
        softKeyToggle.widthF = 0.18f
        val keyBeans = mutableListOf<SoftKey>()
        val t9Keys = when(skbValue){
            InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN, InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING, InputModeSwitcher.MASK_SKB_LAYOUT_STROKE ->{
                if(skbStyleMode == SkbStyleMode.Google){
                    createT9Keys(arrayOf(InputModeSwitcher.USER_KEYCODE_NUMBER, InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI, InputModeSwitcher.USER_KEYCODE_LANG,
                        KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD))
                } else if(skbStyleMode == SkbStyleMode.Samsung){
                    createT9Keys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_LANG,
                        KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_NUMBER))
                } else {
                    createT9Keys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_NUMBER,
                            KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LANG))
                }
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC -> {
                if(skbStyleMode == SkbStyleMode.Google){
                    createQwertyKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_NUMBER, InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI, InputModeSwitcher.USER_KEYCODE_LANG,
                        KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD))
                } else if (skbStyleMode == SkbStyleMode.Samsung) {
                    createQwertyKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_LANG,
                        InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD, InputModeSwitcher.USER_KEYCODE_NUMBER))
                } else {
                    createQwertyKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_NUMBER,
                            InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD, KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LANG))
                }
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_LX17 -> {
                if(skbStyleMode == SkbStyleMode.Google){
                    createT9Keys(arrayOf(InputModeSwitcher.USER_KEYCODE_NUMBER, InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI, InputModeSwitcher.USER_KEYCODE_LANG,
                        KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD))
                } else if (skbStyleMode == SkbStyleMode.Samsung) {
                    createLX17Keys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_LANG,
                        InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_NUMBER))
                } else {
                    createLX17Keys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_NUMBER,
                            InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LANG))
                }
            }
            else -> { //0x1000 InputModeSwitcherManager.MASK_SKB_LAYOUT_QWERTY_PINYIN
                if(skbStyleMode == SkbStyleMode.Google){
                    createQwertyPYKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_NUMBER, InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI, InputModeSwitcher.USER_KEYCODE_LANG,
                        KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD))
                } else if (skbStyleMode == SkbStyleMode.Samsung) {
                    createQwertyPYKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_LANG,
                        InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD, InputModeSwitcher.USER_KEYCODE_NUMBER))
                } else {
                    createQwertyPYKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_NUMBER,
                            InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LANG))
                }
            }
        }
        if (skbStyleMode == SkbStyleMode.Google) {
            t9Keys[2].stateId = 2
            when (t9Keys.size) {
                6 -> {
                    t9Keys[0].widthF = 0.185f;t9Keys[1].widthF = 0.1f
                    t9Keys[2].widthF = 0.1f;t9Keys[3].widthF = 0.23f
                    t9Keys[4].widthF = 0.1f;t9Keys[5].widthF = 0.1f
                    softKeyToggle.widthF = 0.185f
                }
                5 -> {
                    t9Keys[0].widthF = 0.185f;t9Keys[1].widthF = 0.1f
                    t9Keys[2].widthF = 0.1f;t9Keys[3].widthF = 0.33f
                    t9Keys[4].widthF = 0.1f;softKeyToggle.widthF = 0.185f
                }
                else -> {
                    softKeyToggle.widthF = 0.18f
                    t9Keys[0].widthF = 0.18f;t9Keys[1].widthF = 0.21f
                    t9Keys[2].widthF = 0.21f;t9Keys[3].widthF = 0.21f
                }
            }
        } else if (skbStyleMode == SkbStyleMode.Samsung) {
            if(skbValue == 0x4000)t9Keys[1].stateId = 1
            when (t9Keys.size) {
                6 -> {
                    t9Keys[0].widthF = 0.1457f;t9Keys[1].widthF = 0.1457f
                    t9Keys[2].widthF = 0.099f;t9Keys[3].widthF = 0.2f
                    t9Keys[4].widthF = 0.099f;t9Keys[5].widthF = 0.1457f
                    softKeyToggle.widthF = 0.1457f
                }
                5 -> {
                    t9Keys[0].widthF = 0.16f;t9Keys[1].widthF = 0.099f
                    t9Keys[2].widthF = 0.099f;t9Keys[3].widthF = 0.38f
                    t9Keys[4].widthF = 0.099f;softKeyToggle.widthF = 0.16f
                }
                else -> {
                    softKeyToggle.widthF = 0.18f
                    t9Keys[0].widthF = 0.18f;t9Keys[1].widthF = 0.21f
                    t9Keys[2].widthF = 0.21f;t9Keys[3].widthF = 0.21f
                }
            }
        } else {
            if (t9Keys.size == 5) {
                softKeyToggle.widthF = 0.147f
                t9Keys[0].widthF = 0.147f;t9Keys[1].widthF = 0.099f
                t9Keys[2].widthF = 0.099f;t9Keys[3].widthF = 0.396f
                t9Keys[4].widthF = 0.099f
            } else {
                t9Keys[0].widthF = 0.18f;t9Keys[1].widthF = 0.147f
                t9Keys[2].widthF = 0.336f;t9Keys[3].widthF = 0.147f
            }
        }
        keyBeans.addAll(t9Keys)
        keyBeans.add(softKeyToggle)
        return keyBeans
    }

    private fun sogouT9LastRow(): MutableList<SoftKey> {
        val keys = createT9Keys(SogouT9Layout.bottomRowCodes.dropLast(1).toTypedArray())
        keys[2] = SogouT9Layout.createVoiceSpaceKey()
        keys.first().label = "符"
        return keys.toMutableList().apply {
            add(SogouT9Layout.createEnterKey())
            SogouT9Layout.applyBottomRowGeometry(this)
        }
    }

    private fun sogouQwertyLastRow(skbValue: Int): MutableList<SoftKey> {
        val codes = SogouQwertyLayout.bottomRowCodes.dropLast(1).toTypedArray()
        val keys = if (skbValue == InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN) {
            createQwertyPYKeys(codes)
        } else {
            createQwertyKeys(codes)
        }
        keys[3] = SogouQwertyLayout.createVoiceSpaceKey()
        keys.forEachIndexed { index, key ->
            key.widthF = SogouQwertyLayout.bottomRowWidths[index]
            key.heightF = SogouQwertyLayout.ROW_HEIGHT
        }
        return keys.toMutableList().apply {
            add(createEnterToggle().apply {
                widthF = SogouQwertyLayout.bottomRowWidths.last()
                heightF = SogouQwertyLayout.ROW_HEIGHT
            })
        }
    }

    private fun createEnterToggle() = createKeyToggle(KeyEvent.KEYCODE_ENTER).apply {
        keyType = KeyType.AccentKey
        stateId = 0
        setToggleStates(
            listOf(
                ToggleState("去往", 2),
                ToggleState("搜索", 3),
                ToggleState("发送", 4),
                ToggleState("下一个", 5),
                ToggleState("完成", 6),
                ToggleState("上一个", 7),
            )
        )
    }

    fun changeSKBNumberRow() {
        for (skbValue in mSoftKeyboardMap.keys) {
            loadBaseSkb(skbValue)
        }
    }

    fun getSoftKeyboard(skbValue: Int): SoftKeyboard {
        var softKeyboard = mSoftKeyboardMap[skbValue]
        if (softKeyboard == null) {
            softKeyboard = loadBaseSkb(skbValue)
        }
        return softKeyboard
    }

    private fun applyVisualGeometry(keys: List<SoftKey>, geometry: List<KeyGeometry>) {
        require(keys.size == geometry.size)
        keys.zip(geometry).forEach { (key, bounds) ->
            key.setKeyDimensions(bounds.left, bounds.top)
            key.widthF = bounds.width
            key.heightF = bounds.height
        }
    }

    private fun applyCandidateHolderGeometry(key: SoftKey) {
        val bounds = SogouT9Layout.candidateCodeView
        key.setKeyDimensions(bounds.x, bounds.y)
        key.widthF = bounds.width
        key.heightF = bounds.height
    }

    private fun createAuxiliaryRows(
        spec: SogouAuxiliaryLayouts.LayoutSpec,
        preset: Map<Int, Array<String>>,
        voiceSpace: Boolean = false,
    ): List<List<SoftKey>> = spec.codeRows.zip(spec.visualRows).map { (codes, geometry) ->
        codes.map { code ->
            val labels = preset[code]
            val key = if (code == KeyEvent.KEYCODE_ENTER) {
                createEnterToggle()
            } else {
                SoftKey(
                    code = code,
                    label = labels?.getOrNull(0).orEmpty(),
                    labelSmall = labels?.getOrNull(1).orEmpty(),
                )
            }
            key.apply {
                if (voiceSpace && code == KeyEvent.KEYCODE_SPACE) longPressAction = LongPressAction.Voice
                applyThemeRole()
            }
        }.also { applyVisualGeometry(it, geometry) }
    }

    private fun SoftKey.applyThemeRole() {
        keyType = when (code) {
            KeyEvent.KEYCODE_ENTER -> KeyType.AccentKey
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_CLEAR,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            InputModeSwitcher.USER_KEYCODE_SYMBOL,
            InputModeSwitcher.USER_KEYCODE_NUMBER,
            InputModeSwitcher.USER_KEYCODE_RETURN,
            InputModeSwitcher.USER_KEYCODE_LANG -> KeyType.Function
            else -> KeyType.Normal
        }
    }

    /** 生成键盘布局，主要用于计算键盘边界 */
    private fun getSoftKeyboard(rows: List<List<SoftKey>>, isNumberRow: Boolean): SoftKeyboard {
        val isSogouT9 = mSkbValue == InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN
        val isSogouQwerty = mSkbValue == InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN ||
            mSkbValue == InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC
        var lastKeyBottom = 0f
        var lastKeyRight: Float
        var lastKeyTop: Float
        rows.forEachIndexed { rowIndex, rowBean ->
            lastKeyTop = lastKeyBottom  // 新行top为上一行bottom
            lastKeyRight = 0.005f // 新行x从0.005开始
            for (keyBean in rowBean) {
                var keyXPos = keyBean.mLeftF
                var keyYPos = keyBean.mTopF
                val keyWidth = keyBean.widthF
                val keyHeight = keyBean.heightF
                if(keyXPos == -1f || keyYPos == -1f || isNumberRow) {
                    if (keyXPos == -1f) keyXPos = lastKeyRight
                    if (keyYPos == -1f) keyYPos = lastKeyTop
                    if (isNumberRow) {
                        val exactLayoutOffset = if ((isSogouT9 || isSogouQwerty) && rowIndex > 0) {
                            NUMBER_ROW_HEIGHT
                        } else {
                            0f
                        }
                        keyBean.setKeyDimensions(
                            keyXPos,
                            (keyYPos + exactLayoutOffset) / NUMBER_ROW_SCALE,
                            keyHeight / NUMBER_ROW_SCALE,
                        )
                    } else {
                        keyBean.setKeyDimensions(keyXPos, keyYPos)
                    }
                }
                keyBean.setSkbCoreSize(EnvironmentSingleton.instance.skbWidth, EnvironmentSingleton.instance.skbHeight)
                lastKeyRight = keyXPos + keyWidth
                lastKeyTop = keyYPos
                lastKeyBottom = keyYPos + keyHeight
            }
        }
        val environment = EnvironmentSingleton.instance
        return SoftKeyboard(
            rows,
            keyXMarginScale = when {
                isSogouT9 -> SogouT9Layout.X_MARGIN_SCALE
                isSogouQwerty -> SogouQwertyLayout.X_MARGIN_SCALE
                else -> 1f
            },
            keyYMarginScale = when {
                isSogouT9 -> SogouT9Layout.Y_MARGIN_SCALE
                isSogouQwerty -> SogouQwertyLayout.Y_MARGIN_SCALE
                else -> 1f
            },
            normalizedHitBounds = createSogouHitBounds(rows, isNumberRow),
            keyboardWidth = environment.skbWidth,
            keyboardHeight = environment.skbHeight,
        )
    }

    private fun createSogouHitBounds(
        rows: List<List<SoftKey>>,
        isNumberRow: Boolean,
    ): List<SoftKeyboard.NormalizedHitBounds> {
        val rowOffset = if (isNumberRow) 1 else 0
        fun transformY(value: Float): Float = if (isNumberRow) {
            (NUMBER_ROW_HEIGHT + value) / NUMBER_ROW_SCALE
        } else {
            value
        }
        fun bounds(key: SoftKey, geometry: KeyGeometry) = SoftKeyboard.NormalizedHitBounds(
            key = key,
            left = geometry.touchLeft,
            top = transformY(geometry.touchTop),
            right = geometry.touchRight,
            bottom = transformY(geometry.touchBottom),
        )
        fun continuousRowBounds(
            keys: List<SoftKey>,
            top: Float,
            bottom: Float,
        ): List<SoftKeyboard.NormalizedHitBounds> {
            val boundaries = keys.zipWithNext { left, right ->
                ((left.mLeftF + left.widthF) + right.mLeftF) / 2f
            }
            return keys.mapIndexed { index, key ->
                SoftKeyboard.NormalizedHitBounds(
                    key = key,
                    left = boundaries.getOrElse(index - 1) { 0f },
                    top = top,
                    right = boundaries.getOrElse(index) { 1f },
                    bottom = bottom,
                )
            }
        }
        val numberRowBounds = if (isNumberRow) {
            continuousRowBounds(rows.first(), top = 0f, bottom = transformY(0f))
        } else {
            emptyList()
        }
        fun auxiliaryBounds(spec: SogouAuxiliaryLayouts.LayoutSpec) = spec.visualRows.flatMapIndexed { rowIndex, geometryRow ->
            rows[rowIndex + rowOffset].zip(geometryRow, ::bounds)
        }

        return when (mSkbValue) {
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN,
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC -> {
                val geometryRows = SogouQwertyLayout.rowGeometry
                numberRowBounds + geometryRows.flatMapIndexed { rowIndex, geometryRow ->
                    rows[rowIndex + rowOffset].zip(geometryRow.keys, ::bounds)
                }
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN -> {
                val geometryRows = SogouT9Layout.rowGeometry
                val holder = rows[rowOffset].first()
                val holderBounds = SoftKeyboard.NormalizedHitBounds(
                    key = holder,
                    left = 0f,
                    top = transformY(0f),
                    right = SogouT9Layout.candidateCodeView.right,
                    bottom = transformY(geometryRows[2].touchBottom),
                )
                buildList {
                    addAll(numberRowBounds)
                    add(holderBounds)
                    geometryRows.forEachIndexed { rowIndex, geometryRow ->
                        val keys = rows[rowIndex + rowOffset].let { row ->
                            if (rowIndex == 0) row.drop(1) else row
                        }
                        addAll(keys.zip(geometryRow.keys, ::bounds))
                    }
                }
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_STROKE -> numberRowBounds + auxiliaryBounds(SogouAuxiliaryLayouts.stroke)
            InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER -> numberRowBounds + auxiliaryBounds(SogouAuxiliaryLayouts.number)
            InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING -> numberRowBounds + auxiliaryBounds(SogouAuxiliaryLayouts.handwriting)
            InputModeSwitcher.MASK_SKB_LAYOUT_TEXTEDIT -> numberRowBounds + auxiliaryBounds(SogouAuxiliaryLayouts.textEdit)
            else -> emptyList()
        }
    }

    private fun createT9Keys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset =  if(mSkbValue == 0x7000) KeyPreset.strokeKeyPreset else KeyPreset.t9PYKeyPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1)?: "").apply {
                widthF = 0.21f
                applyThemeRole()
                if (code == KeyEvent.KEYCODE_0 && mSkbValue != InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER) {
                    keyType = KeyType.Function
                }
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createQwertyPYKeys(codes: Array<Int>): Array<SoftKey> {
        val keyMnemonicPreset = when (rimeValue) {
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.flypy -> doubleFlyMnemonicPreset
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.abc -> doubleAbcMnemonicPreset
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.mspy -> doubleMSMnemonicPreset
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.natural -> doubleNaturalMnemonicPreset
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.sogou -> doubleSogouMnemonicPreset
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.ziguang -> doubleZiguangMnemonicPreset
            else -> emptyMap()
        }
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = if(numberLine)KeyPreset.qwertyPYKeyPreset else KeyPreset.qwertyPYKeyNumberPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "", keyMnemonic = keyMnemonicPreset[code] ?: "").apply {
                widthF = SogouQwertyLayout.LETTER_WIDTH
                heightF = SogouQwertyLayout.ROW_HEIGHT
                applyThemeRole()
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createQwertyKeys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = if(numberLine)KeyPreset.qwertyKeyPreset else KeyPreset.qwertyKeyNumberPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "", keyMnemonic = labels?.getOrNull(2) ?: "").apply {
                widthF = SogouQwertyLayout.LETTER_WIDTH
                heightF = SogouQwertyLayout.ROW_HEIGHT
                applyThemeRole()
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createNumberLineKeys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        for(code in codes) {
            val softKey = SoftKey(label = code.toString()).apply {
                widthF = 0.099f
                heightF = 0.2f
            }
            softKeys.add(softKey)
        }
        return softKeys.toTypedArray()
    }

    private fun createLX17Keys(codes: Array<Int>, width: Float = 0.142f): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = if(numberLine)KeyPreset.lx17PYKeyPreset else KeyPreset.lx17PYKeyNumberPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "", keyMnemonic= lx17MnemonicPreset[code] ?: "").apply {
                widthF = width
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createKeyToggle(code: Int): SoftKeyToggle {
        return SoftKeyToggle(code).apply { applyThemeRole() }
    }

    companion object {
        private const val NUMBER_ROW_HEIGHT = 0.2f
        private const val NUMBER_ROW_SCALE = 1.2f
        private var mInstance: KeyboardLoaderUtil? = null
        private val mSoftKeyboardMap = HashMap<Int, SoftKeyboard?>() //缓存所有可用键盘
        @JvmStatic
        val instance: KeyboardLoaderUtil
            get() {
                if (null == mInstance) mInstance = KeyboardLoaderUtil()
                return mInstance!!
            }
    }
}
