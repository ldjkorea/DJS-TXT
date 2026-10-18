param(
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$OutputDirectory,
    [switch]$Unsigned,
    [switch]$TestDebuggable
)
$ErrorActionPreference='Stop'
$baseDir=$PSScriptRoot
$webRoot=Split-Path -Parent $baseDir
if(-not $JdkHome) { $JdkHome=Split-Path -Parent (Split-Path -Parent (Get-Command javac -ErrorAction Stop).Source) }
$androidJar=Join-Path $SdkRoot 'platforms\android-34\android.jar'
$buildTools=Join-Path $SdkRoot 'build-tools\35.0.0'
foreach($required in @($androidJar,"$JdkHome\bin\javac.exe","$buildTools\aapt2.exe","$buildTools\d8.bat","$buildTools\zipalign.exe","$buildTools\apksigner.bat")) {
    if(-not (Test-Path -LiteralPath $required)) { throw "필수 도구를 찾을 수 없습니다: $required" }
}
if(-not $OutputDirectory) { $OutputDirectory=Join-Path $baseDir ('.qa\builds\'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')) }
if(Test-Path -LiteralPath $OutputDirectory) { throw "기존 출력 폴더는 덮어쓰지 않습니다: $OutputDirectory" }
$buildDir=[IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path "$buildDir\gen","$buildDir\classes","$buildDir\dex","$buildDir\assets" | Out-Null
function Invoke-Checked([string]$Program,[object[]]$Arguments) {
    & $Program @Arguments
    if($LASTEXITCODE -ne 0) { throw "도구 실행 실패(exit $LASTEXITCODE): $Program" }
}
foreach($asset in @('index.html','manifest.json','sw.js','icon-192.png','icon-512.png','css','js','fonts')) {
    Copy-Item -LiteralPath (Join-Path $webRoot $asset) -Destination "$buildDir\assets" -Recurse
}
$manifest=Get-Content -LiteralPath "$baseDir\AndroidManifest.xml" -Raw -Encoding UTF8
if($TestDebuggable) { $manifest=$manifest.Replace('<application','<application android:debuggable="true"') }
[IO.File]::WriteAllText("$buildDir\AndroidManifest.xml",$manifest,[Text.UTF8Encoding]::new($false))
[xml]$xml=$manifest
$version=$xml.manifest.GetAttribute('versionName','http://schemas.android.com/apk/res/android')
Invoke-Checked "$buildTools\aapt2.exe" @('compile','--dir',"$baseDir\res",'-o',"$buildDir\compiled_res.zip")
Invoke-Checked "$buildTools\aapt2.exe" @('link','-I',$androidJar,'--manifest',"$buildDir\AndroidManifest.xml",'-o',"$buildDir\unsigned.apk",'-A',"$buildDir\assets","$buildDir\compiled_res.zip",'--java',"$buildDir\gen",'--auto-add-overlay')
$sources=@(Get-ChildItem -LiteralPath "$baseDir\src","$buildDir\gen" -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
Invoke-Checked "$JdkHome\bin\javac.exe" (@('-encoding','UTF-8','-source','8','-target','8','-cp',$androidJar,'-d',"$buildDir\classes")+$sources)
$classes=@(Get-ChildItem -LiteralPath "$buildDir\classes" -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName)
Invoke-Checked "$buildTools\d8.bat" (@('--min-api','24','--output',"$buildDir\dex",'--lib',$androidJar)+$classes)
Invoke-Checked "$JdkHome\bin\jar.exe" @('uf',"$buildDir\unsigned.apk",'-C',"$buildDir\dex",'classes.dex')
# Some Windows aapt2/JAR combinations emit backslashes in asset entry names.
# Android treats those as literal filenames, so normalize before alignment/signing.
$inputZip=[IO.Compression.ZipFile]::OpenRead("$buildDir\unsigned.apk")
$outputZip=[IO.Compression.ZipFile]::Open("$buildDir\normalized.apk",[IO.Compression.ZipArchiveMode]::Create)
try {
    foreach($entry in $inputZip.Entries) {
        $name=$entry.FullName.Replace('\','/')
        $level=if($name -eq 'resources.arsc' -or $entry.CompressedLength -eq $entry.Length){[IO.Compression.CompressionLevel]::NoCompression}else{[IO.Compression.CompressionLevel]::Optimal}
        $newEntry=$outputZip.CreateEntry($name,$level)
        $source=$entry.Open();$destination=$newEntry.Open()
        try{$source.CopyTo($destination)}finally{$source.Dispose();$destination.Dispose()}
    }
}finally{$inputZip.Dispose();$outputZip.Dispose()}
Invoke-Checked "$buildTools\zipalign.exe" @('-p','4',"$buildDir\normalized.apk","$buildDir\aligned.apk")
if($Unsigned) { Write-Output "UNSIGNED_APK=$buildDir\aligned.apk"; exit 0 }
$keystore=Join-Path $baseDir 'release.keystore'
if(-not (Test-Path -LiteralPath $keystore)) { throw '기존 서명키가 없습니다. 새 키를 자동 생성하지 않습니다.' }
if(-not $env:DJS_TXT_STORE_PASSWORD) { throw 'DJS_TXT_STORE_PASSWORD 환경 변수에 기존 서명키 비밀번호를 지정하세요.' }
if(-not $env:DJS_TXT_KEY_PASSWORD) { $env:DJS_TXT_KEY_PASSWORD=$env:DJS_TXT_STORE_PASSWORD }
$suffix=if($TestDebuggable){'-qa'}else{''}
$apk=Join-Path $buildDir "DJS_TXT_v$version$suffix.apk"
Invoke-Checked "$buildTools\apksigner.bat" @('sign','--ks',$keystore,'--ks-pass','env:DJS_TXT_STORE_PASSWORD','--key-pass','env:DJS_TXT_KEY_PASSWORD','--out',$apk,"$buildDir\aligned.apk")
$certOutput=& "$buildTools\apksigner.bat" verify --print-certs $apk
if($LASTEXITCODE -ne 0) {throw 'APK 서명 검증 실패'}
$expected='484e8813318127829d507b8d1e5e62ab89077d05af3904de2795b1b8382e419f'
if(-not ($certOutput -match "Signer #1 certificate SHA-256 digest: $expected")) {throw '기존 APK와 서명 인증서가 다릅니다'}
Write-Output "APK_PATH=$apk"
Write-Output ('APK_SHA256='+(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant())
