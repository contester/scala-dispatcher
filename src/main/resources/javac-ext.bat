@echo off
Setlocal EnableDelayedExpansion

javac "%~1" 1>nul 2> compilation.log
find "is public, should be declared in a file named" compilation.log
set className=%~n1

if errorlevel 1 (
    javac "%~1" 1>nul 2> compilation.log
    if errorlevel 1 (
        type compilation.log
        exit 1
    )
) else (
    set /P compilationFirstLine=<compilation.log
    echo !compilationFirstLine!
    for %%A in (!compilationFirstLine!) do set fileName=%%A
    echo !fileName!
    copy %1 !fileName! 1>nul 2>nul
    javac !fileName! 1>nul 2> compilation.log
    if errorlevel 1 (
        type compilation.log
        exit 1
    )
    for %%i in (!fileName!) do set className=%%~ni
)

type compilation.log

echo Main-Class: !className!> MANIFEST.MF
jar cmf MANIFEST.MF Solution.jar *.class
