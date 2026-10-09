package com.yuyan.redpacket.silent;
import org.junit.Test;
import static org.junit.Assert.*;
public class SilentPolicyTest {
 @Test public void onlyExactWechatPackageIsAccepted() {
  assertTrue(SilentPolicy.wechat("com.tencent.mm/.ui.LauncherUI"));
  assertFalse(SilentPolicy.wechat("com.tencent.mm.fake/Main"));
  assertFalse(SilentPolicy.wechat(null));
 }
 @Test public void unknownMainAndMainWechatBlock() {
  assertTrue(new Focus(0,"home/Main",null,0,-1,true).mainSafe(0));
  assertFalse(new Focus(0,"com.tencent.mm/Main",null,0,-1,true).mainSafe(0));
  assertFalse(new Focus(7,"home/Main",null,0,-1,true).mainSafe(0));
  assertFalse(new Focus(0,null,null,0,-1,true).mainSafe(0));
 }
 @Test public void secondaryRequiresOnWechatAndSelectedUser() {
  assertTrue(new Focus(0,"home/Main","com.tencent.mm/Main",0,128,true).secondarySafe(128));
  assertFalse(new Focus(0,"home/Main","com.tencent.mm/Main",0,0,true).secondarySafe(128));
  assertFalse(new Focus(0,"home/Main","other/Main",0,128,true).secondarySafe(128));
  assertFalse(new Focus(0,"home/Main","com.tencent.mm/Main",0,128,false).secondarySafe(128));
 }
 @Test public void frameIsSingleUseLatestFreshAndBounded() {
  FrameGate gate=new FrameGate();
  long first=gate.capture(10,480,800);
  assertFalse(gate.consume(first,-1,0,10));
  assertTrue(gate.consume(first,479,799,1010));
  assertFalse(gate.consume(first,0,0,1010));
  long second=gate.capture(2000,480,800);
  assertFalse(gate.consume(first,0,0,2000));
  assertFalse(gate.consume(second,0,0,3001));
  long third=gate.capture(4000,480,800);
  gate.invalidate(); assertFalse(gate.consume(third,0,0,4000));
 }
 @Test public void usersAreActualExactInstalledPackageResults() {
  assertArrayEquals(new int[]{0,100,128}, InstalledUsers.parse("UserInfo{0:Owner:13} running\nUserInfo{100:Private:0}\nUserInfo{128:Clone:0}"));
  assertTrue(InstalledUsers.hasWechat("package:com.tencent.mm\n"));
  assertFalse(InstalledUsers.hasWechat("package:com.tencent.mm.clone\n"));
 }
 @Test public void pendingIntentRequiresActivityExactCreatorAndSelectedUser() {
  assertTrue(SilentPolicy.pending(true,"com.tencent.mm",128*100000+10042,128));
  assertFalse(SilentPolicy.pending(false,"com.tencent.mm",10042,0));
  assertFalse(SilentPolicy.pending(true,"com.tencent.mm.fake",10042,0));
  assertFalse(SilentPolicy.pending(true,"com.tencent.mm",10042,128));
  assertFalse(SilentPolicy.pending(true,"com.tencent.mm",-1,0));
 }
}
