package com.yuyan.redpacket.silent;
final class FrameGate {
 private long sequence,current,captured;
 private int width,height;
 long capture(long now,int width,int height) { this.width=width; this.height=height; captured=now; current=++sequence; return current; }
 boolean consume(long id,int x,int y,long now) {
  if(current!=0 && (now<captured || now-captured>1000)) { invalidate(); return false; }
  if(id<=0 || id!=current || x<0 || y<0 || x>=width || y>=height) return false;
  current=0; return true;
 }
 long id() { return current; }
 void invalidate() { current=0; }
}
