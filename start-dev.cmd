@echo off
rem ============================================================
rem  一键启动（Windows 通用入口）—— 指向 deploy\windows\start-dev.cmd
rem  说明：旧版本脚本硬编码了另一台机器的 E:\work\env 路径，已废弃该写法。
rem ============================================================
call "%~dp0deploy\windows\start-dev.cmd"
