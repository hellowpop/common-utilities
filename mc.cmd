@echo off

set PROJECT_HOME=%CD%

rem === COMMON ===
doskey /REINSTALL
rem ## project homes
doskey mock=ant spring-base-http2-mock
