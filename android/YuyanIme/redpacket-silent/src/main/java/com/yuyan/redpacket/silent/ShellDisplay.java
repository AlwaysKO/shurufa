package com.yuyan.redpacket.silent;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
@android.annotation.TargetApi(35)
final class ShellDisplay implements SilentSession.Backend {
 private final Context context;
 private final Object imageLock=new Object();
 private VirtualDisplay display;
 private DisplayProfile profile;
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
 @Override public int open() throws Exception {return open(false);}
 @android.annotation.SuppressLint("WrongConstant")
 @Override public int open(boolean notification) throws Exception {
  failure="NONE";
  DisplayManager manager=context.getSystemService(DisplayManager.class);
  Display main=manager==null?null:manager.getDisplay(Display.DEFAULT_DISPLAY);
  if(main==null || !main.isValid())throw new IllegalStateException("INVALID_DISPLAY_METRICS");
  DisplayMetrics metrics=new DisplayMetrics();main.getRealMetrics(metrics);
  profile=DisplayProfile.fromRealMetrics(metrics.widthPixels,metrics.heightPixels,metrics.densityDpi);
  beforeAction();images=new HandlerThread("silent-display-frames");images.start();
  reader=ImageReader.newInstance(profile.width,profile.height,PixelFormat.RGBA_8888,3);
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
  VirtualDisplayConfig config=new VirtualDisplayConfig.Builder("YuyanSilentPacket",profile.width,profile.height,profile.densityDpi)
   .setSurface(reader.getSurface()).setFlags(DisplayMode.flags(notification)).build();
  Class<?> type=Class.forName("android.hardware.display.DisplayManagerGlobal");
  Object global=type.getMethod("getInstance").invoke(null);
  display=(VirtualDisplay)type.getMethod("createVirtualDisplay",Context.class,MediaProjection.class,
   VirtualDisplayConfig.class,VirtualDisplay.Callback.class,Executor.class).invoke(global,context,null,config,null,null);
  if(display==null)throw new IllegalStateException("DISPLAY_CREATION_FAILED");
  return display.getDisplay().getDisplayId();
 }
 private void requireDisplay(int id) {
  if(id<=0 || display==null || profile==null || !display.getDisplay().isValid() || display.getDisplay().getDisplayId()!=id)
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
  return awaitFocus(id,user,main,mainUser,false);
 }
 @Override public Focus awaitAfterAction(int id,int user,String main,int mainUser) throws Exception {
  return awaitFocus(id,user,main,mainUser,true);
 }
 private Focus awaitFocus(int id,int user,String main,int mainUser,boolean afterAction) throws Exception {
  long deadline=SystemClock.elapsedRealtime()+1500;
  while(true) {
   boolean on=display!=null && display.getDisplay().isValid() && display.getDisplay().getState()==Display.STATE_ON;
   String windows=ShellCommand.runFor(deadline-SystemClock.elapsedRealtime(),"dumpsys","window","displays");
   String input=ShellCommand.runFor(deadline-SystemClock.elapsedRealtime(),"dumpsys","input");
   Focus f=FocusParser.parse(windows,input,id,on);long now=SystemClock.elapsedRealtime();
   if(now>deadline)throw new IllegalStateException("FOCUS_TIMEOUT");
   boolean wait=afterAction?StartupFocus.shouldWaitAfterAction(f,user,main,mainUser,now,deadline)
    :StartupFocus.shouldWait(f,user,main,mainUser,now,deadline);
   if(!wait)return f;
   Thread.sleep(Math.min(100,deadline-now));
  }
 }
 int width(int id){requireDisplay(id);return profile.width;}
 int height(int id){requireDisplay(id);return profile.height;}
 Bitmap capture(int id) {
  requireDisplay(id);
  synchronized(imageLock) {
   if(latest==null || !render.ready())return null;
   DisplayProfile active=profile;
   if(latest.getWidth()!=active.width || latest.getHeight()!=active.height)return null;
   Image.Plane plane=latest.getPlanes()[0];
   if(plane.getPixelStride()!=4 || plane.getRowStride()%4!=0)return null;
   ByteBuffer source=plane.getBuffer().duplicate();
   ByteBuffer packed=ByteBuffer.allocate(active.width*active.height*4);
   int start=source.position();
   for(int row=0;row<active.height;row++) {
    long offset=(long)start+(long)row*plane.getRowStride();
    if(offset<0 || offset+(long)active.width*4>source.limit())return null;
    ByteBuffer rowBytes=source.duplicate();rowBytes.position((int)offset);rowBytes.limit((int)offset+active.width*4);packed.put(rowBytes);
   }
   packed.flip();
   Bitmap result=Bitmap.createBitmap(active.width,active.height,Bitmap.Config.ARGB_8888);
   try {
    result.copyPixelsFromBuffer(packed);
    capturedAt=SystemClock.elapsedRealtime();capturedAtUptime=SystemClock.uptimeMillis();return result;
   }
   catch(RuntimeException e) {result.recycle();throw e;}
  }
 }
 void tap(int id,int x,int y) throws Exception {
  requireDisplay(id);
  if(!profile.contains(x,y))throw new IllegalArgumentException("INVALID_COORDINATE");
  beforeAction();
  ShellCommand.run("input","-d",Integer.toString(id),"tap",Integer.toString(x),Integer.toString(y));
 }
 void back(int id) throws Exception {requireDisplay(id);beforeAction();ShellCommand.run("input","-d",Integer.toString(id),"keyevent","4");}
 @Override public void close() {
  if(display!=null){try{display.release();}catch(RuntimeException e){failure="DISPLAY_RELEASE_FAILED";}display=null;}
  synchronized(imageLock) {
   if(latest!=null){try{latest.close();}catch(RuntimeException e){failure="IMAGE_RELEASE_FAILED";}latest=null;}
   imageTime=0;
   profile=null;
  }
  if(reader!=null){try{reader.setOnImageAvailableListener(null,null);reader.close();}catch(RuntimeException e){failure="READER_RELEASE_FAILED";}reader=null;}
  if(images!=null){images.quitSafely();images=null;}
 }
 void beforeAction(){synchronized(imageLock){render.requireNext();}}
 long capturedAt(){return capturedAt;}
 long capturedAtUptime(){return capturedAtUptime;}
 String failure(){return failure;}
}
