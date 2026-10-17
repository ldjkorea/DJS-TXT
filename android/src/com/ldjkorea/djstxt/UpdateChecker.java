package com.ldjkorea.djstxt;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {
    private static final String TAG = "UpdateChecker";
    private static final String VERSION_URL = "https://raw.githubusercontent.com/ldjkorea/txtreader/main/version.json";
    public static final int CURRENT_VERSION_CODE = 1;
    public static final String CURRENT_VERSION_NAME = "1.0.0";

    public static void checkForUpdates(final Activity activity) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(VERSION_URL + "?t=" + System.currentTimeMillis());
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestMethod("GET");

                    if (conn.getResponseCode() == 200) {
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
                        final String notes = json.optString("releaseNotes", "새로운 버전이 출시되었습니다.");
                        final String apkUrl = json.optString("apkUrl", "");

                        if (remoteCode > CURRENT_VERSION_CODE && apkUrl.length() > 0) {
                            new Handler(Looper.getMainLooper()).post(new Runnable() {
                                @Override
                                public void run() {
                                    if (activity == null || activity.isFinishing()) return;
                                    new AlertDialog.Builder(activity)
                                            .setTitle("DJS TXT 업데이트 알림 (v" + remoteName + ")")
                                            .setMessage(notes + "\n\n새 버전을 다운로드하시겠습니까?")
                                            .setPositiveButton("다운로드", new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface dialog, int which) {
                                                    try {
                                                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl));
                                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                                        activity.startActivity(intent);
                                                    } catch (Exception e) {
                                                        Log.e(TAG, "Cannot open download URL", e);
                                                    }
                                                }
                                            })
                                            .setNegativeButton("나중에", null)
                                            .show();
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Update check failed or offline: " + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }
}
