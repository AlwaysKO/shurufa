package com.yuyan.imemodule.data.collect

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import androidx.preference.PreferenceManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CollectionConsentTest {
 @Test fun `旧安装与新安装均须明确确认授权`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>(); val prefs=PreferenceManager.getDefaultSharedPreferences(ctx); prefs.edit().clear().commit()
  assertFalse(CollectionConsent.enabled(ctx)); CollectionConsent.setEnabled(ctx,true); assertTrue(CollectionConsent.enabled(ctx)); CollectionConsent.setEnabled(ctx,false); assertFalse(CollectionConsent.enabled(ctx))
 }
 @Test fun `未知输入框密码框与验证码提示均不采集`() {
  assertFalse(CollectionConsent.allowsEditor(null))
  assertFalse(CollectionConsent.allowsEditor(EditorInfo().apply{inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD}))
  assertFalse(CollectionConsent.allowsEditor(EditorInfo().apply{inputType=InputType.TYPE_CLASS_NUMBER;hintText="短信验证码"}))
  assertFalse(CollectionConsent.allowsEditor(EditorInfo().apply{inputType=InputType.TYPE_CLASS_NUMBER}))
  assertTrue(CollectionConsent.allowsEditor(EditorInfo().apply{inputType=InputType.TYPE_CLASS_TEXT;hintText="消息"}))
 }
 @Test fun `认证文本和独立短数字不入采集队列`() {
  assertFalse(CollectionConsent.allowsText("您的验证码为 123456"))
  assertFalse(CollectionConsent.allowsText("123456"))
  assertFalse(CollectionConsent.allowsText("1"))
  assertFalse(CollectionConsent.allowsText("password: hunter2"))
  assertTrue(CollectionConsent.allowsText("今天一起吃饭"))
 }
}
