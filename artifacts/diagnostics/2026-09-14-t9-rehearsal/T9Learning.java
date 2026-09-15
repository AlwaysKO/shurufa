import android.content.Context;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.zip.*;
import java.io.*;
public class T9Learning {
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
  Context app=new android.content.ContextWrapper(null) { public android.content.res.AssetManager getAssets(){return am;} public String getPackageName(){return "com.yuyan.pinyin.offline.debug";}
   public Context getApplicationContext(){return this;}
   public File getDatabasePath(String name){return new File(root,name);}
   public android.content.pm.ApplicationInfo getApplicationInfo(){android.content.pm.ApplicationInfo i=new android.content.pm.ApplicationInfo();i.targetSdkVersion=28;return i;}
   public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String n,int m,android.database.sqlite.SQLiteDatabase.CursorFactory f){return android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(n),f);}
   public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String n,int m,android.database.sqlite.SQLiteDatabase.CursorFactory f,android.database.DatabaseErrorHandler e){return android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(n).getPath(),f,e);}
 };
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
  Class<?> sc=Class.forName("com.yuyan.imemodule.data.collect.LocalInputStore");
  Class<?> func=Class.forName("kotlin.jvm.functions.Function0");
  Object clock=java.lang.reflect.Proxy.newProxyInstance(func.getClassLoader(),new Class[]{func},(proxy,method,aa)->System.currentTimeMillis());
  java.lang.reflect.Constructor<?> ctor=sc.getConstructor(Context.class,String.class,func);
  Object store=ctor.newInstance(app,"probe.db",clock);
  Field sf=oc.getDeclaredField("store");sf.setAccessible(true);sf.set(null,store);
  long started=System.currentTimeMillis(); int count=0;
  java.util.List<String> cases=Files.readAllLines(Paths.get(args[2]));
  try(PrintWriter out=new PrintWriter(new OutputStreamWriter(new FileOutputStream(args[3]),"UTF-8"))) {
  for(String line:cases) {
   String[] parts=line.split("\\t"); String code=parts[0];
   call(r,"clearRimeComposition",new Class[]{});
   for(char d:code.toCharArray()) call(r,"processRimeKey",new Class[]{int.class,int.class},(int)"ADGJMPTW".charAt(d-'2'),0);
   Object context=call(r,"getRimeContext",new Class[]{});
   Object[] cands=(Object[])context.getClass().getMethod("getCandidates").invoke(context);
   java.util.List<String> texts=new java.util.ArrayList<>(), readings=new java.util.ArrayList<>();
   for(Object c:cands){texts.add((String)c.getClass().getMethod("getText").invoke(c));readings.add((String)c.getClass().getMethod("getComment").invoke(c));}
   Object selection=oc.getMethod("select",String.class,java.util.List.class,java.util.List.class).invoke(offline,code,texts,readings);
   java.util.List<?> first=(java.util.List<?>)selection.getClass().getMethod("getFirstPage").invoke(selection);
   java.util.List<String> filtered=new java.util.ArrayList<>();
   for(Object c:first)filtered.add((String)c.getClass().getMethod("getText").invoke(c));
   android.database.sqlite.SQLiteDatabase db=(android.database.sqlite.SQLiteDatabase)sc.getMethod("getWritableDatabase").invoke(store);
   db.execSQL("DELETE FROM learned_input");
   for(int i=0;i<3;i++)sc.getMethod("learn",String.class,String.class,java.util.List.class).invoke(store,parts[2],parts[1],java.util.Collections.emptyList());
   sc.getMethod("close").invoke(store);
   store=ctor.newInstance(app,"probe.db",clock);sf.set(null,store);
   Object learnedSelection=oc.getMethod("select",String.class,java.util.List.class,java.util.List.class).invoke(offline,code,texts,readings);
   java.util.List<?> learnedFirst=(java.util.List<?>)learnedSelection.getClass().getMethod("getFirstPage").invoke(learnedSelection);
   java.util.List<String> learnedTexts=new java.util.ArrayList<>();
   for(Object c:learnedFirst)learnedTexts.add((String)c.getClass().getMethod("getText").invoke(c));
   oc.getMethod("select",String.class,java.util.List.class,java.util.List.class).invoke(offline,code,texts,readings);
   java.util.List<?> saved=(java.util.List<?>)sc.getMethod("learned",String.class).invoke(store,parts[2]);
   long savedCount=(Long)saved.get(0).getClass().getMethod("getCount").invoke(saved.get(0));
   org.json.JSONObject row=new org.json.JSONObject(); row.put("code",code);
   row.put("learnedAt",parts[2]);row.put("afterThreeAndReopen",new org.json.JSONArray(learnedTexts.subList(0,Math.min(5,learnedTexts.size()))));row.put("learnedRank",learnedTexts.indexOf(parts[1]));row.put("savedCount",savedCount);
   ((android.database.sqlite.SQLiteDatabase)sc.getMethod("getWritableDatabase").invoke(store)).execSQL("DELETE FROM learned_input");

   row.put("nativeTop5",new org.json.JSONArray(texts.subList(0,Math.min(5,texts.size()))));
   row.put("afterTop5",new org.json.JSONArray(filtered.subList(0,Math.min(5,filtered.size()))));
   org.json.JSONObject ranks=new org.json.JSONObject();
   for(String target:parts[1].split("\\|"))ranks.put(target,new org.json.JSONArray(new int[]{texts.indexOf(target),filtered.indexOf(target)}));
   row.put("ranks",ranks); row.put("nativeCount",texts.size()); row.put("afterCount",filtered.size());
   row.put("nativeFirstRemoved",!texts.isEmpty()&&!filtered.contains(texts.get(0)));
   out.println(row.toString()); count++;
   if(count%100==0){out.flush();System.out.println("PROGRESS="+count+" MS="+(System.currentTimeMillis()-started));}
  }
  }
  System.out.println("DONE="+count+" MS="+(System.currentTimeMillis()-started));
  sc.getMethod("close").invoke(store);
  call(r,"exitRime",new Class[]{});
 }
}
