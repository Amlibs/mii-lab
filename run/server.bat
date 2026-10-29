@echo off
chcp 65001 > nul
rem ================= СЕРВЕР (компьютер 1) =================
rem IP этого компьютера в сети (узнать: ipconfig). Для запуска на одном компьютере можно оставить пустым.
set MY_IP=

cd /d "%~dp0.."
call run\java.bat
%JAVA% -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "%CP%" delivery.ServerMain %MY_IP%
pause
