package com.yuyan.redpacket.silent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native portrait profile contract; production remains unchanged until device validation. */
public class DisplayProfileTest {
 @Test public void portraitKeepsRealPixelDimensionsAndNativeDensity() throws Exception {
  Object profile=profile(1200,2664,520);
  assertEquals(1200,value(profile,"width"));
  assertEquals(2664,value(profile,"height"));
  assertEquals(520,value(profile,"densityDpi"));
 }

 @Test public void landscapeMainDisplayOnlySwapsPrivateProfileDimensions() throws Exception {
  Object portrait=profile(1200,2664,520),landscape=profile(2664,1200,520);
  for(String field:new String[]{"width","height","densityDpi"})
   assertEquals(value(portrait,field),value(landscape,field));
  assertEquals(1080,value(profile(2400,1080,420),"width"));
  assertEquals(420,value(profile(2400,1080,420),"densityDpi"));
 }

 @Test public void acceptsInclusivePixelBudgetWithoutDownscalingOrChangingDensity() throws Exception {
  Object profile=profile(4096,2048,640);
  assertEquals(2048,value(profile,"width"));
  assertEquals(4096,value(profile,"height"));
  assertEquals(640,value(profile,"densityDpi"));
  assertEquals(8192,value(profile(1024,8192,160),"height"));
 }

 @Test public void rejectsMissingInvalidOrOversizedMetricsInsteadOfUsingFallback() throws Exception {
  int[][] invalid={{0,2664,520},{1200,0,520},{-1,2664,520},
   {1200,2664,0},{1200,2664,-1},{1024,8193,160},
   {4097,4097,160},{3000,3000,520},{Integer.MAX_VALUE,Integer.MAX_VALUE,520}};
  for(int[] metrics:invalid) {
   try {profile(metrics[0],metrics[1],metrics[2]);fail("Invalid native metrics accepted");}
   catch(IllegalArgumentException expected) { }
  }
 }

 @Test public void captureAndInputShareImmutableProfileBounds() throws Exception {
  Object profile=profile(2664,1200,520);
  for(String name:new String[]{"width","height","densityDpi"}) {
   Field field=profile.getClass().getDeclaredField(name);
   assertTrue("Profile dimensions must not change after display creation",Modifier.isFinal(field.getModifiers()));
  }
  assertTrue(contains(profile,0,0));
  assertTrue(contains(profile,1199,2663));
  assertFalse(contains(profile,1200,2663));
  assertFalse(contains(profile,1199,2664));
  assertFalse(contains(profile,-1,0));
  assertFalse(contains(profile,0,-1));
  assertFalse(contains(profile,Integer.MAX_VALUE,Integer.MAX_VALUE));
 }

 private Object profile(int width,int height,int density) throws Exception {
  Class<?> type;
  try {type=Class.forName("com.yuyan.redpacket.silent.DisplayProfile");}
  catch(ClassNotFoundException absent) {throw new AssertionError("Native DisplayProfile is not implemented",absent);}
  java.lang.reflect.Method method=type.getDeclaredMethod("fromRealMetrics",int.class,int.class,int.class);
  method.setAccessible(true);
  try {return method.invoke(null,width,height,density);}
  catch(InvocationTargetException failure) {
   if(failure.getCause() instanceof IllegalArgumentException)throw (IllegalArgumentException)failure.getCause();
   throw failure;
  }
 }
 private int value(Object profile,String name) throws Exception {
  Field field=profile.getClass().getDeclaredField(name);field.setAccessible(true);return field.getInt(profile);
 }
 private boolean contains(Object profile,int x,int y) throws Exception {
  java.lang.reflect.Method method=profile.getClass().getDeclaredMethod("contains",int.class,int.class);
  method.setAccessible(true);return (boolean)method.invoke(profile,x,y);
 }
}
