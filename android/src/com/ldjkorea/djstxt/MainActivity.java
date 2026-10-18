package com.ldjkorea.djstxt;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
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
    private static final String LOCAL_ASSET_URL = "file:///android_asset/index.html";

    private WebView mWebView;
    private boolean mPageLoaded = false;
    private final AndroidBridge mBridge = new AndroidBridge();

    public class AndroidBridge {
        private String mPendingTitle = null;
        private String mPendingContent = null;
        private volatile boolean mVolumeKeyEnabled = true;
        private volatile boolean mReaderActive = false;

        public synchronized void setPending(String title, String content) {
            this.mPendingTitle = title;
            this.mPendingContent = content;
        }

        @JavascriptInterface
        public synchronized boolean hasPendingFile() {
            return mPendingContent != null && mPendingContent.length() > 0;
        }

        @JavascriptInterface
        public synchronized String pullPendingTitle() {
            String t = mPendingTitle;
            mPendingTitle = null;
            return t != null ? t : "가져온 텍스트.txt";
        }

        @JavascriptInterface
        public synchronized String pullPendingContent() {
            String c = mPendingContent;
            mPendingContent = null;
            return c != null ? c : "";
        }

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

        // 온라인 여부 확인 후 로드 (온라인이면 최신 GitHub Pages, 오프라인이면 내장 번들)
        if (isOnline()) {
            mWebView.loadUrl(REMOTE_URL);
        } else {
            mWebView.loadUrl(LOCAL_ASSET_URL);
        }

        // 백그라운드 APK 버전 자동 업데이트 체크
        AutoUpdater.check(this, false);
    }

    private void initWebSettings() {
        WebSettings ws = mWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        mWebView.addJavascriptInterface(mBridge, "AndroidBridge");

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                mPageLoaded = true;
                notifyNativeFileReady();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    String reqUrl = request.getUrl().toString();
                    if (reqUrl.startsWith("http")) {
                        Log.w(TAG, "Network failed, falling back to local asset: " + reqUrl);
                        view.loadUrl(LOCAL_ASSET_URL);
                    }
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request != null && request.isForMainFrame() && errorResponse != null && errorResponse.getStatusCode() >= 400) {
                    Log.w(TAG, "HTTP error " + errorResponse.getStatusCode() + ", falling back to local asset");
                    view.loadUrl(LOCAL_ASSET_URL);
                }
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
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

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = cm != null ? cm.getActiveNetworkInfo() : null;
            return netInfo != null && netInfo.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

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
                    while ((read = is.read(temp)) != -1) {
                        buffer.write(temp, 0, read);
                    }
                    is.close();
                    byte[] bytes = buffer.toByteArray();

                    String text = decodeText(bytes);
                    mBridge.setPending(fileName, text);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            notifyNativeFileReady();
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Failed to read text from URI", e);
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
                return true; // 시스템 볼륨 HUD 표시 차단
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null) {
            // 웹뷰 내에서 독서 화면이 열려있으면 서재로 복귀, 설정창 열려있으면 닫기, 서재면 앱 종료
            mWebView.evaluateJavascript(
                    "(function() {" +
                    "  var sheet = document.getElementById('sheet');" +
                    "  if (sheet && sheet.classList.contains('show')) {" +
                    "    var btnClose = document.getElementById('sheet-close'); if (btnClose) btnClose.click();" +
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
