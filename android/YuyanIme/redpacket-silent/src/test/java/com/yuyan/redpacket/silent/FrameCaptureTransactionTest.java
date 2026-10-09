package com.yuyan.redpacket.silent;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FrameCaptureTransactionTest {
 private static final class Fake implements FrameCaptureTransaction.Backend<Object> {
  final Object image=new Object();
  boolean pre=true,post=true,postThrows;
  int checks,captures,releases;
  public boolean safe() throws Exception {
   if(++checks==1)return pre;
   if(postThrows)throw new Exception("test");
   return post;
  }
  public Object capture(){captures++;return image;}
  public int width(Object image){return 480;}
  public int height(Object image){return 800;}
  public int displayId(){return 33;}
  public long capturedAtElapsed(){return 6_000;}
  public long capturedAtUptime(){return 20;}
  public void release(Object image){assertSame(this.image,image);releases++;}
 }
 @Test public void preGuardRejectsWithoutCopyingAndInvalidatesPreviousFrame() throws Exception {
  Fake backend=new Fake();backend.pre=false;
  FrameGate gate=new FrameGate();gate.capture(1,480,800);
  assertNull(new FrameCaptureTransaction<>(gate,backend).capture());
  assertEquals(0,backend.captures);assertEquals(0,gate.id());
 }
 @Test public void postGuardRejectsAndReleasesCopiedPixels() throws Exception {
  Fake backend=new Fake();backend.post=false;
  FrameGate gate=new FrameGate();
  assertNull(new FrameCaptureTransaction<>(gate,backend).capture());
  assertEquals(1,backend.captures);assertEquals(1,backend.releases);assertEquals(0,gate.id());
 }
 @Test public void postGuardExceptionReleasesCopiedPixels() {
  Fake backend=new Fake();backend.postThrows=true;
  FrameGate gate=new FrameGate();
  try {new FrameCaptureTransaction<>(gate,backend).capture();fail("guard exception must propagate");}
  catch(Exception expected){assertEquals(1,backend.releases);assertEquals(0,gate.id());}
 }
 @Test public void atomicallyReturnsPixelMetadataAndKeepsElapsedClockForTapExpiry() throws Exception {
  Fake backend=new Fake();FrameGate gate=new FrameGate();
  FrameCaptureTransaction.Frame<Object> frame=new FrameCaptureTransaction<>(gate,backend).capture();
  assertNotNull(frame);assertSame(backend.image,frame.image);
  assertEquals(33,frame.displayId);assertEquals(20,frame.capturedAtUptime);
  assertEquals(1,frame.frameId);assertEquals(frame.frameId,gate.id());
  assertEquals(2,backend.checks);assertEquals(0,backend.releases);
  assertFalse(gate.consume(frame.frameId,1,1,7_001));
  assertTrue(gate.consume(frame.frameId,1,1,7_000));
 }
}
