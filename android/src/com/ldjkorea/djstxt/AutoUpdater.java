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
    public static final int CURRENT_VERSION_CODE = 210;
    public static final String CURRENT_VERSION_NAME = "2.1.0";

    public static void check(final Activity activity, final boolean isManual) {
        if (activity == null || activity.isFinishing()) return;

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

                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (activity.isFinishing()) return;

                                if (remoteCode > CURRENT_VERSION_CODE && apkUrl.length() > 0) {
                                    showUpdatePrompt(activity, remoteName, notes, apkUrl);
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

    private static void showUpdatePrompt(final Activity activity, final String versionName, final String releaseNotes, final String apkUrl) {
        new AlertDialog.Builder(activity)
                .setTitle("DJS TXT 새 버전 발견 (v" + versionName + ")")
                .setMessage(releaseNotes + "\n\n지금 자동으로 다운로드하여 설치하시겠습니까?")
                .setPositiveButton("지금 업데이트", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startDownloadAndInstall(activity, apkUrl);
                    }
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    private static void startDownloadAndInstall(final Activity activity, final String downloadUrl) {
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
                    URL url = new URL(downloadUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.connect();

                    int responseCode = conn.getResponseCode();
                    // 리다이렉트(302, 301) 대응
                    if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
                        String newUrl = conn.getHeaderField("Location");
                        conn.disconnect();
                        url = new URL(newUrl);
                        conn = (HttpURLConnection) url.openConnection();
                        conn.connect();
                        responseCode = conn.getResponseCode();
                    }

                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        throw new Exception("HTTP " + responseCode);
                    }

                    final int fileLength = conn.getContentLength();
                    File destDir = activity.getExternalFilesDir(null);
                    if (destDir == null) destDir = activity.getFilesDir();
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
                    out.flush();

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
                }
            }
        }).start();
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
                        .setNegativeButton("취소", null)
                        .show();
                return;
            }
        }

        try {
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
