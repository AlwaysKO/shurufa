package com.yuyan.redpacket.silent;
/** Metadata-only observation; shares the single-use action gate with bitmap diagnostics. */
final class WindowObservationTransaction {
 interface Backend {
  boolean safe() throws Exception;
  boolean notificationMode();
  default Focus verifiedFocus(){return null;}
  int displayId(); int userId(); int width(); int height();
  long elapsed(); long uptime();
 }
 static final class Ticket {
  final long frameId,capturedAtUptime;
  final int displayId,userId,width,height;
  final String packetUi;
  Ticket(long frameId,long capturedAtUptime,int displayId,int userId,int width,int height){
   this(frameId,capturedAtUptime,displayId,userId,width,height,"NONE");
  }
  Ticket(long frameId,long capturedAtUptime,int displayId,int userId,int width,int height,String packetUi){
   this.packetUi=packetUi;
   this.frameId=frameId;this.capturedAtUptime=capturedAtUptime;this.displayId=displayId;
   this.userId=userId;this.width=width;this.height=height;
  }
 }
 private final FrameGate frames;
 private final Backend backend;
 WindowObservationTransaction(FrameGate frames,Backend backend){this.frames=frames;this.backend=backend;}
 Ticket observe() throws Exception {
  frames.invalidate();
  if(!backend.notificationMode() || !backend.safe())return null;
  int display=backend.displayId(),user=backend.userId(),width=backend.width(),height=backend.height();
  if(display<=0 || user<0 || width<=0 || height<=0 || width>4096 || height>8192
    || (long)width*height>8_388_608)return null;
  if(!backend.safe())return null;
  if(Thread.currentThread().isInterrupted())throw new InterruptedException();
  if(!backend.notificationMode() || display!=backend.displayId()
    || user!=backend.userId() || width!=backend.width() || height!=backend.height())return null;
  // No pixels/nodes precede this ticket: it authorizes a subsequent fresh native-node read.
  // Expensive focus checks complete before issuance, and all subsequent work uses its 1s TTL.
  String packetUi=PacketUiPolicy.kind(backend.verifiedFocus(),user);
  long elapsed=backend.elapsed(),uptime=backend.uptime();
  return new Ticket(frames.capture(elapsed,width,height),uptime,display,user,width,height,packetUi);
 }
}
