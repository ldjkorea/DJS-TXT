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
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JsResult;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
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
    private String mPendingTitle = null;
    private String mPendingContent = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 상단 상태바 색상 무광 흑연 동기화 (#121316)
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

        // 웹뷰 자바스크립트 브릿지 등록 (인앱 업데이트 확인 및 버전 조회 연동)
        mWebView.addJavascriptInterface(new Object() {
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
        }, "AndroidBridge");

        // 온라인 여부 확인 후 로드 (온라인이면 항상 최신 GitHub Pages, 오프라인이면 내장 번들)
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

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                mPageLoaded = true;
                deliverPendingFile();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                // 주 URL 로드 실패 시 내장 오프라인 번들로 자동 전환
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
        deliverPendingFile();
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
            mPendingTitle = "공유된 텍스트.txt";
            mPendingContent = directText;
        }
    }

    private void readTextFromUri(final Uri uri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String fileName = resolveFileName(uri);
                    InputStream is = getContentResolver().openInputStream(uri);
                    if (is == null) return;

                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] temp = new byte[8192];
                    int read;
                    while ((read = is.read(temp)) != -1) {
                        buffer.write(temp, 0, read);
                    }
                    is.close();
                    byte[] bytes = buffer.toByteArray();

                    String text = decodeText(bytes);
                    mPendingTitle = fileName;
                    mPendingContent = text;

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            deliverPendingFile();
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

        // UTF-8 디코딩 검증
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // 실패 시 한국어 레거시 인코딩(EUC-KR / MS949) 폴백
            try {
                return new String(bytes, Charset.forName("EUC-KR"));
            } catch (Exception ex) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    private void deliverPendingFile() {
        if (!mPageLoaded || mPendingContent == null || mWebView == null) return;
        final String title = mPendingTitle != null ? mPendingTitle : "가져온 텍스트.txt";
        final String content = mPendingContent;
        mPendingTitle = null;
        mPendingContent = null;

        try {
            String jsonTitle = JSONObject.quote(title);
            String jsonContent = JSONObject.quote(content);
            String script = "if (window.importExternalTxt) { window.importExternalTxt(" + jsonTitle + ", " + jsonContent + "); }";
            mWebView.evaluateJavascript(script, null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to deliver text to webview", e);
        }
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
