import android.content.Context;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.zip.*;
import java.io.*;
public class T9Transitions {
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
   Object baselineComposition=context.getClass().getMethod("getComposition").invoke(context);
   String beforePreedit=String.valueOf(baselineComposition);
   call(r,"processRimeKey",new Class[]{int.class,int.class},(int)'A',0);
   int back=(Integer)call(r,"getRimeKeycodeByName",new Class[]{String.class},"BackSpace");
   call(r,"processRimeKey",new Class[]{int.class,int.class},back,0);
   Object restored=call(r,"getRimeContext",new Class[]{});
   Object[] restoredCands=(Object[])restored.getClass().getMethod("getCandidates").invoke(restored);
   java.util.List<String> restoredTexts=new java.util.ArrayList<>();
   for(Object c:restoredCands)restoredTexts.add((String)c.getClass().getMethod("getText").invoke(c));
   boolean deleteRestores=texts.equals(restoredTexts)&&beforePreedit.equals(String.valueOf(restored.getClass().getMethod("getComposition").invoke(restored)));
   int down=(Integer)call(r,"getRimeKeycodeByName",new Class[]{String.class},"Page_Down");
   call(r,"processRimeKey",new Class[]{int.class,int.class},down,0);
   Object page=call(r,"getRimeContext",new Class[]{});
   Object menu=page.getClass().getMethod("getMenu").invoke(page);
   int pageNo=(Integer)menu.getClass().getMethod("getPageNo").invoke(menu);
   boolean indexes=true; int visible=0;
   if(pageNo==1){
    Object[] pc=(Object[])page.getClass().getMethod("getCandidates").invoke(page);
    java.util.List<String> pt=new java.util.ArrayList<>(), pr=new java.util.ArrayList<>();
    for(Object c:pc){pt.add((String)c.getClass().getMethod("getText").invoke(c));pr.add((String)c.getClass().getMethod("getComment").invoke(c));}
    java.util.List<?> allowed=(java.util.List<?>)selection.getClass().getMethod("appendNativePage",java.util.List.class,String.class,java.util.List.class).invoke(selection,pt,code,pr);
    for(Object position:allowed){Object ranked=selection.getClass().getMethod("at",int.class).invoke(selection,first.size()+visible++);
     int idx=(Integer)ranked.getClass().getMethod("getNativeIndex").invoke(ranked); indexes&=idx==texts.size()+(Integer)position;
    }
   }
   org.json.JSONObject row=new org.json.JSONObject(); row.put("code",code);
   row.put("deleteRestores",deleteRestores);row.put("pageNo",pageNo);row.put("pagingIndexConsistent",indexes);row.put("visiblePage2",visible);

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
  call(r,"exitRime",new Class[]{});
 }
}
