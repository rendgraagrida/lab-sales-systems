@echo off
python -u "%~dp0scripts\local.py" %*
exit /b %errorlevel%
