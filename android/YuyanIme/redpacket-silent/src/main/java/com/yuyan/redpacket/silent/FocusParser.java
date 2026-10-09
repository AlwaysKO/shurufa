package com.yuyan.redpacket.silent;
import java.util.regex.*;
final class FocusParser {
 private static final Pattern DISPLAY=Pattern.compile("Display: mDisplayId=(\\d+)");
 private static final Pattern WINDOW=Pattern.compile("mCurrentFocus=Window\\{[0-9a-fA-F]+ u(\\d+) ([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)\\}");
 private static final Pattern TOP=Pattern.compile("FocusedDisplayId:\\s*(\\d+)");
 static Focus parse(String windows,String input,int target,boolean on) {
  int current=-1,top=-1,mainUser=-1,secondaryUser=-1;
  boolean conflict=false; String main=null,secondary=null;
  for(String line:windows.split("\n")) {
   Matcher d=DISPLAY.matcher(line); if(d.find()) current=Integer.parseInt(d.group(1));
   Matcher w=WINDOW.matcher(line); if(w.find()) {
    if(current==0) { main=w.group(2); mainUser=Integer.parseInt(w.group(1)); }
    if(target>0 && current==target) { secondary=w.group(2); secondaryUser=Integer.parseInt(w.group(1)); }
   }
  }
  Matcher m=TOP.matcher(input); while(m.find()) { int value=Integer.parseInt(m.group(1)); if(top!=-1 && top!=value) conflict=true; top=value; }
  return new Focus(conflict?-1:top,main,secondary,mainUser,secondaryUser,on);
 }
}
