@echo off
REM ============================================================
REM  mini-sync 一键启动（Windows）
REM
REM  做三件事：
REM    1. 加载 .env 里的数据库口令到当前进程
REM    2. 编译（含插件打包）
REM    3. 启动 Spring Boot 常驻服务
REM
REM  用法：双击本文件，或在项目根目录执行 start.bat
REM ============================================================
setlocal

cd /d "%~dp0"

echo.
echo ============ mini-sync 启动 ============
echo.

REM ---------- 1. 检查 .env ----------
if not exist ".env" (
    echo [错误] 未找到 .env 文件
    echo        请先执行:  copy .env.example .env
    echo        然后编辑 .env，填写 MINISYNC_DB_PWD（和可选的 MINISYNC_PG_PWD）
    pause
    exit /b 1
)

REM ---------- 2. 加载 .env 到环境变量 ----------
REM 逐行读取，跳过注释与空行，用 set 设置变量
for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
    set "line=%%A"
    if not "%%A"=="" (
        echo %%A | findstr /b /c:"#" >nul
        if errorlevel 1 (
            set "%%A=%%B"
        )
    )
)

if "%MINISYNC_DB_PWD%"=="" (
    echo [错误] .env 里没有配置 MINISYNC_DB_PWD
    pause
    exit /b 1
)
echo [配置] 数据库口令已从 .env 加载

REM ---------- 3. JDK ----------
if "%MINISYNC_JAVA_HOME%"=="" set "MINISYNC_JAVA_HOME=D:\jdk"
set "JAVA_HOME=%MINISYNC_JAVA_HOME%"
echo [配置] JAVA_HOME=%JAVA_HOME%

if "%MINISYNC_MVN%"=="" set "MINISYNC_MVN=D:\apache-maven-3.9.16\bin\mvn.cmd"
echo [配置] MVN=%MINISYNC_MVN%

REM ---------- 4. 可选：先编译 ----------
if /i "%~1"=="--build" (
    echo.
    echo [1/2] 编译中...
    call "%MINISYNC_MVN%" -B clean install -DskipTests
    if errorlevel 1 (
        echo [错误] 编译失败
        pause
        exit /b 1
    )
    echo [1/2] 编译完成
)

REM ---------- 5. 启动 ----------
echo.
echo [启动] Spring Boot 服务...
echo        访问地址: http://localhost:%MINISYNC_SERVER_PORT%
if "%MINISYNC_SERVER_PORT%"=="" echo        访问地址: http://localhost:8081
echo        默认账号: admin / admin123
echo        按 Ctrl+C 停止
echo.

call "%MINISYNC_MVN%" -B -pl mini-sync-server spring-boot:run

endlocal
