package com.yuyan.redpacket.silent;
final class RenderGate {
 private long observed,required=1;
 void arrived(){observed++;}
 void requireNext(){required=observed+1;}
 boolean ready(){return observed>=required;}
}
