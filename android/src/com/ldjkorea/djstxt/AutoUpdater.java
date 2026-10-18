package com.ldjkorea.djstxt;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.security.MessageDigest;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class AutoUpdater {
    private static final String TAG = "AutoUpdater";
    private static final String VERSION_URL = "https://raw.githubusercontent.com/ldjkorea/DJS-TXT/main/version.json";
    public static final int CURRENT_VERSION_CODE = 220;
    public static final String CURRENT_VERSION_NAME = "2.2.0";

    private static final AtomicBoolean checking=new AtomicBoolean();
    private static final AtomicBoolean downloading=new AtomicBoolean();
    public static void check(final Activity activity, final boolean isManual) {
        if (activity == null || activity.isFinishing() || !checking.compareAndSet(false,true)) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(VERSION_URL + "?t=" + System.currentTimeMillis());
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setRequestMethod("GET");

                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();

                        JSONObject json = new JSONObject(sb.toString());
                        final int remoteCode = json.optInt("versionCode", 0);
                        final String remoteName = json.optString("versionName", "");
                        final String notes = json.optString("releaseNotes", "새로운 버전이 준비되었습니다.");
                        final String apkUrl = json.optString("apkUrl", "");
                        final String sha256=json.optString("sha256","").toLowerCase(java.util.Locale.ROOT);

                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (activity.isFinishing()) return;

                                if (remoteCode > CURRENT_VERSION_CODE && apkUrl.length() > 0) {
                                    if(!sha256.matches("[0-9a-f]{64}")||!allowedUrl(apkUrl)){postToast(activity,"업데이트 무결성 정보가 없어 설치를 중단했습니다");return;}
                                    showUpdatePrompt(activity, remoteName, notes, apkUrl,sha256,remoteCode);
                                } else if (isManual) {
                                    Toast.makeText(activity, "현재 최신 버전(v" + CURRENT_VERSION_NAME + ")입니다.", Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    } else if (isManual) {
                        postToast(activity, "업데이트 서버 응답 실패 (" + responseCode + ")");
                    }
                } catch (final Exception e) {
                    Log.d(TAG, "Update check failed: " + e.getMessage());
                    if (isManual) {
                        postToast(activity, "업데이트 확인 실패: 네트워크 연결을 확인하세요.");
                    }
                } finally {
                    if (conn != null) conn.disconnect();
                    checking.set(false);
                }
            }
        }).start();
    }

    private static void postToast(final Activity activity, final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (!activity.isFinishing()) {
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private static void showUpdatePrompt(final Activity activity, final String versionName, final String releaseNotes, final String apkUrl,final String sha256,final int expectedVersion) {
        new AlertDialog.Builder(activity)
                .setTitle("DJS TXT 새 버전 발견 (v" + versionName + ")")
                .setMessage(releaseNotes + "\n\n지금 자동으로 다운로드하여 설치하시겠습니까?")
                .setPositiveButton("지금 업데이트", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startDownloadAndInstall(activity, apkUrl,sha256,expectedVersion);
                    }
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    private static void startDownloadAndInstall(final Activity activity, final String downloadUrl,final String sha256,final int expectedVersion) {
        if(!downloading.compareAndSet(false,true))return;
        final ProgressDialog progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle("DJS TXT 업데이트 다운로드");
        progressDialog.setMessage("최신 설치 파일을 다운로드하고 있습니다...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setProgress(0);
        progressDialog.setCancelable(false);
        progressDialog.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                InputStream in = null;
                FileOutputStream out = null;
                try {
                    if(!allowedUrl(downloadUrl))throw new Exception("허용되지 않은 다운로드 주소");
                    URL url = new URL(downloadUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.connect();

                    int responseCode = conn.getResponseCode();
                    // 리다이렉트(302, 301) 대응
                    if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
                        String newUrl = conn.getHeaderField("Location");
                        conn.disconnect();
                        if(!allowedUrl(newUrl))throw new Exception("허용되지 않은 이동 주소");
                        url = new URL(newUrl);
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(10000);conn.setReadTimeout(15000);
                        conn.connect();
                        responseCode = conn.getResponseCode();
                    }

                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        throw new Exception("HTTP " + responseCode);
                    }

                    final int fileLength = conn.getContentLength();
                    File destDir = new File(activity.getFilesDir(),"updates");
                    if(!destDir.isDirectory()&&!destDir.mkdirs())throw new Exception("업데이트 폴더 생성 실패");
                    final File apkFile = new File(destDir, "DJS_TXT_update.apk");
                    if (apkFile.exists()) apkFile.delete();

                    in = conn.getInputStream();
                    out = new FileOutputStream(apkFile);

                    byte[] data = new byte[8192];
                    long total = 0;
                    int count;
                    int lastProgress = 0;

                    while ((count = in.read(data)) != -1) {
                        total += count;
                        if(total>100L*1024*1024)throw new Exception("APK 크기 제한 초과");
                        out.write(data, 0, count);
                        if (fileLength > 0) {
                            final int currentProgress = (int) ((total * 100) / fileLength);
                            if (currentProgress != lastProgress) {
                                lastProgress = currentProgress;
                                new Handler(Looper.getMainLooper()).post(new Runnable() {
                                    @Override
                                    public void run() {
                                        progressDialog.setProgress(currentProgress);
                                    }
                                });
                            }
                        }
                    }
                    out.flush();out.close();out=null;
                    if(fileLength>0&&total!=fileLength)throw new Exception("불완전한 다운로드");
                    validateApk(activity,apkFile,sha256,expectedVersion);
                    activity.getPreferences(Activity.MODE_PRIVATE).edit().putString("pending_apk_sha256",sha256).putInt("pending_apk_version",expectedVersion).apply();

                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (progressDialog.isShowing()) progressDialog.dismiss();
                            promptInstall(activity, apkFile);
                        }
                    });

                } catch (final Exception e) {
                    Log.e(TAG, "Download error", e);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (progressDialog.isShowing()) progressDialog.dismiss();
                            Toast.makeText(activity, "다운로드 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    try {
                        if (out != null) out.close();
                        if (in != null) in.close();
                    } catch (Exception ignored) {}
                    if (conn != null) conn.disconnect();
                    downloading.set(false);
                }
            }
        }).start();
    }

    static boolean allowedUrl(String value){
        try{URL u=new URL(value);return "https".equals(u.getProtocol())&&"raw.githubusercontent.com".equals(u.getHost())&&u.getPort()==-1&&u.getPath().startsWith("/ldjkorea/DJS-TXT/main/")&&u.getPath().matches("/ldjkorea/DJS-TXT/main/DJS_TXT_v[0-9.]+\\.apk");}catch(Exception e){return false;}
    }
    private static void validateApk(Activity activity,File file,String sha256,int version)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(FileInputStream in=new FileInputStream(file)){byte[] bytes=new byte[16384];int count;while((count=in.read(bytes))!=-1)digest.update(bytes,0,count);}
        StringBuilder actual=new StringBuilder();for(byte b:digest.digest())actual.append(String.format("%02x",b&255));
        if(!actual.toString().equals(sha256))throw new Exception("APK 해시 불일치");
        PackageManager pm=activity.getPackageManager();
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo incoming=pm.getPackageArchiveInfo(file.getAbsolutePath(),flags),installed=pm.getPackageInfo(activity.getPackageName(),flags);
        if(incoming==null||!activity.getPackageName().equals(incoming.packageName)||incoming.versionCode!=version||version<=CURRENT_VERSION_CODE)throw new Exception("패키지 또는 버전 불일치");
        Signature[] a=Build.VERSION.SDK_INT>=28?incoming.signingInfo.getApkContentsSigners():incoming.signatures;
        Signature[] b=Build.VERSION.SDK_INT>=28?installed.signingInfo.getApkContentsSigners():installed.signatures;
        if(a==null||b==null||a.length!=b.length||!new java.util.HashSet<Signature>(Arrays.asList(a)).equals(new java.util.HashSet<Signature>(Arrays.asList(b))))throw new Exception("서명 인증서 불일치");
    }
    public static void resumeInstall(final Activity activity){
        final android.content.SharedPreferences prefs=activity.getPreferences(Activity.MODE_PRIVATE);
        final String hash=prefs.getString("pending_apk_sha256",null);final int version=prefs.getInt("pending_apk_version",0);
        if(hash==null)return;
        if(version<=CURRENT_VERSION_CODE){prefs.edit().remove("pending_apk_sha256").remove("pending_apk_version").apply();return;}
        if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls())return;
        prefs.edit().remove("pending_apk_sha256").remove("pending_apk_version").apply();
        final File file=new File(new File(activity.getFilesDir(),"updates"),"DJS_TXT_update.apk");
        new Thread(()->{try{validateApk(activity,file,hash,version);activity.runOnUiThread(()->promptInstall(activity,file));}catch(Exception e){postToast(activity,"설치 재개 실패: "+e.getMessage());}}).start();
    }

    private static void promptInstall(final Activity activity, final File apkFile) {
        if (activity.isFinishing()) return;

        // Android 8.0+ 출처를 알 수 없는 앱 설치 권한 검사
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(activity)
                        .setTitle("설치 권한 필요")
                        .setMessage("원활한 자동 업데이트를 위해 DJS TXT의 '출처를 알 수 없는 앱 설치'를 허용해 주세요.")
                        .setPositiveButton("설정하기", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                                    intent.setData(Uri.parse("package:" + activity.getPackageName()));
                                    activity.startActivity(intent);
                                } catch (Exception e) {
                                    Log.e(TAG, "Cannot open unknown source settings", e);
                                }
                            }
                        })
                        .setNegativeButton("취소", (dialog,which)->activity.getPreferences(Activity.MODE_PRIVATE).edit().remove("pending_apk_sha256").remove("pending_apk_version").apply())
                        .show();
                return;
            }
        }

        try {
            activity.getPreferences(Activity.MODE_PRIVATE).edit().remove("pending_apk_sha256").remove("pending_apk_version").apply();
            Uri apkUri = Uri.parse("content://" + ApkFileProvider.AUTHORITY + "/" + apkFile.getName());
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(installIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch package installer", e);
            Toast.makeText(activity, "설치 화면 호출 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
