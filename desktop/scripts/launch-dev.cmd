@echo off
rem Launch Planability desktop (dev build) with the main-slot MiMo key injected.
rem Key stays out of the repo: reused from the sealed COD toolkit.
rem
rem NON-INTERACTIVE on purpose. The original used `set /p MIMO_KEY=<file`, which reads a line
rem from the file but is an interactive command: if the file is missing (or stdin is not a
rem console, e.g. launched from a shortcut) it silently waits for a human that never comes.
rem `for /f` reads the same file without ever prompting, and the checks below turn every
rem failure into a message instead of a hang.

setlocal
set "KEYFILE=D:\IDE\COD\COD-main\.m0-tools\keys\mimo.key"
set "HERE=%~dp0.."

rem Node for electron.cmd. Prepended only if it exists, so a machine with node already on PATH
rem does not depend on this hardcoded path.
if exist "D:\node-v24.15.0-win-x64" set "PATH=D:\node-v24.15.0-win-x64;%PATH%"

if not exist "%KEYFILE%" (
  echo [Planability] key file not found: %KEYFILE%
  echo [Planability] AI features will be unavailable. Fix the path in scripts\launch-dev.cmd
  goto :fail
)

set "MIMO_KEY="
for /f "usebackq delims=" %%k in ("%KEYFILE%") do (
  if not defined MIMO_KEY set "MIMO_KEY=%%k"
)
if not defined MIMO_KEY (
  echo [Planability] key file is empty: %KEYFILE%
  goto :fail
)

if not exist "%HERE%\out\main\index.js" (
  echo [Planability] out\main\index.js is missing - nothing has been built yet.
  echo [Planability] run this first:  npm run build
  goto :fail
)
if not exist "%HERE%\node_modules\.bin\electron.cmd" (
  echo [Planability] electron is not installed. Run: npm install
  goto :fail
)

set "PLANABILITY_AI_MAIN_KEY=%MIMO_KEY%"
set "PLANABILITY_AI_MAIN_URL=https://api.xiaomimimo.com/v1"
set "PLANABILITY_AI_MAIN_MODEL=mimo-v2.6-flash"

cd /d "%HERE%" || goto :fail
start "" node_modules\.bin\electron.cmd out\main\index.js
rem endlocal immediately: the electron process is detached by `start`, so there is nothing to
rem keep this console alive for. Without this the window lingers and looks like a hang.
endlocal
exit /b 0

:fail
rem Leave the window up long enough to read the message, then close. A shortcut launches this
rem minimized, so a flash-and-vanish error is the hardest kind to diagnose.
timeout /t 6 >nul
endlocal
exit /b 1
