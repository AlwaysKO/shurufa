package com.yuyan.redpacket.silent;
final class SilentSession {
 static final long DURATION_MS=300000;
 interface Backend {
  int open() throws Exception;
  default int open(boolean notification) throws Exception {
   if(notification)throw new IllegalStateException("PUBLIC_DISPLAY_UNSUPPORTED");return open();
  }
  void launch(int display,int user) throws Exception;
  Focus focus(int display) throws Exception;
  Focus awaitFocus(int display,int user,String main,int mainUser) throws Exception;
  default Focus awaitAfterAction(int display,int user,String main,int mainUser) throws Exception {return focus(display);}
  void close();
 }
 private final Backend backend;
 private final FrameGate frames;
 private boolean notificationMode;
 private String state="IDLE",failure="NONE";
 private int display=-1,user=-1;
 private boolean owned;
 private long deadline;
 private Focus last;
 SilentSession(Backend backend) {this(backend,new FrameGate());}
 SilentSession(Backend backend,FrameGate frames) {this.backend=backend;this.frames=frames;}
 boolean notificationMode(){return running() && notificationMode;}
 void start(int uid,int user,long now) {start(uid,user,now,false);}
 void start(int uid,int user,long now,boolean notification) {
  if(running()) {
   if(this.user==user && notificationMode==notification)return;
   stop(this.user!=user?"USER_CHANGED":"DISPLAY_MODE_CHANGED");
  }
  frames.invalidate();notificationMode=notification;
  failure="NONE";last=null;
  if(uid!=2000) {state="SHELL_REQUIRED";return;}
  if(user<0) {state="INVALID_USER";return;}
  try {
   Focus before=backend.focus(-1);last=before;
   if(!before.mainSafe(user)) {state=SilentPolicy.wechat(before.mainWindow)?"MAIN_WECHAT_IN_USE":"MAIN_FOCUS_UNKNOWN";return;}
   this.user=user;owned=true;display=backend.open(notification);
   if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
   if(display<=0) {stop("INVALID_DISPLAY");return;}
   backend.launch(display,user);
   Focus after=backend.awaitFocus(display,user,before.mainWindow,before.mainUser);last=after;
   if(!after.mainSafe(user) || before.mainUser!=after.mainUser || !before.mainWindow.equals(after.mainWindow)) {stop("MAIN_FOCUS_CHANGED");return;}
   if(!after.secondarySafe(user)) {stop("SECONDARY_UNVERIFIED");return;}
   deadline=now+DURATION_MS;state="RUNNING";
  } catch(Exception e) {stop("START_FAILED");}
 }
 boolean verify(long now) {
  if(!running()) return false;
  if(now>=deadline) {stop("EXPIRED");return false;}
  try {
   last=backend.focus(display);
   if(!last.mainSafe(user)) {
    stop(SilentPolicy.wechat(last.mainWindow) && (last.mainUser<0 || last.mainUser==user)
     ? "MAIN_WECHAT_IN_USE" : "MAIN_FOCUS_LOST");
   }
   else if(!last.secondarySafe(user)) stop("SECONDARY_UNVERIFIED");
  } catch(Exception e) {stop("STATUS_UNKNOWN");}
  return running();
 }
 boolean afterAction(long now) {
  if(!running())return false;
  if(now>=deadline){stop("EXPIRED");return false;}
  try {
   Focus after=backend.awaitAfterAction(display,user,last.mainWindow,last.mainUser);last=after;
   if(!after.mainSafe(user))stop(SilentPolicy.wechat(after.mainWindow)
    && (after.mainUser<0 || after.mainUser==user)?"MAIN_WECHAT_IN_USE":"MAIN_FOCUS_LOST");
   else if(!after.secondarySafe(user))stop("SECONDARY_UNVERIFIED");
  }catch(Exception e){stop("STATUS_UNKNOWN");if(e instanceof InterruptedException)Thread.currentThread().interrupt();}
  return running();
 }
 void stop(String reason) {
  frames.invalidate();
  if(!reason.equals("STOPPED") && !reason.equals("DESTROYED")) failure=reason;
  state=reason;display=-1;
  if(owned) {owned=false;backend.close();}
 }
 boolean running(){return state.equals("RUNNING");}
 String state(){return state;}
 String failure(){return failure;}
 int display(){return display;}
 int user(){return user;}
 Focus verifiedFocus(){return running()?last:null;}
 boolean mainSafe(){return running() && last!=null && last.mainSafe(user);}
 boolean secondaryVerified(){return running() && last!=null && last.secondarySafe(user);}
}
