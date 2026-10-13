package com.yuyan.redpacket.silent;
import org.junit.Test;
import static org.junit.Assert.*;
public class NotificationDisplayTest {
 @Test public void publicAddsOnlyPublicBitAndPreservesEveryIsolationFlag(){
  assertEquals(DisplayMode.ISOLATED_FLAGS,DisplayMode.flags(false));
  assertEquals(DisplayMode.ISOLATED_FLAGS|1,DisplayMode.flags(true));
  assertEquals(1,DisplayMode.flags(false)^DisplayMode.flags(true));
 }
 static class Backend extends SilentSessionTest.Backend {
  boolean notification;
  @Override public int open(boolean mode){notification=mode;return open();}
  @Override public void close(){super.close();current=new Focus(0,"home/Main",null,0,-1,true);}
 }
 @Test public void switchingModeClosesPrivateDisplayAndNeverReusesItsTicket(){
  Backend backend=new Backend();FrameGate gate=new FrameGate();SilentSession session=new SilentSession(backend,gate);
  session.start(2000,0,0);long old=gate.capture(10,1200,2664);
  session.start(2000,0,20,true);
  assertTrue(session.running());assertTrue(session.notificationMode());assertTrue(backend.notification);
  assertEquals(2,backend.opened);assertEquals(1,backend.closed);assertEquals(0,gate.id());
  assertFalse(gate.consume(old,1,1,20));
  session.start(2000,0,30);assertFalse(session.notificationMode());assertFalse(backend.notification);
  assertEquals(3,backend.opened);assertEquals(2,backend.closed);
 }
 @Test public void sameNotificationModeDoesNotRestartOrExtendSession(){
  Backend backend=new Backend();FrameGate gate=new FrameGate();SilentSession session=new SilentSession(backend,gate);
  session.start(2000,0,5,true);session.start(2000,0,200,true);
  assertEquals(1,backend.opened);assertTrue(session.notificationMode());
  assertFalse(session.verify(300005));assertEquals(1,backend.closed);
 }
 @Test public void stopAndFocusFailureInvalidateOutstandingTicket(){
  Backend backend=new Backend();FrameGate gate=new FrameGate();SilentSession session=new SilentSession(backend,gate);
  session.start(2000,0,0,true);long old=gate.capture(0,1200,2664);session.stop("STOPPED");
  assertEquals(0,gate.id());assertFalse(gate.consume(old,1,1,1));
  session.start(2000,0,2,true);gate.capture(2,1200,2664);
  backend.current=new Focus(7,"home/Main","com.tencent.mm/Main",0,0,true);
  assertFalse(session.verify(3));assertEquals(0,gate.id());
 }
 @Test public void transitionWindowsAndDisplayOffWaitButMainChangesStopImmediately(){
  Focus[] transitions={new Focus(0,"home/Main",null,0,-1,false),
   new Focus(0,"home/Main","android/StartingWindow",0,0,true),
   new Focus(0,"home/Main","com.tencent.mm/Main",0,128,true)};
  for(Focus f:transitions){assertTrue(StartupFocus.shouldWait(f,0,"home/Main",0,100,1500));assertFalse(StartupFocus.shouldWait(f,0,"home/Main",0,1500,1500));}
  assertFalse(StartupFocus.shouldWait(new Focus(0,"home/Main","com.tencent.mm/Main",0,0,true),0,"home/Main",0,100,1500));
  assertFalse(StartupFocus.shouldWait(new Focus(7,"home/Main",null,0,-1,true),0,"home/Main",0,100,1500));
  assertFalse(StartupFocus.shouldWait(new Focus(0,"other/Main",null,0,-1,true),0,"home/Main",0,100,1500));
  assertFalse(StartupFocus.shouldWait(new Focus(0,"home/Main",null,128,-1,true),0,"home/Main",0,100,1500));
 }
}
