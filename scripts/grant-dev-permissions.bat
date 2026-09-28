@echo off
REM ============================================================
REM  眼互（EyeMonitor）- 开发环境一键授权脚本
REM  用途：开发调试阶段，免除每次安装 APK 后手动授权。
REM  用法：连接好设备（真机 adb 或模拟器）后双击运行。
REM  注意：部分权限系统限制无法经 adb 授予（见下方"手动项"）。
REM ============================================================
setlocal
set "ADB=E:\Android\SDK\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb.exe"

echo. ============================================
echo. 正在查找在线设备...
%ADB% devices

echo. ============================================
for /f "usebackq tokens=1" %%d in (`%ADB% devices ^| findstr /v "List of devices attached" ^| findstr /v "^\s*$" ^| findstr "device"`) do (
    echo. 正在授权设备: %%d
    echo. --- 授予运行时权限（通知/位置/后台位置）---
    %ADB% -s %%d shell pm grant com.eyemonitor android.permission.POST_NOTIFICATIONS
    %ADB% -s %%d shell pm grant com.eyemonitor android.permission.ACCESS_FINE_LOCATION
    %ADB% -s %%d shell pm grant com.eyemonitor android.permission.ACCESS_BACKGROUND_LOCATION

    echo. --- 授予「使用情况访问」（UsageStats）---
    %ADB% -s %%d shell appops set com.eyemonitor GET_USAGE_STATS allow

    echo. --- 尝试开启「无障碍服务」（部分设备/模拟器不生效，需手动）---
    %ADB% -s %%d shell settings put secure enabled_accessibility_services com.eyemonitor/com.eyemonitor.service.AppAccessibilityService
    %ADB% -s %%d shell settings put secure accessibility_enabled 1

    echo. --- 防电池优化（可选）---
    %ADB% -s %%d shell dumpsys deviceidle whitelist +com.eyemonitor

    echo. --- 注入模拟位置（模拟器专用；lng 经度, lat 纬度）---
    %ADB% -s %%d emu geo fix 121.4737 31.2304
    echo. 设备 %%d 授权完成。
)

echo. ============================================
echo. 完成！仍有以下权限需在设备「设置」中手动开启一次：
echo.   1. 无障碍服务：设置 - 辅助功能 - 眼互 - 开启（模拟器/部分系统 adb 无法写入）
echo.   2. 使用情况访问：设置 - 应用 - 眼互 - 使用情况访问 - 开启
echo.      （若 appops 授权被系统回滚，也需手动开启，仅一次，之后保留）
echo.   注意：每次 "install -r" 更新 APK 或 force-stop 后，无障碍开关可能被系统重置，需重新开启。
pause