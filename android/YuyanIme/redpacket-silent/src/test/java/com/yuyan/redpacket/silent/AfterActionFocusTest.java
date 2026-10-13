package com.yuyan.redpacket.silent;
import org.junit.Test;
import static org.junit.Assert.*;
public class AfterActionFocusTest {
 @Test public void onlyMissingWindowOrSameUserWechatOffCanWait(){
  assertTrue(waiting(new Focus(0,"home/Main",null,0,-1,true),100));
  assertTrue(waiting(new Focus(0,"home/Main","com.tencent.mm/Main",0,128,false),100));
  assertFalse(waiting(new Focus(0,"home/Main","com.tencent.mm/Main",0,128,true),100));
  assertFalse(waiting(new Focus(0,"home/Main",null,0,-1,true),1500));
  assertFalse(waiting(new Focus(0,"home/Main","android/Permission",0,128,true),100));
  assertFalse(waiting(new Focus(0,"home/Main","com.tencent.mm/Main",0,0,true),100));
  assertFalse(waiting(new Focus(0,"home/Main","com.tencent.mm/Main",0,-1,true),100));
 }
 @Test public void mainSafeUserSwitchesCanWaitButUnsafeTopNeverWait(){
  assertFalse(waiting(new Focus(7,"home/Main",null,0,-1,true),100));
  assertTrue(waiting(new Focus(0,"other/Main",null,0,-1,true),100));
  assertTrue(waiting(new Focus(0,"home/Main",null,1,-1,true),100));
  assertFalse(waiting(new Focus(0,"com.tencent.mm/Main",null,128,-1,true),100));
 }
 private boolean waiting(Focus f,long now){return StartupFocus.shouldWaitAfterAction(f,128,"home/Main",0,now,1500);}
 static class Backend extends SilentSessionTest.Backend {
  Focus after=new Focus(0,"home/Main","com.tencent.mm/Main",0,128,true);
  int waits;
  @Override public Focus awaitAfterAction(int display,int user,String main,int mainUser){waits++;return after;}
 }
 @Test public void actionGuardUsesBoundedWaitButNormalVerifyStillRejectsTransientFocus(){
  Backend b=new Backend();SilentSession s=new SilentSession(b);s.start(2000,128,0);
  b.current=new Focus(0,"home/Main",null,0,-1,true);
  assertTrue(s.afterAction(10));assertEquals(1,b.waits);assertTrue(s.running());
  assertFalse(s.verify(11));assertEquals("SECONDARY_UNVERIFIED",s.state());
 }
 @Test public void failedActionWaitOrWrongSecondaryClosesSession(){
  for(int reason=0;reason<2;reason++){
   Backend b=new Backend();SilentSession s=new SilentSession(b);s.start(2000,128,0);
   b.after=reason==0?new Focus(0,"home/Main",null,0,-1,true):
    new Focus(0,"home/Main","com.tencent.mm/Main",0,0,true);
   assertFalse(s.afterAction(10));assertFalse(s.running());assertEquals(1,b.closed);
  }
 }
 @Test public void userMaySwitchMainAppsDuringActionWait(){
  Backend b=new Backend();SilentSession s=new SilentSession(b);s.start(2000,128,0);
  b.after=new Focus(0,"game/Main","com.tencent.mm/Main",0,128,true);
  assertTrue(s.afterAction(10));assertTrue(s.running());
 }
 @Test public void expiredSessionNeverStartsActionWait(){
  Backend b=new Backend();SilentSession s=new SilentSession(b);s.start(2000,128,0);
  assertFalse(s.afterAction(300000));assertEquals("EXPIRED",s.state());assertEquals(0,b.waits);
 }
}
