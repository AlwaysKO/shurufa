package com.yuyan.redpacket.silent;

/** A single serialized capture: metadata belongs to the copied pixels, not a later status RPC. */
final class FrameCaptureTransaction<T> {
 interface Backend<T> {
  boolean safe() throws Exception;
  T capture() throws Exception;
  int width(T image);
  int height(T image);
  int displayId();
  long capturedAtElapsed();
  long capturedAtUptime();
  void release(T image);
 }
 static final class Frame<T> {
  final T image;
  final long frameId,capturedAtUptime;
  final int displayId;
  Frame(T image,long frameId,int displayId,long capturedAtUptime) {
   this.image=image;this.frameId=frameId;this.displayId=displayId;this.capturedAtUptime=capturedAtUptime;
  }
 }
 private final FrameGate frames;
 private final Backend<T> backend;
 FrameCaptureTransaction(FrameGate frames,Backend<T> backend){this.frames=frames;this.backend=backend;}
 Frame<T> capture() throws Exception {
  frames.invalidate();
  T image=null;
  boolean transferred=false;
  try {
   if(!backend.safe())return null;
   image=backend.capture();if(image==null)return null;
   int width=backend.width(image),height=backend.height(image),display=backend.displayId();
   long elapsed=backend.capturedAtElapsed(),uptime=backend.capturedAtUptime();
   if(!backend.safe())return null;
   if(Thread.currentThread().isInterrupted())throw new InterruptedException();
   Frame<T> result=new Frame<>(image,frames.capture(elapsed,width,height),display,uptime);
   transferred=true;return result;
  } finally {
   if(image!=null && !transferred){frames.invalidate();backend.release(image);}
  }
 }
}
