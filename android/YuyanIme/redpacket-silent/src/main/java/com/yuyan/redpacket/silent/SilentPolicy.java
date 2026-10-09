package com.yuyan.redpacket.silent;
final class SilentPolicy {
 static boolean wechat(String window) { return window != null && window.startsWith("com.tencent.mm/"); }
 // Android UserHandle.PER_USER_RANGE is 100000; creator UID carries its Android user.
 static boolean pending(boolean activity,String creator,int creatorUid,int selectedUser) {
  return activity && "com.tencent.mm".equals(creator) && creatorUid>=10000
   && selectedUser>=0 && creatorUid/100000==selectedUser;
 }
 static boolean caller(int caller,int client,int own,boolean destroy) {
  return client>=10000 && (caller==client || (destroy && caller==own));
 }
}
