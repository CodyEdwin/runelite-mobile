@echo off

set thisdir = "%~dp0"
set langfile = %thisdir%\..\runelite\src\main\assets\language_list.txt

del %langfile%
dir %thisdir%\..\runelite\src\main\res\values-* /s /b > %langfile%

