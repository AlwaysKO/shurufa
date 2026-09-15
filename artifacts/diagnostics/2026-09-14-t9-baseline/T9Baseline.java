import android.content.Context;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.zip.*;
import java.io.*;
public class T9Baseline {
 static Object call(Class<?> c,String n,Class<?>[] t,Object... a) throws Exception {return c.getMethod(n,t).invoke(null,a);}
 public static void main(String[] args) throws Exception {
  System.out.println("START"); String root=args[0];
  try(ZipFile z=new ZipFile(args[1])) {
   var it=z.entries();
   while(it.hasMoreElements()) {var e=it.nextElement(); if(e.isDirectory()||!e.getName().startsWith("assets/rime/"))continue;
    Path p=Paths.get(root,"data",e.getName().substring(12));Files.createDirectories(p.getParent());
    try(InputStream s=z.getInputStream(e)){Files.copy(s,p,StandardCopyOption.REPLACE_EXISTING);}
   }
  }
  System.out.println("ASSETS_READY");
  android.content.res.AssetManager am=android.content.res.AssetManager.class.getConstructor().newInstance();
  android.content.res.AssetManager.class.getMethod("addAssetPath",String.class).invoke(am,args[1]);
  Context app=new android.content.ContextWrapper(null) { public android.content.res.AssetManager getAssets(){return am;} public String getPackageName(){return "com.yuyan.pinyin.offline.debug";} };
  System.out.println("CONTEXT_READY");
  Class<?> r=Class.forName("com.yuyan.inputmethod.core.Rime");
  System.out.println("CLASS_READY");
  call(r,"startupRime",new Class[]{Context.class,String.class,String.class,boolean.class},app,root+"/data",root+"/data",false);
  System.out.println("SCHEMA="+call(r,"selectRimeSchema",new Class[]{String.class},"t9_pinyin"));
  call(r,"setRimePageSize",new Class[]{int.class},100);
  Class<?> lc=Class.forName("com.yuyan.imemodule.data.completion.T9Lexicon");
  Object companion=lc.getField("Companion").get(null);
  Class<?> oc=Class.forName("com.yuyan.imemodule.data.completion.OfflineT9Candidates");
  Object offline=oc.getField("INSTANCE").get(null);
  try(ZipFile z=new ZipFile(args[1])) {
   for(String field:new String[]{"lexicon","domains"}) {
    InputStream in=z.getInputStream(z.getEntry("assets/completion/"+(field.equals("lexicon")?"t9_lexicon.tsv.gzip":"chinese_domains.tsv")));
    if(field.equals("lexicon"))in=new java.util.zip.GZIPInputStream(in);
    Object lex=companion.getClass().getMethod("parse",Reader.class).invoke(companion,new InputStreamReader(in,"UTF-8"));
    Field f=oc.getDeclaredField(field); f.setAccessible(true); f.set(null,lex);in.close();
   }
  }
  for(String code:new String[]{"94363362","9267426548","2466434262","9366","93663","64324862","9664337","64426"}) {
   call(r,"clearRimeComposition",new Class[]{});
   for(char d:code.toCharArray()) call(r,"processRimeKey",new Class[]{int.class,int.class},(int)"ADGJMPTW".charAt(d-'2'),0);
   Object context=call(r,"getRimeContext",new Class[]{});
   System.out.println("CODE="+code+" CONTEXT="+context);
   Object[] cands=(Object[])context.getClass().getMethod("getCandidates").invoke(context);
   java.util.List<String> texts=new java.util.ArrayList<>(), readings=new java.util.ArrayList<>();
   for(Object c:cands){texts.add((String)c.getClass().getMethod("getText").invoke(c));readings.add((String)c.getClass().getMethod("getComment").invoke(c));}
   Object selection=oc.getMethod("select",String.class,java.util.List.class,java.util.List.class).invoke(offline,code,texts,readings);
   java.util.List<?> first=(java.util.List<?>)selection.getClass().getMethod("getFirstPage").invoke(selection);
   System.out.println("AFTER="+code+" "+first.subList(0,Math.min(10,first.size())));

  }
  call(r,"exitRime",new Class[]{});
 }
}
