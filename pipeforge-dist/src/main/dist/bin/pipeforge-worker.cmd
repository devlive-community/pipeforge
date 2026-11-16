@echo off
rem
rem Copyright (c) 2026 devlive-community/pipeforge
rem
rem Licensed under the MIT License. See the LICENSE file in the
rem project root for full license text.
rem
rem The worker process: pipeforge-worker.cmd ^<start^|stop^|status^|run^> [options]; see pipeforge-daemon.cmd.

call "%~dp0pipeforge-daemon.cmd" worker %*
exit /b %errorlevel%
