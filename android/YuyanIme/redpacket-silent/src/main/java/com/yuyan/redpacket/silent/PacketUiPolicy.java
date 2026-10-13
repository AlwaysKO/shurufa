package com.yuyan.redpacket.silent;
/** Exact classes present in the local WeChat classes13.dex; unknown versions fail closed. */
final class PacketUiPolicy {
 static String kind(Focus focus,int user){
  if(focus==null || !focus.mainSafe(user) || !focus.secondarySafe(user))return "NONE";
  String component=focus.secondaryWindow;
  if(exact(component,"LuckyMoneyNotHookReceiveUI"))return "RECEIVE";
  if(exact(component,"LuckyMoneyDetailUI"))return "DETAIL";
  return "NONE";
 }
 private static boolean exact(String component,String activity){
  String suffix="plugin.luckymoney.ui."+activity;
  return component.equals("com.tencent.mm/com.tencent.mm."+suffix)
   || component.equals("com.tencent.mm/."+suffix);
 }
}
