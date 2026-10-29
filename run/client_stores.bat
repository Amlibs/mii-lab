@echo off
chcp 65001 > nul
rem ================= КЛИЕНТ: МАГАЗИНЫ (компьютер 2) =================
rem IP сервера (компьютера 1) в сети
set SERVER_IP=192.168.1.10
rem IP этого компьютера (необязательно; нужно, если несколько сетевых адаптеров)
set MY_IP=

cd /d "%~dp0.."
call run\java.bat
%JAVA% -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "%CP%" delivery.ClientMain %SERVER_IP% stores data %MY_IP%
pause
