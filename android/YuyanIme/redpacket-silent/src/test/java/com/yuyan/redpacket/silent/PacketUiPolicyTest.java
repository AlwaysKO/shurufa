package com.yuyan.redpacket.silent;
import org.junit.Test;
import static org.junit.Assert.*;
public class PacketUiPolicyTest {
 private Focus focus(String cls){return new Focus(0,"home/Main","com.tencent.mm/"+cls,0,128,true);}
 @Test public void exactlyKnownReceiveAndDetailActivitiesAreAcceptedInBothComponentForms(){
  for(String prefix:new String[]{"com.tencent.mm.","."}){
   assertEquals("RECEIVE",PacketUiPolicy.kind(focus(prefix+"plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI"),128));
   assertEquals("DETAIL",PacketUiPolicy.kind(focus(prefix+"plugin.luckymoney.ui.LuckyMoneyDetailUI"),128));
  }
 }
 @Test public void unknownAndLookalikeActivitiesNeverClaimAResult(){
  for(String cls:new String[]{"com.tencent.mm.ui.LauncherUI","com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyReceiveUI",
   "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUIExtra","other.LuckyMoneyDetailUI",
   "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyFakeDetailUI"})assertEquals("NONE",PacketUiPolicy.kind(focus(cls),128));
  assertEquals("NONE",PacketUiPolicy.kind(new Focus(0,"home/Main","other/com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI",0,128,true),128));
 }
 @Test public void exactActivityWithoutSameUserAndSafeOnDisplayIsNotEvidence(){
  Focus valid=focus("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI");
  for(Focus bad:new Focus[]{null,new Focus(7,"home/Main",valid.secondaryWindow,0,128,true),
    new Focus(0,"home/Main",valid.secondaryWindow,0,0,true),
    new Focus(0,"home/Main",valid.secondaryWindow,0,128,false),
    new Focus(0,"com.tencent.mm/Main",valid.secondaryWindow,128,128,true)})
   assertEquals("NONE",PacketUiPolicy.kind(bad,128));
 }
 @Test public void observationUsesFinalGuardFocusAndNeverReusesPreviousPageKind() throws Exception {
  class Backend extends WindowObservationTransactionTest.Backend {
   Focus current;
   @Override public boolean safe() throws Exception {
    boolean safe=super.safe();
    current=checks==1?focus("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI"):
     checks==2?focus("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI"):focus("com.tencent.mm.ui.LauncherUI");
    return safe;
   }
   @Override public Focus verifiedFocus(){return current;}
  }
  Backend backend=new Backend();WindowObservationTransaction transaction=new WindowObservationTransaction(new FrameGate(),backend);
  assertEquals("DETAIL",transaction.observe().packetUi);assertEquals(2,backend.checks);
  assertEquals("NONE",transaction.observe().packetUi);assertEquals(4,backend.checks);
 }
}
