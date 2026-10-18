package com.ldjkorea.djstxt.tests;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs only on the dedicated emulator. Never clears app storage. */
public class SmokeInstrumentation extends Instrumentation {
    private Bundle arguments;
    public class AuditWriter {
        @android.webkit.JavascriptInterface public String save(String json)throws Exception {
            java.io.File folder=new java.io.File(getTargetContext().getExternalFilesDir(null),"preservation");
            if(!folder.isDirectory()&&!folder.mkdirs())throw new Exception("Backup directory unavailable");
            java.io.File file=new java.io.File(folder,"snapshot-"+System.currentTimeMillis()+".json");
            byte[] bytes=json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){out.write(bytes);out.getFD().sync();}
            JSONObject data=new JSONObject(json),books=data.getJSONObject("books");int bookmarks=0;
            java.util.Iterator<String> keys=books.keys();while(keys.hasNext()){org.json.JSONArray marks=books.getJSONObject(keys.next()).optJSONArray("bookmarks");if(marks!=null)bookmarks+=marks.length();}
            JSONObject result=new JSONObject();result.put("ok",true);result.put("phase","audit");result.put("books",books.length());result.put("bookmarks",bookmarks);result.put("bytes",bytes.length);result.put("file",file.getAbsolutePath());return result.toString();
        }
    }
    @Override public void onCreate(Bundle args){arguments=args;start();}
    private WebView find(View view){if(view instanceof WebView)return (WebView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){WebView found=find(group.getChildAt(i));if(found!=null)return found;}}return null;}
    private String evaluate(WebView web,String script)throws Exception{
        CountDownLatch latch=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<String>();
        runOnMainSync(()->web.evaluateJavascript(script,value->{result.set(value);latch.countDown();}));
        if(!latch.await(15,TimeUnit.SECONDS))throw new Exception("JS timeout");return result.get();
    }
    @Override public void onStart(){Bundle report=new Bundle();try{
        String phase=arguments.getString("phase","verify");
        if(!android.os.Build.FINGERPRINT.contains("generic")&&!android.os.Build.MODEL.contains("sdk_gphone")&&!"audit".equals(phase))throw new Exception("Synthetic tests are restricted to the emulator");
        Intent intent=new Intent();intent.setClassName("com.ldjkorea.djstxt","com.ldjkorea.djstxt.MainActivity");intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity=startActivitySync(intent);WebView web=find(activity.getWindow().getDecorView());if(web==null)throw new Exception("WebView missing");
        if("seed-file".equals(phase)||"audit".equals(phase)){
            long readyDeadline=System.currentTimeMillis()+30000;
            while(!"true".equals(evaluate(web,"typeof Meta!=='undefined'&&location.protocol==='https:'"))){if(System.currentTimeMillis()>readyDeadline)throw new Exception("Initial app bootstrap timeout");Thread.sleep(100);}
        }
        final CountDownLatch fileLoaded=new CountDownLatch(1);
        if("seed-file".equals(phase))runOnMainSync(()->{
            activity.getPreferences(Activity.MODE_PRIVATE).edit().putBoolean("legacy_file_copied_v1",false).putBoolean("legacy_file_verified_v2",false).commit();
            web.getSettings().setAllowFileAccess(true);web.setWebViewClient(new android.webkit.WebViewClient(){@Override public void onPageFinished(WebView view,String url){if("file:///android_asset/index.html".equals(url))fileLoaded.countDown();}});web.loadUrl("file:///android_asset/index.html");
        });
        if("seed-file".equals(phase)&&!fileLoaded.await(20,TimeUnit.SECONDS))throw new Exception("File-origin page load timeout");
        if("audit".equals(phase))runOnMainSync(()->{web.addJavascriptInterface(new AuditWriter(),"QaAudit");web.reload();});
        if("probe".equals(phase)){
            String root=java.util.Arrays.toString(activity.getAssets().list("")),js=java.util.Arrays.toString(activity.getAssets().list("js"));
            String result="root="+root+" js="+js;
            try(java.io.InputStream in=activity.getAssets().open("js/app.js")){result+=" read="+in.read();}catch(Exception e){result+=" error="+e;}
            report.putString("stream",result);finish(Activity.RESULT_OK,report);return;
        }
        long deadline=System.currentTimeMillis()+45000;
        String ready="typeof Meta!=='undefined'&&typeof DB!=='undefined'"+("audit".equals(phase)?"&&typeof QaAudit!=='undefined'":"")+("seed-file".equals(phase)?"&&location.protocol==='file:'":"");
        while(!"true".equals(evaluate(web,ready))){if(System.currentTimeMillis()>deadline)throw new Exception("App not ready: "+evaluate(web,"JSON.stringify({url:location.href,scripts:Array.from(document.scripts).map(s=>s.src),styles:Array.from(document.styleSheets).map(s=>s.href),resources:performance.getEntriesByType('resource').map(r=>({name:r.name,size:r.transferSize,duration:r.duration}))})"));Thread.sleep(200);}
        String file=phase+".js";
        byte[] bytes;try(java.io.InputStream in=getContext().getAssets().open(file);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);bytes=out.toByteArray();}
        if("audit".equals(phase))evaluate(web,"window.__qaExpectedLegacy="+activity.getPreferences(Activity.MODE_PRIVATE).getString("legacy_file_expected","{}")+";");
        evaluate(web,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        String result;
        do{Thread.sleep(200);result=evaluate(web,"window.__qaResult||null");if(System.currentTimeMillis()>deadline)throw new Exception("Test timeout");}while("null".equals(result));
        String plain=new org.json.JSONArray("["+result+"]").getString(0);JSONObject json=new JSONObject(plain);
        if("legacy-merge".equals(phase)&&!json.optBoolean("ok")){
            java.lang.reflect.Field field=activity.getClass().getDeclaredField("legacyWebView");field.setAccessible(true);WebView legacy=(WebView)field.get(activity);
            json.put("migrationComplete",activity.getPreferences(Activity.MODE_PRIVATE).getBoolean("legacy_file_copied_v1",false));
            json.put("legacyView",legacy==null?"none":evaluate(legacy,"JSON.stringify({url:location.href,ready:document.readyState,meta:typeof Meta,books:typeof Meta!=='undefined'?Object.keys(Meta.books()).length:-1,bridge:typeof LegacyReadBridge})"));
            plain=json.toString();
        }
        if("seed-file".equals(phase)&&json.optBoolean("ok"))runOnMainSync(()->activity.getPreferences(Activity.MODE_PRIVATE).edit().putBoolean("legacy_file_copied_v1",false).putBoolean("legacy_file_verified_v2",false).commit());
        report.putString("stream",plain+"\n");finish(json.optBoolean("ok")?Activity.RESULT_OK:Activity.RESULT_CANCELED,report);
    }catch(Exception e){report.putString("stream",e.toString());finish(Activity.RESULT_CANCELED,report);}}
}
