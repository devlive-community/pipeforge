@rem
@rem Copyright (c) 2026 devlive-community/pipeforge
@rem
@rem Licensed under the MIT License. See the LICENSE file in the
@rem project root for full license text.
@rem
@rem Starts the pipeforge command. JAVA_HOME selects the Java runtime (21 or later), JAVA_OPTS adds
@rem JVM options. The first run writes a class data sharing archive that makes later starts faster.
@echo off
setlocal
set "PIPEFORGE_HOME=%~dp0.."
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java") else (set "JAVA=java")
"%JAVA%" -XX:+AutoCreateSharedArchive "-XX:SharedArchiveFile=%PIPEFORGE_HOME%\lib\pipeforge.jsa" -Xlog:cds=off -Xlog:cds+dynamic=off %JAVA_OPTS% "-Dpipeforge.home=%PIPEFORGE_HOME%" -cp "%PIPEFORGE_HOME%\lib\*" org.devlive.pipeforge.cli.Main %*
exit /b %ERRORLEVEL%
