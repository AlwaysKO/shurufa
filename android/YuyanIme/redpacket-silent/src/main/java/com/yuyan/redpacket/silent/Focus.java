package com.yuyan.redpacket.silent;
final class Focus {
 final int topDisplay,mainUser,secondaryUser;
 final String mainWindow,secondaryWindow;
 final boolean displayOn;
 Focus(int top,String main,String secondary,int mainUser,int secondaryUser,boolean on) {
  topDisplay=top; mainWindow=main; secondaryWindow=secondary;
  this.mainUser=mainUser; this.secondaryUser=secondaryUser; displayOn=on;
 }
 boolean mainSafe(int selectedUser) {
  return selectedUser>=0 && topDisplay==0 && mainWindow!=null && !mainWindow.isEmpty()
   && (!SilentPolicy.wechat(mainWindow) || (mainUser>=0 && mainUser!=selectedUser));
 }
 boolean secondarySafe(int user) { return displayOn && secondaryUser==user && SilentPolicy.wechat(secondaryWindow); }
}
