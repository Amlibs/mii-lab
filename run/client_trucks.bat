@echo off
chcp 65001 > nul
rem ================= КЛИЕНТ: МАШИНЫ (компьютер 1) =================
rem IP сервера. Если клиент на том же компьютере, что и сервер, — 127.0.0.1
set SERVER_IP=127.0.0.1
rem IP этого компьютера (необязательно; нужно, если несколько сетевых адаптеров)
set MY_IP=

cd /d "%~dp0.."
call run\java.bat
%JAVA% -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "%CP%" delivery.ClientMain %SERVER_IP% trucks data %MY_IP%
pause
