package com.yuyan.redpacket.silent;
import org.junit.Test;
import static org.junit.Assert.*;
public class SilentSessionTest {
 static class Backend implements SilentSession.Backend {
  int opened,closed,launched,id=7; boolean throwOpen;
  Focus current=new Focus(0,"home/Main",null,0,-1,true);
  public int open() { opened++; if(throwOpen) throw new IllegalStateException(); return id; }
  public void launch(int display,int user) { launched++; current=new Focus(0,"home/Main","com.tencent.mm/Main",0,user,true); }
  public Focus focus(int display) { return current; }
  public Focus awaitFocus(int display,int user,String main,int mainUser) { return current; }
  public void close() { closed++; }
 }
 @Test public void nonShellAndUnknownMainDoNotOpen() {
  Backend b=new Backend(); SilentSession s=new SilentSession(b);
  s.start(0,128,0); assertEquals(0,b.opened);
  b.current=new Focus(-1,null,null,-1,-1,true); s.start(2000,128,0); assertEquals(0,b.opened);
 }
 @Test public void mainWechatIsUserTakeoverBeforeLaunch() {
  Backend b=new Backend(); b.current=new Focus(0,"com.tencent.mm/Main",null,0,-1,true);
  SilentSession s=new SilentSession(b); s.start(2000,0,0); assertEquals("MAIN_WECHAT_IN_USE",s.state()); assertEquals(0,b.opened);
 }
 @Test public void invalidDisplayNeverLaunchesAndPartialCreationCleans() {
  Backend b=new Backend(); b.id=0; SilentSession s=new SilentSession(b);s.start(2000,128,0);
  assertEquals(0,b.launched);assertEquals(1,b.closed);
  b.throwOpen=true;s.start(2000,128,0);assertEquals(2,b.closed);
 }
 @Test public void userMismatchAndMainWechatStopWithoutMainTaskOperation() {
  Backend b=new Backend();SilentSession s=new SilentSession(b);s.start(2000,128,0);assertTrue(s.running());
  b.current=new Focus(0,"home/Main","com.tencent.mm/Main",0,0,true);
  assertFalse(s.verify(1));assertEquals(1,b.closed);
  b.current=new Focus(0,"home/Main",null,0,-1,true);s.start(2000,128,100);
  b.current=new Focus(0,"com.tencent.mm/Main",null,128,-1,true);
  assertFalse(s.verify(101));assertEquals("MAIN_WECHAT_IN_USE",s.state());assertEquals(2,b.closed);
 }
 @Test public void originalDeadlineAndRepeatedStopAreStable() {
  Backend b=new Backend();SilentSession s=new SilentSession(b);s.start(2000,128,5);
  s.start(2000,128,200);assertEquals(1,b.opened);
  assertFalse(s.verify(300005));assertEquals("EXPIRED",s.state());
  s.stop("STOPPED");assertEquals(1,b.closed);
 }
 @Test public void otherKnownWechatUserCanRemainOnMainWhileTargetRunsOnSecondary() {
  Backend b=new Backend() {
   public void launch(int display,int user) {
    launched++;current=new Focus(0,"com.tencent.mm/.ui.LauncherUI","com.tencent.mm/Main",128,user,true);
   }
  };
  b.current=new Focus(0,"com.tencent.mm/.ui.LauncherUI",null,128,-1,true);
  SilentSession s=new SilentSession(b);s.start(2000,0,0);
  assertTrue(s.running());assertTrue(s.verify(100));assertTrue(s.mainSafe());assertEquals(0,b.closed);
 }
 @Test public void sameOrUnknownMainWechatUserStillBlocksBeforeOpening() {
  for(int mainUser:new int[]{0,-1}) {
   Backend b=new Backend();b.current=new Focus(0,"com.tencent.mm/Main",null,mainUser,-1,true);
   SilentSession s=new SilentSession(b);s.start(2000,0,0);
   assertEquals("MAIN_WECHAT_IN_USE",s.state());assertEquals(0,b.opened);
  }
 }
 @Test public void mainUserMustNotChangeDuringStartupEvenWhenComponentMatches() {
  Backend b=new Backend() {
   public void launch(int display,int user) {
    launched++;current=new Focus(0,"com.tencent.mm/.ui.LauncherUI","com.tencent.mm/Main",129,user,true);
   }
  };
  b.current=new Focus(0,"com.tencent.mm/.ui.LauncherUI",null,128,-1,true);
  SilentSession s=new SilentSession(b);s.start(2000,0,0);
  assertEquals("MAIN_FOCUS_CHANGED",s.state());assertEquals(1,b.opened);assertEquals(1,b.closed);
 }
 @Test public void runningOtherUserSessionStopsWhenMainWechatBecomesTargetOrUnknown() {
  for(int mainUser:new int[]{0,-1}) {
   Backend b=new Backend() {
    public void launch(int display,int user) {
     launched++;current=new Focus(0,"com.tencent.mm/.ui.LauncherUI","com.tencent.mm/Main",128,user,true);
    }
   };
   b.current=new Focus(0,"com.tencent.mm/.ui.LauncherUI",null,128,-1,true);
   SilentSession s=new SilentSession(b);s.start(2000,0,0);assertTrue(s.running());
   b.current=new Focus(0,"com.tencent.mm/.ui.LauncherUI","com.tencent.mm/Main",mainUser,0,true);
   assertFalse(s.verify(100));assertEquals("MAIN_WECHAT_IN_USE",s.state());
   assertEquals(1,b.closed);assertEquals(-1,s.display());
  }
 }
 @Test public void runningOtherUserSessionStopsWhenSecondaryStealsTopDisplay() {
  Backend b=new Backend() {
   public void launch(int display,int user) {
    launched++;current=new Focus(0,"com.tencent.mm/.ui.LauncherUI","com.tencent.mm/Main",128,user,true);
   }
  };
  b.current=new Focus(0,"com.tencent.mm/.ui.LauncherUI",null,128,-1,true);
  SilentSession s=new SilentSession(b);s.start(2000,0,0);assertTrue(s.running());
  b.current=new Focus(7,"com.tencent.mm/.ui.LauncherUI","com.tencent.mm/Main",128,0,true);
  assertFalse(s.verify(100));assertEquals("MAIN_FOCUS_LOST",s.state());
  assertEquals(1,b.closed);assertEquals(-1,s.display());
 }
}
