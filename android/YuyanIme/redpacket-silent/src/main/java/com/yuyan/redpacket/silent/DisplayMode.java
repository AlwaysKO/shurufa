package com.yuyan.redpacket.silent;
final class DisplayMode {
 static final int ISOLATED_FLAGS=(1<<3)|(1<<6)|(1<<8)|(1<<10)|(1<<11)|(1<<14)|(1<<16);
 static int flags(boolean notification){return ISOLATED_FLAGS|(notification?1:0);}
}
