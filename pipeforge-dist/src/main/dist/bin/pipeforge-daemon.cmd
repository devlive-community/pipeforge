@echo off
rem
rem Copyright (c) 2026 devlive-community/pipeforge
rem
rem Licensed under the MIT License. See the LICENSE file in the
rem project root for full license text.
rem
rem Runs a cluster process on Windows: pipeforge-daemon.cmd <master|worker|standalone>
rem <start|stop|status|run> [options]. stop ends the process at once: Windows has no SIGTERM.

setlocal EnableDelayedExpansion
set "ROLE=%~1"
set "COMMAND=%~2"
if not "%ROLE%"=="master" if not "%ROLE%"=="worker" if not "%ROLE%"=="standalone" (
  echo usage: pipeforge-daemon.cmd ^<master^|worker^|standalone^> ^<start^|stop^|status^|run^> [options] 1>&2
  exit /b 2
)
set "ARGS="
shift
shift
:collect
if "%~1"=="" goto collected
set "ARGS=!ARGS! %1"
shift
goto collect
:collected

for %%I in ("%~dp0..") do set "PIPEFORGE_HOME=%%~fI"
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java.exe") else (set "JAVA=java")
if not defined PIPEFORGE_LOG_DIR set "PIPEFORGE_LOG_DIR=%PIPEFORGE_HOME%\logs"
set "PID_FILE=%PIPEFORGE_HOME%\run\%ROLE%.pid"
set "OUT_FILE=%PIPEFORGE_LOG_DIR%\pipeforge-%ROLE%.out"

set "OPTIONS="
if exist "%PIPEFORGE_HOME%\conf\jvm.options" (
  for /f "usebackq eol=# tokens=* delims=" %%L in ("%PIPEFORGE_HOME%\conf\jvm.options") do (
    set "LINE=%%L"
    if "!LINE:~0,7!"=="master:" (
      if "%ROLE%"=="master" set "OPTIONS=!OPTIONS! !LINE:~7!"
    ) else if "!LINE:~0,7!"=="worker:" (
      if "%ROLE%"=="worker" set "OPTIONS=!OPTIONS! !LINE:~7!"
    ) else if "!LINE:~0,11!"=="standalone:" (
      if "%ROLE%"=="standalone" set "OPTIONS=!OPTIONS! !LINE:~11!"
    ) else (
      set "OPTIONS=!OPTIONS! !LINE!"
    )
  )
)
set LAUNCH="%JAVA%" !OPTIONS! %JAVA_OPTS% "-Dpipeforge.home=%PIPEFORGE_HOME%" "-Dpipeforge.role=%ROLE%" "-Dpipeforge.log.dir=%PIPEFORGE_LOG_DIR%" "-Dlogback.configurationFile=%PIPEFORGE_HOME%\conf\logback.xml" -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider -cp "%PIPEFORGE_HOME%\lib\*;%PIPEFORGE_HOME%\lib\logback\*" org.devlive.pipeforge.dist.ClusterMain %ROLE% !ARGS!

if "%COMMAND%"=="run" (
  !LAUNCH!
  exit /b !errorlevel!
)
if "%COMMAND%"=="status" (
  call :running
  if !errorlevel! equ 0 (echo The %ROLE% is running ^(pid !PID!^).& exit /b 0)
  echo The %ROLE% is not running.
  exit /b 3
)
if "%COMMAND%"=="stop" (
  call :running
  if !errorlevel! neq 0 (if exist "%PID_FILE%" del "%PID_FILE%"& echo The %ROLE% is not running.& exit /b 0)
  taskkill /F /PID !PID! >nul
  del "%PID_FILE%"
  echo Stopped the %ROLE%.
  exit /b 0
)
if "%COMMAND%"=="start" (
  call :running
  if !errorlevel! equ 0 (echo The %ROLE% is running already ^(pid !PID!^).& exit /b 0)
  if exist "%PID_FILE%" del "%PID_FILE%"
  if not exist "%PIPEFORGE_LOG_DIR%" mkdir "%PIPEFORGE_LOG_DIR%"
  start "pipeforge-%ROLE%" /b cmd /c "!LAUNCH! >> "%OUT_FILE%" 2>&1"
  for /l %%N in (1,1,60) do (
    if exist "%PID_FILE%" (
      set /p PID=<"%PID_FILE%"
      echo Started the %ROLE% ^(pid !PID!^); logs in %PIPEFORGE_LOG_DIR%.
      exit /b 0
    )
    timeout /t 1 /nobreak >nul
  )
  echo The %ROLE% did not start within 60 seconds; see %OUT_FILE% 1>&2
  exit /b 1
)
echo usage: pipeforge-%ROLE%.cmd ^<start^|stop^|status^|run^> [--set key=value]... [--labels k=v,...] 1>&2
exit /b 2

:running
if not exist "%PID_FILE%" exit /b 1
set /p PID=<"%PID_FILE%"
tasklist /FI "PID eq !PID!" 2>nul | find "!PID!" >nul
exit /b !errorlevel!
