$ErrorActionPreference = "Stop"

$TOOLS = "C:\Users\c\Documents\Codex\2026-09-14\tools"
$ANDROID_JAR = "$TOOLS\android-sdk\platforms\android-34\android.jar"
$BUILD_TOOLS = "$TOOLS\android-sdk\build-tools\35.0.0"
$JDK_BIN = "$TOOLS\jdk\jdk-21.0.12.1+1\bin"

$AAPT2 = "$BUILD_TOOLS\aapt2.exe"
$D8 = "$BUILD_TOOLS\d8.bat"
$ZIPALIGN = "$BUILD_TOOLS\zipalign.exe"
$APKSIGNER = "$BUILD_TOOLS\apksigner.bat"
$JAVAC = "$JDK_BIN\javac.exe"
$JAR = "$JDK_BIN\jar.exe"
$KEYTOOL = "$JDK_BIN\keytool.exe"

$BASE_DIR = "C:\Users\c\.gemini\antigravity\scratch\txtreader\android"
Set-Location $BASE_DIR

$BUILD_DIR = "$BASE_DIR\build"
if (Test-Path $BUILD_DIR) { Remove-Item -Recurse -Force $BUILD_DIR }
New-Item -ItemType Directory -Path "$BUILD_DIR\gen" | Out-Null
New-Item -ItemType Directory -Path "$BUILD_DIR\classes" | Out-Null
New-Item -ItemType Directory -Path "$BUILD_DIR\dex" | Out-Null

Write-Host "==> 0. Syncing web assets into android/assets..."
$WEB_ROOT = "C:\Users\c\.gemini\antigravity\scratch\txtreader"
Copy-Item -Force "$WEB_ROOT\index.html" "$BASE_DIR\assets\index.html"
Copy-Item -Force "$WEB_ROOT\manifest.json" "$BASE_DIR\assets\manifest.json"
Copy-Item -Force "$WEB_ROOT\sw.js" "$BASE_DIR\assets\sw.js"
Copy-Item -Force -Recurse "$WEB_ROOT\css" "$BASE_DIR\assets\"
Copy-Item -Force -Recurse "$WEB_ROOT\js" "$BASE_DIR\assets\"

Write-Host "==> 1. Compiling resources with aapt2..."
& $AAPT2 compile --dir "$BASE_DIR\res" -o "$BUILD_DIR\compiled_res.zip"

Write-Host "==> 2. Linking APK and generating R.java..."
& $AAPT2 link -I $ANDROID_JAR --manifest "$BASE_DIR\AndroidManifest.xml" -o "$BUILD_DIR\unaligned.apk" -A "$BASE_DIR\assets" "$BUILD_DIR\compiled_res.zip" --java "$BUILD_DIR\gen" --auto-add-overlay

Write-Host "==> 3. Compiling Java sources with javac (Java 8 compatibility)..."
$javaFiles = Get-ChildItem -Path "$BASE_DIR\src", "$BUILD_DIR\gen" -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
& $JAVAC -encoding UTF-8 -source 8 -target 8 -cp $ANDROID_JAR -d "$BUILD_DIR\classes" $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac compilation failed" }

Write-Host "==> 4. Converting bytecode to DEX with d8..."
$classFiles = Get-ChildItem -Path "$BUILD_DIR\classes" -Recurse -Filter "*.class" | Select-Object -ExpandProperty FullName
& $D8 --min-api 24 --output "$BUILD_DIR\dex" --lib $ANDROID_JAR $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 DEX conversion failed" }

$dexFile = "$BUILD_DIR\dex\classes.dex"
if (-not (Test-Path $dexFile)) { throw "classes.dex was not generated!" }
Write-Host "classes.dex generated successfully: $((Get-Item $dexFile).Length) bytes"

Write-Host "==> 5. Adding classes.dex to APK..."
& $JAR uf "$BUILD_DIR\unaligned.apk" -C "$BUILD_DIR\dex" classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar update failed" }

Write-Host "==> 6. Aligning APK with zipalign..."
& $ZIPALIGN -f -p 4 "$BUILD_DIR\unaligned.apk" "$BUILD_DIR\aligned.apk"

Write-Host "==> 7. Ensuring release keystore..."
$KEYSTORE = "$BASE_DIR\release.keystore"
if (-not (Test-Path $KEYSTORE)) {
    & $KEYTOOL -genkeypair -v -keystore $KEYSTORE -alias djstxt -keyalg RSA -keysize 2048 -validity 10000 -storepass djstxtpass -keypass djstxtpass -dname "CN=ldjkorea, OU=DJS, O=DJS, L=Seoul, ST=Seoul, C=KR"
}

Write-Host "==> 8. Signing APK with apksigner..."
$OUTPUT_APK = "$BASE_DIR\DJS_TXT_v2.1.0.apk"
if (Test-Path $OUTPUT_APK) { Remove-Item -Force $OUTPUT_APK }
& $APKSIGNER sign --ks $KEYSTORE --ks-pass pass:djstxtpass --key-pass pass:djstxtpass --out $OUTPUT_APK "$BUILD_DIR\aligned.apk"

Write-Host "==> 9. Verifying signed APK..."
& $APKSIGNER verify -v $OUTPUT_APK

Copy-Item -Force $OUTPUT_APK "$WEB_ROOT\DJS_TXT_v2.1.0.apk"

$apkItem = Get-Item $OUTPUT_APK
Write-Host "==> BUILD SUCCESS!"
Write-Host "APK Path: $($apkItem.FullName)"
Write-Host "Size: $($apkItem.Length) bytes ($([Math]::Round($apkItem.Length / 1KB, 1)) KB)"
