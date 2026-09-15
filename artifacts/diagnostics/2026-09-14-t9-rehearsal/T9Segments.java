import android.content.Context;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.zip.*;
import java.io.*;
public class T9Segments {
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
  Class<?> kc=Class.forName("com.yuyan.inputmethod.data.KeyRecordStack");
  for(String[] spec:new String[][]{{"94363362","真的","吗"},{"9267426548","玩","漂流"}}) {
   call(r,"clearRimeComposition",new Class[]{});
   Object stack=kc.getConstructor().newInstance();
   for(char d:spec[0].toCharArray()) {
    char key="ADGJMPTW".charAt(d-'2');
    android.view.KeyEvent e=new android.view.KeyEvent(0,0,android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_A+key-'A',0,android.view.KeyEvent.META_SHIFT_ON);
    kc.getMethod("pushKey",android.view.KeyEvent.class).invoke(stack,e);
    call(r,"processRimeKey",new Class[]{int.class,int.class},(int)key,0);
   }
   org.json.JSONObject row=new org.json.JSONObject();row.put("code",spec[0]);
   row.put("beforeSelectionCode",kc.getMethod("unlockedT9Digits").invoke(stack));
   for(int stage=1;stage<=2;stage++) {
    int index=-1, scannedPages=0;
    for(int attempt=0;attempt<20;attempt++) {
     Object context=call(r,"getRimeContext",new Class[]{});
     Object menu=context.getClass().getMethod("getMenu").invoke(context);
     int pageNo=(Integer)menu.getClass().getMethod("getPageNo").invoke(menu);
     Object[] cands=(Object[])context.getClass().getMethod("getCandidates").invoke(context);
     scannedPages++;
     for(int i=0;i<cands.length;i++)if(spec[stage].equals(cands[i].getClass().getMethod("getText").invoke(cands[i]))){index=pageNo*100+i;break;}
     if(index>=0||(Boolean)menu.getClass().getMethod("isLastPage").invoke(menu))break;
     int down=(Integer)call(r,"getRimeKeycodeByName",new Class[]{String.class},"Page_Down");
     call(r,"processRimeKey",new Class[]{int.class,int.class},down,0);
    }
    row.put("stage"+stage+"ScannedPages",scannedPages);
    row.put("stage"+stage+"Index",index);
    if(index<0)break;
    row.put("stage"+stage+"LearningCode",kc.getMethod("unlockedT9Digits").invoke(stack));
    call(r,"selectRimeCandidate",new Class[]{int.class},index);
    kc.getMethod("pushCandidateSelectAction").invoke(stack);
    Object commit=call(r,"getRimeCommit",new Class[]{});
    row.put("stage"+stage+"Commit",commit==null?org.json.JSONObject.NULL:commit.toString());
   }
   row.put("afterSelectionCode",kc.getMethod("unlockedT9Digits").invoke(stack));
   System.out.println("SEGMENT="+row.toString());
  }
  call(r,"exitRime",new Class[]{});
 }
}
