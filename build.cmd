@echo off
rem Offline build: vendored JRE 17 + Eclipse compiler, no Gradle, no full JDK.
setlocal
set JRE=%~dp0toolchain\jre
set ECJ=%~dp0toolchain\ecj.jar
set API=%~dp0toolchain\libxposed-api.jar
set SDK=C:\Users\ham.zhang\AppData\Local\Android\Sdk
set BT=%SDK%\build-tools\36.0.0
set PLAT=%SDK%\platforms\android-36\android.jar
set SRC=%~dp0src
set OUT=%~dp0build
set PATH=%JRE%\bin;%PATH%
set JAVA_HOME=%JRE%

if not exist "%OUT%\classes" mkdir "%OUT%\classes"
if not exist "%OUT%\dex" mkdir "%OUT%\dex"

rem NOTE: no -bootclasspath here, so ECJ resolves java.* against the JRE 17 platform.
rem Only use APIs that exist in android.jar and on minSdk 29 (no List.of/String.isBlank etc).
echo [1/6] compile
java -Dfile.encoding=UTF-8 -jar "%ECJ%" -8 -encoding UTF-8 -nowarn -classpath "%PLAT%;%API%" -d "%OUT%\classes" ^
  "%SRC%\com\defsw0rd\rm9hp4camfix\CamFixModule.java" ^
  "%SRC%\com\defsw0rd\rm9hp4camfix\CameraBoost.java" ^
  "%SRC%\com\defsw0rd\rm9hp4camfix\FreezeController.java" ^
  "%SRC%\com\defsw0rd\rm9hp4camfix\HoldWindow.java" ^
  "%SRC%\com\defsw0rd\rm9hp4camfix\FixSettings.java" ^
  "%SRC%\com\defsw0rd\rm9hp4camfix\MainActivity.java"
if errorlevel 1 exit /b 1

echo [2/6] d8
python "%~dp0tools\pack_jar.py" "%OUT%\classes" "%OUT%\classes.jar"
if errorlevel 1 exit /b 1
call "%BT%\d8.bat" --min-api 29 --lib "%PLAT%" --output "%OUT%\dex" "%OUT%\classes.jar"
if errorlevel 1 exit /b 1

echo [3/6] aapt2 compile
"%BT%\aapt2.exe" compile --dir "%~dp0res" -o "%OUT%\res.zip"
if errorlevel 1 exit /b 1

echo [4/6] aapt2 link
"%BT%\aapt2.exe" link -o "%OUT%\base.apk" -I "%PLAT%" --manifest "%~dp0AndroidManifest.xml" --min-sdk-version 29 --target-sdk-version 33 "%OUT%\res.zip"
if errorlevel 1 exit /b 1

echo [5/6] pack dex + META-INF
python "%~dp0tools\pack_apk.py" "%OUT%\base.apk" "%OUT%\dex\classes.dex" "%~dp0assets" "%OUT%\packed.apk"
if errorlevel 1 exit /b 1
"%BT%\zipalign.exe" -f -p 4 "%OUT%\packed.apk" "%OUT%\aligned.apk"
if errorlevel 1 exit /b 1

echo [6/6] sign
call "%BT%\apksigner.bat" sign --ks "%~dp0debug.keystore" --ks-pass pass:android --key-pass pass:android --min-sdk-version 29 --out "%OUT%\camfix.apk" "%OUT%\aligned.apk"
if errorlevel 1 exit /b 1

echo BUILD OK: %OUT%\camfix.apk
endlocal
