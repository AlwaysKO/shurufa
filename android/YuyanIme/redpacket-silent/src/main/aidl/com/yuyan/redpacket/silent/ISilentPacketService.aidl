package com.yuyan.redpacket.silent;
import android.graphics.Bitmap;
import android.app.PendingIntent;
import android.os.Bundle;
interface ISilentPacketService {
 String start(int userId) = 0;
 String status() = 1;
 Bitmap capture() = 2;
 boolean tap(int x, int y, long frameId) = 3;
 boolean back() = 4;
 boolean launch(in PendingIntent intent) = 5;
 void stop() = 6;
 String users() = 7;
 Bundle captureFrame() = 8;
 void destroy() = 16777114;
}
