package com.yuyan.redpacket.silent;
import org.junit.Test;
import static org.junit.Assert.*;
public class ShellCommandDeadlineTest {
 @Test public void commandThatOutlivesRemainingBudgetIsKilledWithoutFourSecondWait() throws Exception {
  long started=System.nanoTime();
  try{ShellCommand.runFor(80,"sh","-c","exec sleep 2");fail("remaining focus budget must stop command");}
  catch(IllegalStateException expected){assertEquals("COMMAND_TIMEOUT",expected.getMessage());}
  assertTrue("timeout must not wait for command completion",(System.nanoTime()-started)/1_000_000<1500);
 }
 @Test public void noRemainingBudgetRejectsEvenAnOtherwiseSuccessfulCommand() throws Exception {
  try{ShellCommand.runFor(0,"sh","-c","printf fixed");fail("expired budget must not spawn command");}
  catch(IllegalStateException expected){assertEquals("COMMAND_TIMEOUT",expected.getMessage());}
 }
 @Test public void successfulBoundedCommandRetainsExistingOutputContract() throws Exception {
  assertEquals("fixed",ShellCommand.runFor(1000,"sh","-c","printf fixed"));
 }
}
