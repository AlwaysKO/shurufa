package com.yuyan.redpacket.silent;
/** Startup can traverse unrelated launch windows; actions only tolerate missing/turning-on focus. */
final class StartupFocus {
 static boolean shouldWait(Focus focus,int user,String main,int mainUser,long now,long deadline){
  return now<deadline && focus.mainSafe(user) && focus.mainUser==mainUser
   && main!=null && main.equals(focus.mainWindow) && !focus.secondarySafe(user);
 }
 static boolean shouldWaitAfterAction(Focus focus,int user,String main,int mainUser,long now,long deadline){
  if(now>=deadline || !focus.mainSafe(user) || focus.secondarySafe(user))return false;
  if(focus.secondaryUser>=0 && focus.secondaryUser!=user)return false;
  if(focus.secondaryWindow==null)return true;
  return !focus.displayOn && focus.secondaryUser==user && SilentPolicy.wechat(focus.secondaryWindow);
 }
}
