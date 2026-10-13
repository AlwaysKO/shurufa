package com.yuyan.redpacket.silent;

/** One immutable native portrait configuration shared by display, capture and input. */
final class DisplayProfile {
 final int width,height,densityDpi;
 private DisplayProfile(int width,int height,int densityDpi) {
  this.width=width;this.height=height;this.densityDpi=densityDpi;
 }
 static DisplayProfile fromRealMetrics(int realWidth,int realHeight,int densityDpi) {
  int width=Math.min(realWidth,realHeight),height=Math.max(realWidth,realHeight);
  if(width<=0 || height<=0 || densityDpi<=0 || width>4096 || height>8192 ||
     (long)width*height>8_388_608)
   throw new IllegalArgumentException("INVALID_DISPLAY_METRICS");
  return new DisplayProfile(width,height,densityDpi);
 }
 boolean contains(int x,int y) {return x>=0 && y>=0 && x<width && y<height;}
}
