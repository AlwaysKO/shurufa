package com.yuyan.redpacket.silent;
import java.util.regex.*;
import java.util.ArrayList;
final class InstalledUsers {
 static final Pattern USER=Pattern.compile("UserInfo\\{(\\d+):([^}:]*):[^}]*\\}");
 static int[] parse(String output) {
  ArrayList<Integer> ids=new ArrayList<>(); Matcher m=USER.matcher(output);
  while(m.find()) { int id=Integer.parseInt(m.group(1)); if(!ids.contains(id)) ids.add(id); }
  int[] result=new int[ids.size()]; for(int i=0;i<result.length;i++) result[i]=ids.get(i); return result;
 }
 interface Check {boolean installed(int user) throws Exception;}
 static int[] available(String output,Check check) {
  ArrayList<Integer> result=new ArrayList<>();
  for(int id:parse(output))try{if(check.installed(id))result.add(id);}catch(Exception ignored){ }
  int[] ids=new int[result.size()];for(int i=0;i<ids.length;i++)ids[i]=result.get(i);return ids;
 }
 static boolean hasWechat(String packages) {
  for(String line:packages.split("\n")) if(line.trim().equals("package:com.tencent.mm")) return true;
  return false;
 }
}
