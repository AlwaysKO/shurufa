package com.yuyan.redpacket.silent;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.regex.Matcher;

/** Shell UserService. Every operation is serialized; no public arbitrary-command entry point. */
@android.annotation.TargetApi(35)
public final class SilentPacketService extends ISilentPacketService.Stub {
 private final int clientUid;
 private final Context context;
 private final ShellDisplay backend;
 private final SilentSession session;
 private final FrameGate frames=new FrameGate();
 private final FrameCaptureTransaction<Bitmap> captureTransaction;
 private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
 private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
 private final HardDeadline hardDeadline=new HardDeadline(watchdog,()->Process.killProcess(Process.myPid()));
 private ScheduledFuture<?> monitor;
 public SilentPacketService(Context context) {
  if(Build.VERSION.SDK_INT<35 || Process.myUid()!=2000)throw new SecurityException("SHELL_API35_REQUIRED");
  clientUid=context.getApplicationInfo().uid;
  if(clientUid<10000)throw new SecurityException("INVALID_CLIENT");
  this.context=context;backend=new ShellDisplay(context);session=new SilentSession(backend);
  captureTransaction=new FrameCaptureTransaction<>(frames,new FrameCaptureTransaction.Backend<Bitmap>() {
   public boolean safe(){return SilentPacketService.this.safe();}
   public Bitmap capture(){return backend.capture(session.display());}
   public int width(Bitmap image){return image.getWidth();}
   public int height(Bitmap image){return image.getHeight();}
   public int displayId(){return session.display();}
   public long capturedAtElapsed(){return backend.capturedAt();}
   public long capturedAtUptime(){return backend.capturedAtUptime();}
   public void release(Bitmap image){image.recycle();}
  });
 }
 private void authorize(boolean destroy) {
  if(!SilentPolicy.caller(Binder.getCallingUid(),clientUid,Process.myUid(),destroy))throw new SecurityException("WRONG_CALLER");
 }
 private <T> T run(Callable<T> operation,T failed) {
  authorize(false);
  Future<T> task=worker.submit(operation);
  try{return task.get(25,TimeUnit.SECONDS);}
  catch(Exception e){
   task.cancel(true);
   watchdog.schedule(()->Process.killProcess(Process.myPid()),3,TimeUnit.SECONDS);
   if(e instanceof InterruptedException)Thread.currentThread().interrupt();
   return failed;
  }
 }
 @Override public String start(int userId) {
  authorize(false);hardDeadline.arm(SilentSession.DURATION_MS);
  return run(()->{
   hardDeadline.arm(SilentSession.DURATION_MS);
   if(userId<0 || !installed(userId)){session.stop("USER_UNAVAILABLE");finishCleanup();return snapshot();}
   session.start(Process.myUid(),userId,SystemClock.elapsedRealtime());
   if(session.running() && monitor==null)monitor=worker.scheduleWithFixedDelay(()->{
    session.verify(SystemClock.elapsedRealtime());if(!session.running())finishCleanup();
   },2,2,TimeUnit.SECONDS);
   if(!session.running())finishCleanup();
   return snapshot();
  },"{\"state\":\"SERVICE_UNRESPONSIVE\"}");
 }
 @Override public String status(){return run(()->{safe();return snapshot();},"{\"state\":\"SERVICE_UNRESPONSIVE\"}");}
 private boolean safe() {
  boolean ok=session.verify(SystemClock.elapsedRealtime());
  if(!ok)finishCleanup();return ok;
 }
 @Override public Bundle captureFrame(){return run(()->{
  FrameCaptureTransaction.Frame<Bitmap> frame=captureTransaction.capture();
  if(frame==null)return null;
  boolean transferred=false;
  try {
   Bundle result=new Bundle();result.putParcelable("bitmap",frame.image);
   result.putLong("frameId",frame.frameId);result.putInt("displayId",frame.displayId);
   result.putLong("capturedAtUptime",frame.capturedAtUptime);
   transferred=true;return result;
  } finally {if(!transferred){frames.invalidate();frame.image.recycle();}}
 },null);}
 @Override public Bitmap capture(){return run(()->{
  FrameCaptureTransaction.Frame<Bitmap> frame=captureTransaction.capture();
  return frame==null?null:frame.image;
 },null);}
 @Override public boolean tap(int x,int y,long frameId){return run(()->{
  if(!safe() || !frames.consume(frameId,x,y,SystemClock.elapsedRealtime()))return false;
  backend.tap(session.display(),x,y);return safe();
 },false);}
 @Override public boolean back(){return run(()->{
  if(!safe())return false;frames.invalidate();backend.back(session.display());return safe();
 },false);}
 @Override public boolean launch(PendingIntent intent){return run(()->{
  if(intent==null || !SilentPolicy.pending(intent.isActivity(),intent.getCreatorPackage(),intent.getCreatorUid(),session.user()) || !safe())return false;
  frames.invalidate();
  ActivityOptions options=ActivityOptions.makeBasic().setLaunchDisplayId(session.display())
   .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
  // Sender explicitly grants its shell BAL privileges; no creator-side or primary-display fallback.
  backend.beforeAction();
  intent.send(context,0,null,null,null,null,options.toBundle());
  return safe();
 },false);}
 @Override public void stop(){
  authorize(false);ScheduledFuture<?> fuse=watchdog.schedule(()->Process.killProcess(Process.myPid()),3,TimeUnit.SECONDS);
  run(()->{session.stop("STOPPED");finishCleanup();fuse.cancel(false);return true;},false);
 }
 @Override public String users(){return run(()->{
  JSONArray users=new JSONArray();
  String all=ShellCommand.run("pm","list","users");
  int[] available=InstalledUsers.available(all,id->InstalledUsers.hasWechat(
   ShellCommand.run("pm","list","packages","--user",Integer.toString(id),"com.tencent.mm")));
  Matcher matcher=InstalledUsers.USER.matcher(all);
  while(matcher.find()) {
   int id=Integer.parseInt(matcher.group(1));
   for(int valid:available)if(valid==id)users.put(new JSONObject().put("id",id).put("name",matcher.group(2)));
  }
  return new JSONObject().put("users",users).toString();
 },"{\"users\":[],\"error\":\"USER_ENUMERATION_FAILED\"}");}
 private boolean installed(int user) throws Exception {
  if(user<0)return false;
  // Verify a real Android user and that this exact package is installed for that user.
  boolean found=false;for(int id:InstalledUsers.parse(ShellCommand.run("pm","list","users")))if(id==user)found=true;
  if(!found)return false;
  try{return InstalledUsers.hasWechat(ShellCommand.run("pm","list","packages","--user",Integer.toString(user),"com.tencent.mm"));}
  catch(Exception e){return false;}
 }
 private String snapshot() throws Exception {
  return new JSONObject().put("state",session.state()).put("displayId",session.display())
   .put("frameId",frames.id()).put("mainSafe",session.mainSafe()).put("secondaryVerified",session.secondaryVerified())
   .put("failure",session.failure()).put("userId",session.user()).toString();
 }
 private void finishCleanup(){
  frames.invalidate();if(monitor!=null){monitor.cancel(false);monitor=null;}
  if(!"NONE".equals(backend.failure()))Process.killProcess(Process.myPid());
  hardDeadline.disarm();
 }
 @Override public void destroy(){
  authorize(true);watchdog.schedule(()->Process.killProcess(Process.myPid()),3,TimeUnit.SECONDS);
  worker.execute(()->{session.stop("DESTROYED");finishCleanup();worker.shutdown();System.exit(0);});
 }
}
