package com.yuyan.redpacket.silent;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Display;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
@android.annotation.TargetApi(35)
final class ShellDisplay implements SilentSession.Backend {
 static final int WIDTH=480,HEIGHT=800;
 private static final int FLAGS=(1<<3)|(1<<6)|(1<<8)|(1<<10)|(1<<11)|(1<<14)|(1<<16);
 private final Context context;
 private final Object imageLock=new Object();
 private VirtualDisplay display;
 private ImageReader reader;
 private HandlerThread images;
 private Image latest;
 private final RenderGate render=new RenderGate();
 private long imageTime,capturedAt,capturedAtUptime;
 private String failure="NONE";
 ShellDisplay(Context base) {context=new ContextWrapper(base) {
  @Override public String getPackageName(){return "com.android.shell";}
  @Override public String getOpPackageName(){return "com.android.shell";}
 };}
 @android.annotation.SuppressLint("WrongConstant")
 @Override public int open() throws Exception {
  failure="NONE";beforeAction();images=new HandlerThread("silent-display-frames");images.start();
  reader=ImageReader.newInstance(WIDTH,HEIGHT,PixelFormat.RGBA_8888,3);
  reader.setOnImageAvailableListener(source->{
   synchronized(imageLock) {
    try {
     Image image=source.acquireLatestImage();
     if(image!=null) {
      Image previous=latest;latest=image;imageTime=SystemClock.elapsedRealtime();render.arrived();
      if(previous!=null)try{previous.close();}catch(IllegalStateException ignored){ }
     }
    } catch(IllegalStateException ignored) { }
   }
  },new Handler(images.getLooper()));
  VirtualDisplayConfig config=new VirtualDisplayConfig.Builder("YuyanSilentPacket",WIDTH,HEIGHT,160)
   .setSurface(reader.getSurface()).setFlags(FLAGS).build();
  Class<?> type=Class.forName("android.hardware.display.DisplayManagerGlobal");
  Object global=type.getMethod("getInstance").invoke(null);
  display=(VirtualDisplay)type.getMethod("createVirtualDisplay",Context.class,MediaProjection.class,
   VirtualDisplayConfig.class,VirtualDisplay.Callback.class,Executor.class).invoke(global,context,null,config,null,null);
  if(display==null)throw new IllegalStateException("DISPLAY_CREATION_FAILED");
  return display.getDisplay().getDisplayId();
 }
 private void requireDisplay(int id) {
  if(id<=0 || display==null || !display.getDisplay().isValid() || display.getDisplay().getDisplayId()!=id)
   throw new IllegalStateException("INVALID_DISPLAY");
 }
 @Override public void launch(int id,int user) throws Exception {
  requireDisplay(id);
  if(user<0)throw new IllegalArgumentException("INVALID_USER");
  beforeAction();
  String result=ShellCommand.run("am","start","-W","--user",Integer.toString(user),"--display",Integer.toString(id),
   "-n","com.tencent.mm/.ui.LauncherUI");
  if(!result.contains("Status: ok"))throw new IllegalStateException("WECHAT_LAUNCH_FAILED");
 }
 @Override public Focus focus(int id) throws Exception {
  boolean on=id<0 || (display!=null && display.getDisplay().isValid() && display.getDisplay().getState()==Display.STATE_ON);
  return FocusParser.parse(ShellCommand.run("dumpsys","window","displays"),ShellCommand.run("dumpsys","input"),id,on);
 }
 @Override public Focus awaitFocus(int id,int user,String main,int mainUser) throws Exception {
  long deadline=SystemClock.elapsedRealtime()+1500;
  while(true) {
   Focus f=focus(id);
   if(!f.mainSafe(user) || f.mainUser!=mainUser || !main.equals(f.mainWindow) || !f.displayOn || f.secondaryWindow!=null)return f;
   long remaining=deadline-SystemClock.elapsedRealtime();if(remaining<=0)return f;
   Thread.sleep(Math.min(100,remaining));
  }
 }
 Bitmap capture(int id) {
  requireDisplay(id);
  synchronized(imageLock) {
   if(latest==null || !render.ready())return null;
   Image.Plane plane=latest.getPlanes()[0];
   if(plane.getPixelStride()!=4 || plane.getRowStride()%4!=0)return null;
   ByteBuffer source=plane.getBuffer().duplicate();
   ByteBuffer packed=ByteBuffer.allocate(WIDTH*HEIGHT*4);
   int start=source.position();
   for(int row=0;row<HEIGHT;row++) {
    int offset=start+row*plane.getRowStride();
    if(offset<0 || offset+WIDTH*4>source.limit())return null;
    ByteBuffer rowBytes=source.duplicate();rowBytes.position(offset);rowBytes.limit(offset+WIDTH*4);packed.put(rowBytes);
   }
   packed.flip();
   Bitmap result=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);
   try {
    result.copyPixelsFromBuffer(packed);
    capturedAt=SystemClock.elapsedRealtime();capturedAtUptime=SystemClock.uptimeMillis();return result;
   }
   catch(RuntimeException e) {result.recycle();throw e;}
  }
 }
 void tap(int id,int x,int y) throws Exception {
  requireDisplay(id);
  if(x<0 || y<0 || x>=WIDTH || y>=HEIGHT)throw new IllegalArgumentException("INVALID_COORDINATE");
  beforeAction();
  ShellCommand.run("input","-d",Integer.toString(id),"tap",Integer.toString(x),Integer.toString(y));
 }
 void back(int id) throws Exception {requireDisplay(id);beforeAction();ShellCommand.run("input","-d",Integer.toString(id),"keyevent","4");}
 @Override public void close() {
  if(display!=null){try{display.release();}catch(RuntimeException e){failure="DISPLAY_RELEASE_FAILED";}display=null;}
  synchronized(imageLock) {
   if(latest!=null){try{latest.close();}catch(RuntimeException e){failure="IMAGE_RELEASE_FAILED";}latest=null;}
   imageTime=0;
  }
  if(reader!=null){try{reader.setOnImageAvailableListener(null,null);reader.close();}catch(RuntimeException e){failure="READER_RELEASE_FAILED";}reader=null;}
  if(images!=null){images.quitSafely();images=null;}
 }
 void beforeAction(){synchronized(imageLock){render.requireNext();}}
 long capturedAt(){return capturedAt;}
 long capturedAtUptime(){return capturedAtUptime;}
 String failure(){return failure;}
}
