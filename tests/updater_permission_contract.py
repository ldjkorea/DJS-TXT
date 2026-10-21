"""Host-only contract tests for the real AutoUpdater source. No Android SDK/device/network.

Run: python tests/updater_permission_contract.py [--revision 2026ec2]
Requires Python 3 and JDK 21 on PATH. Android surfaces are deliberately small fakes;
this verifies Java branching/intents, not Android settings/installer UI or persistence.
"""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA_PATH = "android/src/com/ldjkorea/djstxt/AutoUpdater.java"
MAIN_PATH = "android/src/com/ldjkorea/djstxt/MainActivity.java"

STUBS = {
    "android/content/SharedPreferences.java": """
package android.content;
import java.util.*;
public class SharedPreferences {
 public final Map<String,Object> values = new HashMap<>();
 public synchronized String getString(String k,String d){return (String)values.getOrDefault(k,d);}
 public synchronized int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);}
 public Editor edit(){return new Editor();}
 public class Editor {
  final Map<String,Object> writes=new HashMap<>(); final Set<String> removes=new HashSet<>();
  public Editor putString(String k,String v){writes.put(k,v);return this;}
  public Editor putInt(String k,int v){writes.put(k,v);return this;}
  public Editor remove(String k){removes.add(k);return this;}
  public void apply(){synchronized(SharedPreferences.this){removes.forEach(values::remove);values.putAll(writes);}}
 }
}
""",
    "android/app/Activity.java": """
package android.app;
import android.content.*; import android.content.pm.*; import android.os.*; import java.io.*; import java.util.*;
public class Activity {
 public static final int MODE_PRIVATE=0;
 public SharedPreferences prefs=new SharedPreferences(); public PackageManager pm=new PackageManager();
 public File files; public boolean finishing; public AlertDialog dialog;
 public final List<Intent> intents=new ArrayList<>(); public final List<String> toasts=new ArrayList<>();
 public boolean isFinishing(){return finishing;}
 public SharedPreferences getPreferences(int mode){return prefs;}
 public PackageManager getPackageManager(){return pm;}
 public String getPackageName(){return "com.ldjkorea.djstxt";}
 public File getFilesDir(){return files;}
 public void runOnUiThread(Runnable r){Handler.queue.add(r);}
 public void startActivity(Intent i){intents.add(i);}
}
""",
    "android/app/AlertDialog.java": """
package android.app;
import android.content.DialogInterface;
public class AlertDialog implements DialogInterface {
 public String title,message; public DialogInterface.OnClickListener positive,negative;
 public static class Builder {
  final Activity a; final AlertDialog d=new AlertDialog();
  public Builder(Activity a){this.a=a;}
  public Builder setTitle(String s){d.title=s;return this;}
  public Builder setMessage(String s){d.message=s;return this;}
  public Builder setPositiveButton(String s,DialogInterface.OnClickListener l){d.positive=l;return this;}
  public Builder setNegativeButton(String s,DialogInterface.OnClickListener l){d.negative=l;return this;}
  public AlertDialog show(){a.dialog=d;return d;}
 }
}
""",
    "android/content/DialogInterface.java": """
package android.content;
public interface DialogInterface {interface OnClickListener{void onClick(DialogInterface d,int which);}}
""",
    "android/app/ProgressDialog.java": """
package android.app;
public class ProgressDialog {
 public static final int STYLE_HORIZONTAL=1;
 public ProgressDialog(Activity a){} public void setTitle(String s){} public void setMessage(String s){}
 public void setProgressStyle(int i){} public void setMax(int i){} public void setProgress(int i){}
 public void setCancelable(boolean b){} public void show(){} public boolean isShowing(){return false;} public void dismiss(){}
}
""",
    "android/content/Intent.java": """
package android.content;
import android.net.Uri;
public class Intent {
 public static final String ACTION_VIEW="android.intent.action.VIEW";
 public static final int FLAG_GRANT_READ_URI_PERMISSION=1,FLAG_ACTIVITY_NEW_TASK=0x10000000;
 public final String action; public Uri data; public String type; public int flags;
 public Intent(String action){this.action=action;}
 public Intent setData(Uri u){data=u;return this;}
 public Intent setDataAndType(Uri u,String t){data=u;type=t;return this;}
 public Intent addFlags(int f){flags|=f;return this;}
}
""",
    "android/net/Uri.java": """
package android.net;
public record Uri(String value){public static Uri parse(String s){return new Uri(s);}public String toString(){return value;}}
""",
    "android/os/Build.java": """
package android.os;
public class Build {public static class VERSION{public static int SDK_INT=34;}public static class VERSION_CODES{public static final int O=26;}}
""",
    "android/os/Handler.java": """
package android.os;
import java.util.concurrent.ConcurrentLinkedQueue;
public class Handler {
 public static final ConcurrentLinkedQueue<Runnable> queue=new ConcurrentLinkedQueue<>();
 public Handler(Looper l){} public boolean post(Runnable r){queue.add(r);return true;}
 public static void drain(){Runnable r;while((r=queue.poll())!=null)r.run();}
}
""",
    "android/os/Looper.java": "package android.os; public class Looper{public static Looper getMainLooper(){return new Looper();}}",
    "android/provider/Settings.java": "package android.provider; public class Settings{public static final String ACTION_MANAGE_UNKNOWN_APP_SOURCES=\"android.settings.MANAGE_UNKNOWN_APP_SOURCES\";}",
    "android/util/Log.java": "package android.util; public class Log{public static int d(String t,String m){return 0;}public static int e(String t,String m,Throwable e){return 0;}}",
    "android/widget/Toast.java": """
package android.widget;
import android.app.Activity;
public class Toast {
 public static final int LENGTH_SHORT=0,LENGTH_LONG=1; final Activity a;final String s;
 Toast(Activity a,String s){this.a=a;this.s=s;}
 public static Toast makeText(Activity a,String s,int duration){return new Toast(a,s);}
 public void show(){a.toasts.add(s);}
}
""",
    "android/content/pm/Signature.java": "package android.content.pm; public record Signature(String value){}",
    "android/content/pm/SigningInfo.java": """
package android.content.pm;
public class SigningInfo{public Signature[] signers={new Signature("synthetic-certificate")};public Signature[] getApkContentsSigners(){return signers;}}
""",
    "android/content/pm/PackageInfo.java": """
package android.content.pm;
public class PackageInfo{public String packageName="com.ldjkorea.djstxt";public int versionCode;public SigningInfo signingInfo=new SigningInfo();public Signature[] signatures=signingInfo.signers;}
""",
    "android/content/pm/PackageManager.java": """
package android.content.pm;
public class PackageManager {
 public static final int GET_SIGNING_CERTIFICATES=1,GET_SIGNATURES=2;
 public boolean allowed; public final PackageInfo incoming=new PackageInfo(),installed=new PackageInfo();
 public boolean canRequestPackageInstalls(){return allowed;}
 public PackageInfo getPackageArchiveInfo(String path,int flags){return incoming;}
 public PackageInfo getPackageInfo(String name,int flags){return installed;}
}
""",
    "org/json/JSONObject.java": """
package org.json;
public class JSONObject {
 public JSONObject(String s){throw new AssertionError("Metadata/network path is outside this host test");}
 public int optInt(String k,int d){return d;}public String optString(String k,String d){return d;}
}
""",
    "com/ldjkorea/djstxt/ApkFileProvider.java": "package com.ldjkorea.djstxt; public class ApkFileProvider{public static final String AUTHORITY=\"com.ldjkorea.djstxt.fileprovider\";}",
}


def source(path, revision):
    if revision:
        return subprocess.check_output(
            ["git", "show", f"{revision}:{path}"], cwd=ROOT, encoding="utf-8"
        )
    return (ROOT / path).read_text(encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revision", help="Compile the tracked source at this Git revision")
    args = parser.parse_args()
    updater = source(JAVA_PATH, args.revision)
    activity = source(MAIN_PATH, args.revision)
    provider = source("android/src/com/ldjkorea/djstxt/ApkFileProvider.java", args.revision)
    authority = re.search(r'public static final String AUTHORITY\s*=\s*"([^"]+)"', provider)
    assert authority and authority[1] == "com.ldjkorea.djstxt.fileprovider", "Provider fake must match the real authority"
    # A host fake cannot run a real Android lifecycle; guard the actual integration too.
    resume = activity.split("protected void onResume()", 1)[1].split("@Override", 1)[0]
    assert "AutoUpdater.resumeInstall(this)" in resume, "MainActivity must resume the installer"
    with tempfile.TemporaryDirectory(prefix="djs-txt-updater-host-") as scratch:
        root = Path(scratch)
        for name, content in STUBS.items():
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        (root / "com/ldjkorea/djstxt/AutoUpdater.java").write_text(updater, encoding="utf-8")
        harness = ROOT / "tests/java/UpdaterPermissionContract.java"
        classes = root / "classes"
        subprocess.run(
            ["javac", "-encoding", "UTF-8", "-d", str(classes),
             *map(str, root.rglob("*.java")), str(harness)], check=True, timeout=30
        )
        subprocess.run(
            ["java", "-cp", str(classes), "com.ldjkorea.djstxt.UpdaterPermissionContract", str(root)],
            check=True, timeout=30
        )
    print(f"PASS: MainActivity resume hook; source={args.revision or 'working tree'}", flush=True)


if __name__ == "__main__":
    main()
