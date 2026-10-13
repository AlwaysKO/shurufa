package com.yuyan.redpacket.silent;
import org.junit.Test;
import static org.junit.Assert.*;
public class WindowObservationTransactionTest {
 static class Backend implements WindowObservationTransaction.Backend {
  boolean pre=true,post=true,mode=true,changeDisplay,throwPost;
  int checks,width=1200,height=2664,display=71,user=128;long elapsed=6000,uptime=20;
  public boolean safe() throws Exception {
   checks++;if(checks==1)return pre;if(throwPost)throw new Exception("fixed_test_failure");
   if(changeDisplay)display++;return post;
  }
  public boolean notificationMode(){return mode;}
  public int displayId(){return display;}public int userId(){return user;}
  public int width(){return width;}public int height(){return height;}
  public long elapsed(){return elapsed;}public long uptime(){return uptime;}
 }
 @Test public void metadataTicketHasOriginalGeometryAndSingleUseOneSecondExpiry() throws Exception {
  Backend backend=new Backend();FrameGate gate=new FrameGate();
  WindowObservationTransaction.Ticket ticket=new WindowObservationTransaction(gate,backend).observe();
  assertNotNull(ticket);assertEquals(71,ticket.displayId);assertEquals(128,ticket.userId);
  assertEquals(1200,ticket.width);assertEquals(2664,ticket.height);assertEquals(20,ticket.capturedAtUptime);
  assertEquals(2,backend.checks);assertFalse(gate.consume(ticket.frameId,1200,1,6001));
  assertTrue(gate.consume(ticket.frameId,1199,2663,7000));
  assertFalse(gate.consume(ticket.frameId,1,1,7000));
 }
 @Test public void newObservationSupersedesBitmapOrPreviousObservationTicket() throws Exception {
  Backend backend=new Backend();FrameGate gate=new FrameGate();long bitmap=gate.capture(1,480,800);
  WindowObservationTransaction transaction=new WindowObservationTransaction(gate,backend);
  WindowObservationTransaction.Ticket first=transaction.observe();assertNotNull(first);
  WindowObservationTransaction.Ticket second=transaction.observe();assertNotNull(second);
  assertFalse(gate.consume(bitmap,1,1,6000));assertFalse(gate.consume(first.frameId,1,1,6000));
  assertTrue(gate.consume(second.frameId,1,1,6000));
 }
 @Test public void privateModeUnsafeFocusAndGeometryChangesInvalidatePreviousTicket() throws Exception {
  for(int rejection=0;rejection<7;rejection++){
   Backend backend=new Backend();FrameGate gate=new FrameGate();gate.capture(1,1200,2664);
   if(rejection==0)backend.mode=false;if(rejection==1)backend.pre=false;if(rejection==2)backend.post=false;
   if(rejection==3)backend.changeDisplay=true;if(rejection==4)backend.width=0;
   if(rejection==5)backend.user=-1;if(rejection==6)backend.height=8193;
   assertNull(new WindowObservationTransaction(gate,backend).observe());assertEquals(0,gate.id());
  }
 }
 @Test public void slowFocusChecksPrecedeFreshTicketIssuanceAndDoNotExtendItsTtl() throws Exception {
  Backend backend=new Backend(){
   @Override public boolean safe() throws Exception {
    boolean safe=super.safe();elapsed+=800;uptime+=800;return safe;
   }
  };
  FrameGate gate=new FrameGate();
  WindowObservationTransaction.Ticket ticket=new WindowObservationTransaction(gate,backend).observe();
  assertNotNull(ticket);assertEquals(1620,ticket.capturedAtUptime);
  assertTrue(gate.consume(ticket.frameId,1,1,8600));
  backend.checks=0;
  ticket=new WindowObservationTransaction(gate,backend).observe();assertNotNull(ticket);
  assertFalse(gate.consume(ticket.frameId,1,1,backend.elapsed+1001));assertEquals(0,gate.id());
 }
 @Test public void expiredTicketIsInvalidatedAndCannotReviveWhenClockMovesBack(){
  FrameGate gate=new FrameGate();long id=gate.capture(6000,1200,2664);
  assertFalse(gate.consume(id,1,1,7001));assertEquals(0,gate.id());
  assertFalse(gate.consume(id,1,1,7000));
 }
 @Test public void guardExceptionLeavesNoTicket() throws Exception {
  Backend backend=new Backend();backend.throwPost=true;FrameGate gate=new FrameGate();gate.capture(1,1200,2664);
  try{new WindowObservationTransaction(gate,backend).observe();fail("exception must propagate");}
  catch(Exception expected){assertEquals(0,gate.id());}
 }
 @Test public void interruptedObservationCannotIssueTicket() throws Exception {
  Backend backend=new Backend();FrameGate gate=new FrameGate();
  Thread.currentThread().interrupt();
  try{new WindowObservationTransaction(gate,backend).observe();fail("interruption must propagate");}
  catch(InterruptedException expected){assertEquals(0,gate.id());}
  finally{Thread.interrupted();}
 }
}
