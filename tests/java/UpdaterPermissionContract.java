package com.ldjkorea.djstxt;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Runs the production AutoUpdater against fake Android surfaces, never a device. */
public class UpdaterPermissionContract {
    static final String HASH = "pending_apk_sha256", VERSION = "pending_apk_version";
    static final byte[] APK = "synthetic APK bytes; package metadata is supplied by fake PM".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static int cases, networkAttempts;
    static Path root;

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static Activity fixture() throws Exception {
        Build.VERSION.SDK_INT = 34;
        Activity a = new Activity();
        a.files = Files.createTempDirectory(root, "fixture-").toFile();
        Path update = a.files.toPath().resolve("updates/DJS_TXT_update.apk");
        Files.createDirectories(update.getParent());
        Files.write(update, APK);
        Files.writeString(a.files.toPath().resolve("synthetic-reader-canary"), "unchanged");
        a.prefs.edit().putString("synthetic-reader-canary", "unchanged").apply();
        a.pm.incoming.versionCode = AutoUpdater.CURRENT_VERSION_CODE + 1;
        a.pm.installed.versionCode = AutoUpdater.CURRENT_VERSION_CODE;
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(APK));
        a.prefs.edit().putString(HASH, hash).putInt(VERSION, a.pm.incoming.versionCode).apply();
        return a;
    }

    static void prompt(Activity a) throws Exception {
        Method m = AutoUpdater.class.getDeclaredMethod("promptInstall", Activity.class, File.class);
        m.setAccessible(true);
        m.invoke(null, a, new File(a.files, "updates/DJS_TXT_update.apk"));
    }

    static boolean pending(Activity a) { return a.prefs.getString(HASH, null) != null; }

    static void waitFor(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            Handler.drain();
            Thread.sleep(2);
        }
        Handler.drain();
        require(done.getAsBoolean(), "Background validation did not finish");
    }

    static void installIntent(Activity a) {
        require(a.intents.size() == 1, "Exactly one installer intent expected");
        Intent i = a.intents.get(0);
        require(Intent.ACTION_VIEW.equals(i.action), "Installer ACTION_VIEW");
        require("content://com.ldjkorea.djstxt.fileprovider/DJS_TXT_update.apk".equals(i.data.toString()), "Private provider URI");
        require("application/vnd.android.package-archive".equals(i.type), "APK MIME");
        require((i.flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0, "Read grant");
        require(!pending(a) && a.prefs.getInt(VERSION, -1) == -1, "Pending state consumed");
    }

    static void pass(String name, Activity a) throws Exception {
        require("unchanged".equals(a.prefs.getString("synthetic-reader-canary", null)), "Unrelated preferences changed");
        require("unchanged".equals(Files.readString(a.files.toPath().resolve("synthetic-reader-canary"))), "Unrelated file changed");
        require(networkAttempts == 0, "Permission/resume must reuse the local APK without network");
        System.out.println("PASS " + (++cases) + ": " + name);
    }

    static void rejected(String name, java.util.function.Consumer<Activity> mutate) throws Exception {
        Activity a = fixture();
        a.pm.allowed = true;
        mutate.accept(a);
        AutoUpdater.resumeInstall(a);
        waitFor(() -> !a.toasts.isEmpty());
        require(a.intents.isEmpty(), "Invalid APK must never reach installer");
        require(a.toasts.get(0).startsWith("설치 재개 실패:"), "Visible validation failure");
        pass(name, a);
    }

    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]);
        // A regression into download/check fails locally, without any actual request.
        URL.setURLStreamHandlerFactory(protocol -> new URLStreamHandler() {
            protected URLConnection openConnection(URL u) throws IOException {
                networkAttempts++;
                throw new IOException("Network forbidden in permission contract tests");
            }
        });

        Activity a = fixture();
        prompt(a);
        require(a.dialog != null && a.intents.isEmpty() && pending(a), "Permission prompt keeps pending APK");
        pass("first permission prompt preserves pending state", a);

        a.dialog.positive.onClick(a.dialog, 1);
        require(a.intents.size() == 1, "Settings launched once");
        require(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES.equals(a.intents.get(0).action), "Per-app settings action");
        require("package:com.ldjkorea.djstxt".equals(a.intents.get(0).data.toString()), "Per-app settings URI");
        require(pending(a), "Opening settings keeps pending state");
        a.intents.clear();
        pass("settings action and package URI", a);

        AutoUpdater.resumeInstall(a);
        AutoUpdater.resumeInstall(a);
        require(pending(a) && a.intents.isEmpty() && Handler.queue.isEmpty(), "Denied return keeps pending; no loop");
        pass("denied/back return waits without downloading or reopening settings", a);

        a.pm.allowed = true;
        AutoUpdater.resumeInstall(a);
        AutoUpdater.resumeInstall(a);
        Activity granted = a;
        waitFor(() -> !granted.intents.isEmpty());
        AutoUpdater.resumeInstall(a);
        Handler.drain();
        installIntent(a);
        require(Arrays.equals(APK, Files.readAllBytes(a.files.toPath().resolve("updates/DJS_TXT_update.apk"))), "Local APK reused");
        pass("later allowed return validates local APK and launches once", a);

        a = fixture();
        prompt(a);
        a.dialog.negative.onClick(a.dialog, -1);
        a.pm.allowed = true;
        AutoUpdater.resumeInstall(a);
        require(!pending(a) && a.intents.isEmpty(), "Cancel must not install on later resume");
        require(Files.exists(a.files.toPath().resolve("updates/DJS_TXT_update.apk")), "Cancel does not delete downloaded APK");
        pass("permission-dialog cancel clears only the pending request", a);

        a = fixture();
        a.pm.allowed = true;
        prompt(a);
        installIntent(a);
        pass("already allowed path launches directly", a);

        Activity old = fixture();
        Activity recreated = new Activity();
        recreated.files = old.files;
        recreated.prefs = old.prefs; // Simulate restored prefs, not Android disk persistence.
        recreated.pm = old.pm;
        recreated.pm.allowed = true;
        AutoUpdater.resumeInstall(recreated);
        waitFor(() -> !recreated.intents.isEmpty());
        installIntent(recreated);
        pass("activity recreation can use restored pending state", recreated);

        a = fixture();
        a.prefs.edit().putInt(VERSION, AutoUpdater.CURRENT_VERSION_CODE).apply();
        AutoUpdater.resumeInstall(a);
        require(!pending(a) && a.intents.isEmpty(), "Obsolete pending request removed before permission gate");
        pass("already installed version clears stale request", a);

        a = fixture();
        a.prefs.edit().remove(HASH).remove(VERSION).apply();
        AutoUpdater.resumeInstall(a);
        require(a.intents.isEmpty() && Handler.queue.isEmpty(), "No pending request is a no-op");
        pass("ordinary resume without pending APK", a);

        rejected("hash mismatch rejected after permission return", x -> x.prefs.edit().putString(HASH, "0".repeat(64)).apply());
        rejected("wrong package rejected after permission return", x -> x.pm.incoming.packageName = "other.package");
        rejected("wrong version rejected after permission return", x -> x.pm.incoming.versionCode++);
        rejected("wrong signing certificate rejected after permission return", x -> x.pm.incoming.signingInfo.signers = new Signature[]{new Signature("wrong")});
        rejected("missing local APK rejected after permission return", x -> require(new File(x.files, "updates/DJS_TXT_update.apk").delete(), "Remove synthetic fixture"));

        a = fixture();
        Build.VERSION.SDK_INT = 25;
        AutoUpdater.resumeInstall(a);
        Activity pre26 = a;
        waitFor(() -> !pre26.intents.isEmpty());
        installIntent(a);
        pass("pre-26 path uses legacy signatures without source permission gate", a);
        System.out.println("PASS: " + cases + " host-only cases; code=" + AutoUpdater.CURRENT_VERSION_CODE + "; network attempts=" + networkAttempts);
    }
}
