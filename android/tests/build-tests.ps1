param([Parameter(Mandatory=$true)][string]$SdkRoot,[string]$JdkHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
if(-not $JdkHome){$JdkHome=Split-Path -Parent (Split-Path -Parent (Get-Command javac).Source)}
$androidRoot=Split-Path -Parent $PSScriptRoot
$build=Join-Path $androidRoot ('.qa\test-builds\'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$bt=Join-Path $SdkRoot 'build-tools\35.0.0'
$jar=Join-Path $SdkRoot 'platforms\android-34\android.jar'
New-Item -ItemType Directory -Path "$build\classes","$build\dex","$build\assets" | Out-Null
Copy-Item -Path "$PSScriptRoot\*.js" -Destination "$build\assets"
function Checked([string]$command,[object[]]$arguments){& $command @arguments;if($LASTEXITCODE -ne 0){throw "Failed: $command"}}
Checked "$bt\aapt2.exe" @('link','-I',$jar,'--manifest',"$PSScriptRoot\AndroidManifest.xml",'-A',"$build\assets",'-o',"$build\unsigned.apk")
Checked "$JdkHome\bin\javac.exe" @('-encoding','UTF-8','-source','8','-target','8','-cp',$jar,'-d',"$build\classes","$PSScriptRoot\SmokeInstrumentation.java")
$classes=@(Get-ChildItem "$build\classes" -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName)
Checked "$bt\d8.bat" (@('--min-api','24','--output',"$build\dex",'--lib',$jar)+$classes)
Checked "$JdkHome\bin\jar.exe" @('uf',"$build\unsigned.apk",'-C',"$build\dex",'classes.dex')
Checked "$bt\zipalign.exe" @('-p','4',"$build\unsigned.apk","$build\aligned.apk")
if(-not $env:DJS_TXT_STORE_PASSWORD){throw 'DJS_TXT_STORE_PASSWORD is required'}
Checked "$bt\apksigner.bat" @('sign','--ks',"$androidRoot\release.keystore",'--ks-pass','env:DJS_TXT_STORE_PASSWORD','--out',"$build\tests.apk","$build\aligned.apk")
Checked "$bt\apksigner.bat" @('verify',"$build\tests.apk")
Write-Output "TEST_APK_PATH=$build\tests.apk"
