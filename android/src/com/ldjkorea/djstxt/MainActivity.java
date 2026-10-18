package com.ldjkorea.djstxt;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.ServiceWorkerController;
import android.webkit.ServiceWorkerClient;
import android.content.ActivityNotFoundException;
import android.content.pm.ApplicationInfo;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.util.Locale;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String TAG = "DjsTxtMain";
    private static final String REMOTE_URL = "https://ldjkorea.github.io/DJS-TXT/";
    private static final String BOOTSTRAP_URL = "https://ldjkorea.github.io/DJS-TXT-bootstrap/";
    private static final String BOOTSTRAP_HTML = "<!doctype html><meta charset='utf-8'><script>(async()=>{try{if(navigator.serviceWorker){const rs=await navigator.serviceWorker.getRegistrations();await Promise.all(rs.filter(r=>r.scope===location.origin+'/DJS-TXT/').map(r=>r.unregister()));}}finally{AndroidBridge.openBundledReader();}})();</script>";
    private static final String LOCAL_ASSET_URL = "file:///android_asset/index.html";

    private WebView mWebView;
    private boolean mPageLoaded = false;
    private final AndroidBridge mBridge = new AndroidBridge();
    private long mLastVolumeEventTime = 0;
    private static final int PICK_FILES=101, SAVE_BACKUP=102;
    private ValueCallback<Uri[]> fileCallback;
    private String backupToSave;
    private WebView legacyWebView;
    private boolean legacyStarted=false;
    private boolean bootstrapFinished=false;
    private static final int MAX_TEXT_BYTES=50*1024*1024;
    private static final Set<String> ASSETS=new HashSet<String>(Arrays.asList("index.html","manifest.json","sw.js","css/app.css","js/app.js","js/data.js","js/reader.js","icon-192.png","icon-512.png","fonts/RIDIBatang.otf","fonts/PretendardVariable.woff2","fonts/OFL.txt","fonts/RIDIBatang-LICENSE.txt","fonts/Pretendard-LICENSE.txt"));

    public class AndroidBridge {
        private final ArrayDeque<String> pendingFiles = new ArrayDeque<String>();
        private long pendingCharacters=0;
        private volatile boolean mVolumeKeyEnabled = true;
        private volatile boolean mReaderActive = false;
        public synchronized void setPending(String title,String content) { setPending(title,content,"txt"); }
        public synchronized void setPending(String title,String content,String kind) {
            try {
                if(pendingFiles.size()>=20||pendingCharacters+content.length()>30L*1024*1024) throw new IllegalStateException("너무 많은 파일이 동시에 도착했습니다");
                JSONObject file=new JSONObject();file.put("title",title);file.put("content",content);file.put("kind",kind);String serialized=file.toString();pendingFiles.add(serialized);pendingCharacters+=serialized.length();
            } catch(org.json.JSONException e){throw new IllegalArgumentException(e);}
        }
        @JavascriptInterface public synchronized boolean hasPendingFile(){return !pendingFiles.isEmpty();}
        @JavascriptInterface public synchronized String pullPendingFile(){String file=pendingFiles.poll();if(file!=null)pendingCharacters-=file.length();return file;}
        @JavascriptInterface public void saveBackup(final String name,final String json) {
            runOnUiThread(()->{
                if(backupToSave!=null){Toast.makeText(MainActivity.this,"다른 백업을 저장 중입니다",Toast.LENGTH_SHORT).show();return;}
                backupToSave=json;
                Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");intent.putExtra(Intent.EXTRA_TITLE,name);
                try{startActivityForResult(intent,SAVE_BACKUP);}catch(ActivityNotFoundException e){backupToSave=null;Toast.makeText(MainActivity.this,"파일 저장 앱을 찾을 수 없습니다",Toast.LENGTH_LONG).show();}
            });
        }
        @JavascriptInterface public void legacyMigrationComplete(){runOnUiThread(()->finishLegacyRead());}
        @JavascriptInterface public void openBundledReader(){runOnUiThread(()->{if(!bootstrapFinished){bootstrapFinished=true;mWebView.loadUrl(REMOTE_URL);}});}
        @JavascriptInterface public void finishLegacyMigration(){runOnUiThread(()->{
            if(legacyStarted||getPreferences(MODE_PRIVATE).getBoolean("legacy_file_verified_v2",false))return;
            String expected=getPreferences(MODE_PRIVATE).getString("legacy_file_expected",null);
            if(getPreferences(MODE_PRIVATE).getBoolean("legacy_file_copied_v1",false)&&expected!=null){
                mWebView.evaluateJavascript("window.hasLegacyRecords("+expected+").then(ok=>AndroidBridge.legacyStatus(ok)).catch(()=>AndroidBridge.legacyStatus(false))",null);
            }else {getPreferences(MODE_PRIVATE).edit().putBoolean("legacy_file_copied_v1",false).commit();MainActivity.this.recoverLegacyLibrary();}
        });}
        @JavascriptInterface public void legacyStatus(boolean present){runOnUiThread(()->{
            if(present)getPreferences(MODE_PRIVATE).edit().putBoolean("legacy_file_verified_v2",true).remove("legacy_file_expected").commit();
            else {getPreferences(MODE_PRIVATE).edit().putBoolean("legacy_file_copied_v1",false).commit();MainActivity.this.recoverLegacyLibrary();}
        });}
        // An explicit deletion accepts the visible library, so migration cannot resurrect it.
        @JavascriptInterface public void acceptLegacyLibrary(){getPreferences(MODE_PRIVATE).edit().putBoolean("legacy_file_verified_v2",true).remove("legacy_file_expected").commit();}
        @JavascriptInterface public void recoverLegacyLibrary(){runOnUiThread(()->MainActivity.this.recoverLegacyLibrary());}

        @JavascriptInterface
        public void setVolumeKeyEnabled(boolean enabled) {
            this.mVolumeKeyEnabled = enabled;
        }

        @JavascriptInterface
        public boolean isVolumeKeyEnabled() {
            return mVolumeKeyEnabled;
        }

        @JavascriptInterface
        public void setReaderActive(boolean active) {
            this.mReaderActive = active;
        }

        @JavascriptInterface
        public boolean isReaderActive() {
            return mReaderActive;
        }

        @JavascriptInterface
        public void setKeepScreenOn(final boolean keep) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Window window = getWindow();
                    if (window != null) {
                        if (keep) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public int getBatteryLevel() {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
                    if (bm != null) {
                        int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                        if (level >= 0 && level <= 100) return level;
                    }
                }
            } catch (Exception ignored) {}
            return -1;
        }

        @JavascriptInterface
        public void checkUpdate() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    AutoUpdater.check(MainActivity.this, true);
                }
            });
        }

        @JavascriptInterface
        public String getVersionName() {
            return AutoUpdater.CURRENT_VERSION_NAME;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 상단 상태바 및 내비게이션 바 색상 무광 흑연 동기화 (#121316)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.parseColor("#121316"));
            window.setNavigationBarColor(Color.parseColor("#121316"));
        }

        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.parseColor("#121316"));
        setContentView(layout);

        mWebView = new WebView(this);
        mWebView.setBackgroundColor(Color.parseColor("#121316"));
        layout.addView(mWebView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        initWebSettings();

        // 초기 인텐트 처리
        handleIntent(getIntent());

        // Keep the existing HTTPS origin and its IndexedDB/localStorage on every launch.
        // App assets are served by shouldInterceptRequest even when disconnected.
        // Leave the old service-worker scope before loading bundled assets. Otherwise
        // its already-running controller can return a stale/empty cached response.
        mWebView.loadUrl(BOOTSTRAP_URL);

        // 백그라운드 APK 버전 자동 업데이트 체크
        AutoUpdater.check(this, false);
    }

    private void initWebSettings() {
        WebSettings ws = mWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)!=0);
        ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request){return assetResponse(request.getUrl());}
        });

        mWebView.addJavascriptInterface(mBridge, "AndroidBridge");

        mWebView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return assetResponse(request.getUrl());}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                Uri uri=request.getUrl();
                if(trusted(uri)||BOOTSTRAP_URL.equals(uri.toString()))return false;
                if("https".equals(uri.getScheme())){try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(Exception ignored){}}
                return true;
            }
            @Override public void onPageFinished(WebView view,String url) {
                if(!trusted(Uri.parse(url)))return;
                mPageLoaded=true;
                // Remove only regenerable caches; books and settings remain untouched.
                view.evaluateJavascript("if(navigator.serviceWorker){navigator.serviceWorker.getRegistrations().then(rs=>Promise.all(rs.filter(r=>r.scope===location.origin+'/DJS-TXT/').map(r=>r.unregister())));}",null);
                notifyNativeFileReady();
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params) {
                if(fileCallback!=null)fileCallback.onReceiveValue(null);
                fileCallback=callback;
                Intent intent=params.createIntent();intent.setAction(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try{startActivityForResult(intent,PICK_FILES);}catch(ActivityNotFoundException e){fileCallback.onReceiveValue(null);fileCallback=null;Toast.makeText(MainActivity.this,"파일 선택 앱을 찾을 수 없습니다",Toast.LENGTH_LONG).show();}
                return true;
            }
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, "[JS] " + cm.message() + " (" + cm.sourceId() + ":" + cm.lineNumber() + ")");
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                return super.onJsAlert(view, url, message, result);
            }
        });
    }

    private static boolean trusted(Uri uri){return "https".equals(uri.getScheme())&&"ldjkorea.github.io".equals(uri.getHost())&&uri.getPath()!=null&&uri.getPath().startsWith("/DJS-TXT/");}
    private WebResourceResponse assetResponse(Uri uri) {
        if(BOOTSTRAP_URL.equals(uri.toString()))return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(BOOTSTRAP_HTML.getBytes(StandardCharsets.UTF_8)));
        if(!trusted(uri))return new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",null,new ByteArrayInputStream(new byte[0]));
        String path=uri.getPath().substring("/DJS-TXT/".length());if(path.isEmpty())path="index.html";
        if(!ASSETS.contains(path))return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",null,new ByteArrayInputStream(new byte[0]));
        try {
            String mime=path.endsWith(".html")?"text/html":path.endsWith(".css")?"text/css":path.endsWith(".js")?"application/javascript":path.endsWith(".json")?"application/json":path.endsWith(".otf")?"font/otf":path.endsWith(".woff2")?"font/woff2":"image/png";
            java.util.Map<String,String> headers=new java.util.HashMap<String,String>();headers.put("Content-Type",mime+((mime.startsWith("text/")||mime.contains("javascript")||mime.contains("json"))?"; charset=utf-8":""));headers.put("Cache-Control","no-store");headers.put("X-Content-Type-Options","nosniff");
            byte[] bytes;
            try(InputStream input=getAssets().open(path);ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);bytes=output.toByteArray();
            }
            headers.put("Content-Length",String.valueOf(bytes.length));
            if((getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0)Log.d(TAG,"Bundled asset "+path+" bytes="+bytes.length);
            return new WebResourceResponse(mime,mime.startsWith("font/")||mime.equals("image/png")?null:"UTF-8",200,"OK",headers,new ByteArrayInputStream(bytes));
        }catch(Exception e){Log.e(TAG,"Asset unavailable: "+path,e);return new WebResourceResponse("text/plain","UTF-8",500,"Asset Error",null,new ByteArrayInputStream(new byte[0]));}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==PICK_FILES&&fileCallback!=null) {
            Uri[] uris=null;
            if(result==RESULT_OK&&data!=null){if(data.getClipData()!=null){int count=data.getClipData().getItemCount();uris=new Uri[count];for(int i=0;i<count;i++)uris[i]=data.getClipData().getItemAt(i).getUri();}else if(data.getData()!=null)uris=new Uri[]{data.getData()};}
            fileCallback.onReceiveValue(uris);fileCallback=null;
        } else if(request==SAVE_BACKUP) {
            final String json=backupToSave;backupToSave=null;
            if(result==RESULT_OK&&data!=null&&data.getData()!=null&&json!=null) {
                final Uri uri=data.getData();
                new Thread(()->{try(OutputStream stream=getContentResolver().openOutputStream(uri,"wt")){if(stream==null)throw new java.io.IOException("저장 위치를 열 수 없습니다");stream.write(json.getBytes(StandardCharsets.UTF_8));runOnUiThread(()->mWebView.evaluateJavascript("window.onNativeBackupSaved?.(true)",null));}catch(Exception e){runOnUiThread(()->{Toast.makeText(this,"백업 저장 실패: "+e.getMessage(),Toast.LENGTH_LONG).show();mWebView.evaluateJavascript("window.onNativeBackupSaved?.(false)",null);});}}).start();
            }else mWebView.evaluateJavascript("window.onNativeBackupSaved?.(false)",null);
        }
    }
    private void recoverLegacyLibrary() {
        if(legacyStarted||getPreferences(MODE_PRIVATE).getBoolean("legacy_file_copied_v1",false))return;
        legacyStarted=true;
        // Read the old file origin without removing or rewriting any old records.
        legacyWebView=new WebView(this);legacyWebView.getSettings().setJavaScriptEnabled(true);legacyWebView.getSettings().setDomStorageEnabled(true);legacyWebView.getSettings().setDatabaseEnabled(true);legacyWebView.getSettings().setAllowFileAccess(true);
        legacyWebView.addJavascriptInterface(new Object(){
            @JavascriptInterface public void copied(final String json){runOnUiThread(()->{
                try{getPreferences(MODE_PRIVATE).edit().putString("legacy_file_expected",new JSONObject(json).getJSONObject("books").toString()).commit();}catch(Exception e){return;}
                mWebView.evaluateJavascript("window.restoreLibraryData("+json+",{migration:true}).then(()=>window.AndroidBridge.legacyMigrationComplete()).catch(()=>{})",null);
            });}
            @JavascriptInterface public void empty(){runOnUiThread(()->{getPreferences(MODE_PRIVATE).edit().putString("legacy_file_expected","{}").commit();finishLegacyRead();});}
        },"LegacyReadBridge");
        legacyWebView.setWebChromeClient(new WebChromeClient(){@Override public boolean onConsoleMessage(ConsoleMessage message){Log.d(TAG,"Legacy reader: "+message.message());return true;}});
        legacyWebView.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !LOCAL_ASSET_URL.equals(r.getUrl().toString());}
            @Override public void onPageFinished(WebView v,String url){
                if(!LOCAL_ASSET_URL.equals(url))return;
                v.evaluateJavascript("(async()=>{try{const books=Meta.books();if(!Object.keys(books).length){LegacyReadBridge.empty();return;}const contents={};for(const id of Object.keys(books)){const text=await DB.get(id);if(text===null)throw Error('missing');contents[id]=text;}LegacyReadBridge.copied(JSON.stringify({version:3,books,contents,cfg:Meta.cfg(),lastBook:Meta.lastBook()}));}catch(e){console.warn('Legacy library kept intact: '+e.message);}})()",null);
            }
        });
        legacyWebView.loadUrl(LOCAL_ASSET_URL);
    }
    private void finishLegacyRead(){getPreferences(MODE_PRIVATE).edit().putBoolean("legacy_file_copied_v1",true).apply();if(legacyWebView!=null){legacyWebView.destroy();legacyWebView=null;}}

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        String directText = null;

        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                uri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
            if (uri == null) {
                CharSequence textSeq = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (textSeq != null) directText = textSeq.toString();
            }
        }

        if (uri != null) {
            readTextFromUri(uri);
        } else if (directText != null && directText.length() > 0) {
            mBridge.setPending("공유된 텍스트.txt", directText);
            notifyNativeFileReady();
        }
    }

    private void readTextFromUri(final Uri uri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String fileName = resolveFileName(uri);
                    InputStream is = null;

                    // 1. ContentResolver 통해 열기
                    try {
                        is = getContentResolver().openInputStream(uri);
                    } catch (Exception e) {
                        Log.w(TAG, "ContentResolver.openInputStream failed: " + e.getMessage());
                    }

                    // 2. 만약 file:// 스킴이고 실패했다면 직접 File 열기 시도
                    if (is == null && "file".equalsIgnoreCase(uri.getScheme())) {
                        String path = uri.getPath();
                        if (path != null) {
                            File f = new File(path);
                            if (f.exists() && f.canRead()) {
                                is = new FileInputStream(f);
                            }
                        }
                    }

                    if (is == null) {
                        Log.e(TAG, "Cannot open stream for URI: " + uri);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "파일을 읽을 수 없습니다. (권한 또는 경로 오류)", Toast.LENGTH_SHORT).show();
                            }
                        });
                        return;
                    }

                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] temp = new byte[16384];
                    int read;
                    try {
                        while ((read = is.read(temp)) != -1) {
                            if(buffer.size()+read>MAX_TEXT_BYTES)throw new java.io.IOException("TXT 파일은 50MB까지 열 수 있습니다");
                            buffer.write(temp, 0, read);
                        }
                    } finally { is.close(); }
                    byte[] bytes = buffer.toByteArray();

                    String text = decodeText(bytes);
                    mBridge.setPending(fileName, text);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            notifyNativeFileReady();
                        }
                    });
                } catch (OutOfMemoryError e) {
                    runOnUiThread(()->Toast.makeText(MainActivity.this,"메모리가 부족합니다. 앱의 가져오기에서 파일을 선택하거나 더 작은 TXT를 사용해 주세요.",Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    Log.e(TAG, "Failed to read text from URI", e);
                    runOnUiThread(()->Toast.makeText(MainActivity.this,"파일 읽기 실패: "+e.getMessage(),Toast.LENGTH_LONG).show());
                }
            }
        }).start();
    }

    private String resolveFileName(Uri uri) {
        String name = null;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    name = cursor.getString(0);
                }
            } catch (Exception ignored) {
            } finally {
                if (cursor != null) cursor.close();
            }
        }
        if (name == null || name.length() == 0) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.length() == 0) {
            name = "가져온 텍스트.txt";
        }
        return name;
    }

    private String decodeText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";

        // UTF-8 BOM 감지 (EF BB BF)
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }

        // UTF-16 LE BOM 감지 (FF FE)
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }

        // UTF-16 BE BOM 감지 (FE FF)
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }

        // UTF-8 디코딩 검증
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // 실패 시 한국어 확장 완성형(MS949) 폴백 (현대 한글 11,172자 완벽 수용)
            try {
                return new String(bytes, Charset.forName("MS949"));
            } catch (Exception ex) {
                try {
                    return new String(bytes, Charset.forName("EUC-KR"));
                } catch (Exception ex2) {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            }
        }
    }

    private void notifyNativeFileReady() {
        if (!mPageLoaded || mWebView == null || !mBridge.hasPendingFile()) return;
        mWebView.evaluateJavascript("if (window.onNativeFileReady) { window.onNativeFileReady(); }", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        int keyCode = event.getKeyCode();

        if (mBridge.isReaderActive() && mBridge.isVolumeKeyEnabled()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (action == KeyEvent.ACTION_DOWN) {
                    long now = System.currentTimeMillis();
                    if (now - mLastVolumeEventTime >= 160) {
                        mLastVolumeEventTime = now;
                        final String dir = (keyCode == KeyEvent.KEYCODE_VOLUME_UP) ? "up" : "down";
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (mWebView != null) {
                                    mWebView.evaluateJavascript("if (window.onVolumeKey) { window.onVolumeKey('" + dir + "'); }", null);
                                }
                            }
                        });
                    }
                }
                return true; // 시스템 볼륨 HUD 표시 차단
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onResume(){super.onResume();AutoUpdater.resumeInstall(this);if(mWebView!=null){mWebView.onResume();mWebView.evaluateJavascript("if(typeof syncKeepScreen==='function')syncKeepScreen()",null);}}
    @Override protected void onPause(){getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(mWebView!=null){mWebView.evaluateJavascript("window.dispatchEvent(new Event('blur'))",null);mWebView.onPause();}super.onPause();}
    @Override protected void onDestroy(){if(fileCallback!=null)fileCallback.onReceiveValue(null);if(legacyWebView!=null)legacyWebView.destroy();if(mWebView!=null)mWebView.destroy();super.onDestroy();}
    @Override
    public void onBackPressed() {
        if (mWebView != null) {
            // 웹뷰 내에서 독서 화면이 열려있으면 서재로 복귀, 설정창 열려있으면 닫기, 서재면 앱 종료
            mWebView.evaluateJavascript(
                    "(function() {" +
                    "  var sheet = document.getElementById('sheet');" +
                    "  if (sheet && (sheet.classList.contains('open') || sheet.classList.contains('show'))) {" +
                    "    var overlay = document.getElementById('overlay'); if (overlay) overlay.click();" +
                    "    return 'consumed';" +
                    "  }" +
                    "  var reader = document.getElementById('reader');" +
                    "  if (reader && reader.classList.contains('show')) {" +
                    "    var btnHome = document.getElementById('btn-home'); if (btnHome) btnHome.click();" +
                    "    return 'consumed';" +
                    "  }" +
                    "  return 'exit';" +
                    "})()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (!"\"consumed\"".equals(value)) {
                                MainActivity.super.onBackPressed();
                            }
                        }
                    }
            );
        } else {
            super.onBackPressed();
        }
    }
}
