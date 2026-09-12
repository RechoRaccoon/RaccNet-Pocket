@echo off
:: ============================================================
::  MediaViewer - Push to GitHub and trigger build
::  FIRST TIME SETUP: Replace the URL below with your repo URL
::  Example: https://github.com/YourName/MediaViewer.git
:: ============================================================
set REPO_URL=https://github.com/RechoRaccoon/RaccNetLite.git

:: Check if git is set up in this folder already
if not exist ".git" (
    echo Setting up Git for the first time...
    git init
    git remote add origin %REPO_URL%
    git branch -M main
) else (
    :: Make sure remote is correct in case it changed
    git remote set-url origin %REPO_URL% 2>nul
)

:: Stage all files, commit, and push
echo.
echo Pushing to GitHub...
git add .
git commit -m "Update %date% %time%"
git push -u origin main --force

echo.
echo ============================================================
echo Done! Build is running on GitHub Actions.
echo Go to your repo ^> Actions tab to watch progress (~5 min).
echo When finished, click the run ^> Artifacts ^> download APK.
echo ============================================================
echo.
pause
