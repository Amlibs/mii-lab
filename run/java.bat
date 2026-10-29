@echo off
rem Общие настройки для скриптов запуска: где java и откуда брать классы.
rem Классы собирает IDEA: Build -> Build Project (папка out\production\rizblabmac).
set CP=out\production\rizblabmac;lib\jade.jar;lib\gson-2.13.1.jar
set JAVA=java
rem если java нет в PATH — берём JDK, скачанный IDEA (папка .jdks в домашней папке)
if exist "%USERPROFILE%\.jdks" for /d %%d in ("%USERPROFILE%\.jdks\*") do if exist "%%d\bin\java.exe" set JAVA="%%d\bin\java.exe"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set JAVA="%JAVA_HOME%\bin\java.exe"
