package com.yuyan.redpacket.silent;
import org.junit.Test;
import static org.junit.Assert.*;
public class RenderGateTest {
 @Test public void staticFrameRemainsReadableUntilAnActionRequiresNewContent() {
  RenderGate gate=new RenderGate();assertFalse(gate.ready());
  gate.arrived();assertTrue(gate.ready());assertTrue(gate.ready());
  gate.requireNext();assertFalse(gate.ready());
  gate.arrived();assertTrue(gate.ready());
 }
 @Test public void unavailableUserDoesNotHideOtherInstalledUsers() {
  int[] users=InstalledUsers.available("UserInfo{0:Owner:0}\nUserInfo{100:Private:0}\nUserInfo{128:Clone:0}",id->{if(id==100)throw new SecurityException();return true;});
  assertArrayEquals(new int[]{0,128},users);
 }
}
