@echo off
REM dwc-dp-analyser — Windows wrapper for the DwC-DP analyser CLI.
REM Mirrors the bash wrapper: locate Java, locate the jar, exec, pass through exit code.

setlocal enabledelayedexpansion

set "APP_NAME=dwc-dp-analyser"
set "REQUIRED_JAVA_MAJOR=17"
set "SCRIPT_DIR=%~dp0"

REM --- find the jar: override, then portable layout, then next to script ---
if defined DWC_DP_ANALYSER_JAR (
  set "JAR_PATH=%DWC_DP_ANALYSER_JAR%"
) else if exist "%SCRIPT_DIR%..\lib\%APP_NAME%-cli.jar" (
  set "JAR_PATH=%SCRIPT_DIR%..\lib\%APP_NAME%-cli.jar"
) else if exist "%SCRIPT_DIR%%APP_NAME%-cli.jar" (
  set "JAR_PATH=%SCRIPT_DIR%%APP_NAME%-cli.jar"
) else (
  echo error: could not locate %APP_NAME%-cli.jar 1>&2
  echo   set DWC_DP_ANALYSER_JAR=C:\path\to\jar to override the search 1>&2
  exit /b 1
)

REM --- find a Java runtime ---
if defined JAVA_HOME (
  set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
  if not exist "!JAVA_BIN!" (
    echo error: JAVA_HOME is set but "!JAVA_BIN!" does not exist 1>&2
    exit /b 1
  )
) else (
  where java >nul 2>nul
  if errorlevel 1 (
    echo error: no Java runtime found on PATH 1>&2
    echo   install a JRE/JDK %REQUIRED_JAVA_MAJOR%+ or set JAVA_HOME 1>&2
    exit /b 1
  )
  set "JAVA_BIN=java"
)

REM --- verify minimum Java version ---
set "JAVA_MAJOR=0"
for /f "tokens=1,3 delims=. " %%A in ('"!JAVA_BIN!" -version 2^>^&1 ^| findstr /r "version"') do (
  if "%%A"=="1" (
    set "JAVA_MAJOR=%%B"
  ) else (
    set "JAVA_MAJOR=%%A"
  )
)
REM strip any stray quote characters left over from the version string
set "JAVA_MAJOR=%JAVA_MAJOR:"=%"

if !JAVA_MAJOR! lss %REQUIRED_JAVA_MAJOR% (
  echo error: %APP_NAME% requires Java %REQUIRED_JAVA_MAJOR%+, found major version !JAVA_MAJOR! 1>&2
  echo   using: !JAVA_BIN! 1>&2
  exit /b 1
)

REM --- JVM options: sane default, fully overridable ---
if not defined DWC_DP_ANALYSER_JAVA_OPTS set "DWC_DP_ANALYSER_JAVA_OPTS=-Xmx2g"

"!JAVA_BIN!" %DWC_DP_ANALYSER_JAVA_OPTS% -jar "%JAR_PATH%" %*
exit /b %errorlevel%
