package com.yuyan.imemodule.data.redpacket
import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test
class NativePacketReaderTest {
 private val ticket=NativePacketTicket(1,100,8,128,1000,2000)
 private val window=NativePacketWindow(8,128,7,IntRect(0,0,1000,2000),listOf(NativePacketNode("0",null,IntRect(0,0,1000,2000),windowId=7)))
 @Test fun freshBoundedWindowCanBeRead() {assertNotNull(NativePacketReader.read(ticket,NativePacketWindowSource{window}, clock={101}))}
 @Test fun expiredFutureMainDisplayOrInvalidTicketNeverReadsSource() {
  for(t in listOf(ticket.copy(capturedAt=-1),ticket.copy(capturedAt=2000),ticket.copy(displayId=0),ticket.copy(frameId=0),ticket.copy(width=0))) {
   var called=false;assertNull(NativePacketReader.read(t,NativePacketWindowSource{called=true;window}, clock={1101}));assertFalse(called)
  }
 }
 @Test fun sourceCostIsIncludedInTtl() {var time=100L;assertNull(NativePacketReader.read(ticket,NativePacketWindowSource{time=1101;window}, clock={time}))}
 @Test fun sourceFailureOrMissingWindowFailsClosed() {
  assertNull(NativePacketReader.read(ticket,NativePacketWindowSource{throw IllegalStateException()}, clock={101}))
  assertNull(NativePacketReader.read(ticket,NativePacketWindowSource{null}, clock={101}))
 }
 @Test fun factsContainOnlyFixedMetadataAndCallbackFailureDoesNotAbort() {
  var facts:NativePacketReadFacts?=null
  assertNotNull(NativePacketReader.read(ticket,NativePacketWindowSource{window},clock={101},onFacts={facts=it}))
  assertEquals(NativePacketReadStatus.VALID,facts!!.status)
  assertEquals(1,facts!!.nodeCount)
  assertNotNull(NativePacketReader.read(ticket,NativePacketWindowSource{window},clock={101},onFacts={throw IllegalStateException()}))
 }
 @Test fun realWindowMetadataSelectorRequiresOneApplicationOnExactDisplay() {
  val selected=NativePacketWindowMeta(8,0,true,window.bounds)
  val main=selected.copy(displayId=0,windowId=9)
  assertEquals(selected,selectNativePacketWindow(ticket,listOf(main,selected)))
  assertNull(selectNativePacketWindow(ticket,listOf(main)))
  assertNull(selectNativePacketWindow(ticket,listOf(selected,selected.copy(windowId=2))))
  assertNull(selectNativePacketWindow(ticket,listOf(selected,selected.copy(windowId=2,application=false))))
  assertNull(selectNativePacketWindow(ticket,listOf(selected.copy(application=false))))
  assertNull(selectNativePacketWindow(ticket,listOf(selected.copy(bounds=IntRect(-1,0,1000,2000)))))
 }
 @Test fun thinSystemDecorationsAreAllowedButOtherOverlaysAreRejected() {
  val app=NativePacketWindowMeta(8,0,true,window.bounds)
  val top=NativePacketWindowMeta(8,2,false,IntRect(0,0,1000,100),windowType=3,layer=2)
  val bottom=top.copy(windowId=3,bounds=IntRect(0,1900,1000,2000))
  assertEquals(app,selectNativePacketWindow(ticket,listOf(app,top,bottom)))
  for (bad in listOf(top.copy(windowType=2),top.copy(windowType=4),
      top.copy(bounds=IntRect(0,0,1000,400)),top.copy(bounds=IntRect(100,0,800,100)),
      top.copy(bounds=IntRect(0,700,1000,800))))
   assertNull(selectNativePacketWindow(ticket,listOf(app,bad)))
 }
 @Test fun fullBackendNativePixelBudgetIsAcceptedWithoutResizing() {
  for ((width,height) in listOf(2001 to 4000,2160 to 3840,2048 to 4096)) {
   val t=ticket.copy(width=width,height=height);val b=IntRect(0,0,width,height)
   val w=window.copy(bounds=b,nodes=listOf(NativePacketNode("0",null,b,windowId=7)))
   assertNotNull(NativePacketReader.read(t,NativePacketWindowSource{w},clock={101}))
  }
  assertTrue(nativePacketPixelBudget(8_388_608));assertFalse(nativePacketPixelBudget(8_388_609))
 }
 @Test fun permissionCancellationDoesNotReadAndStopsAfterSource() {
  var called=false
  assertNull(NativePacketReader.read(ticket,NativePacketWindowSource{called=true;window},clock={101},permitted={false}));assertFalse(called)
  var allowed=true
  assertNull(NativePacketReader.read(ticket,NativePacketWindowSource{allowed=false;window},clock={101},permitted={allowed}))
 }
 @Test fun allTargetDisplayWindowMetadataMustRemainStableAcrossTreeRead() {
  val app=NativePacketWindowMeta(8,0,true,window.bounds)
  val top=NativePacketWindowMeta(8,2,false,IntRect(0,0,1000,100),windowType=3,layer=2)
  assertTrue(nativePacketWindowSetStable(listOf(app,top),listOf(top,app)))
  for(after in listOf(listOf(app),listOf(app,top.copy(bounds=IntRect(0,0,1000,150))),
      listOf(app,top.copy(layer=3)),listOf(app,top,top.copy(windowId=3,bounds=IntRect(0,1900,1000,2000)))))
   assertFalse(nativePacketWindowSetStable(listOf(app,top),after))
 }
}
