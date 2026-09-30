#!/usr/bin/env bash
# 眼互省电优化 · P0 基线测量
# 场景：AVD 亮屏 4min（含 app 切换/位置移动注入）+ 息屏 6min → 计数
# 用法：bash scripts/battery-baseline.sh
# 产物：.omc/state/battery-baseline.json
set -euo pipefail
export MSYS_NO_PATHCONV=1

ADB="E:/Android/Sdk/platform-tools/adb.exe"
D=emulator-5556
DEVICE_ID="a0bb5f68-b0eb-4e9d-8c0b-719cfc5e86b1"
PAIR="245163"
MYSQL=(mysql -ueye -p'EyeDev2025_local!' -h127.0.0.1 eye_monitor -N -s)

BRIGHT_MIN=4
OFF_MIN=6

echo "[P0] 开始基线：亮屏 ${BRIGHT_MIN}min + 息屏 ${OFF_MIN}min @ $D"

# ---------- 1. 起点基线 ----------
LOC_BEFORE=$("${MYSQL[@]}" -e "SELECT COUNT(*) FROM eye_location_points WHERE device_id='$DEVICE_ID'" 2>/dev/null)
echo "[P0] 位置落库起点: $LOC_BEFORE"

# ---------- 2. 清 logcat ----------
"$ADB" -s $D logcat -c

# ---------- 3. 亮屏窗口（4min）：切 app ×5 + 2 次位置移动注入 ----------
"$ADB" -s $D shell "input keyevent 82" || true   # 确保亮屏
wake=$("$ADB" -s $D shell "dumpsys power | grep mWakefulness" | head -1)
echo "[P0] 亮屏校验: $wake"

BRIGHT_END=$(( BRIGHT_MIN * 60 ))
STEP=$(( BRIGHT_END / 5 ))
for i in $(seq 0 4); do
    case $((i % 2)) in
        0) "$ADB" -s $D shell "am start -n com.android.settings/.Settings" >/dev/null 2>&1 || true ;;
        1) "$ADB" -s $D shell "input keyevent 3" >/dev/null 2>&1 || true ;;  # HOME
    esac
    # 第 2 次后注入一次位置移动（跨 200m，触发围栏/大位移链路）
    if [ "$i" -eq 2 ]; then
        "$ADB" -s $D emu geo fix 121.49 31.24 >/dev/null 2>&1 || true
        echo "[P0] 注入位置① (121.49, 31.24)"
    elif [ "$i" -eq 4 ]; then
        "$ADB" -s $D emu geo fix 121.52 31.27 >/dev/null 2>&1 || true
        echo "[P0] 注入位置② (121.52, 31.27)"
    fi
    sleep "$STEP"
done

# ---------- 4. 息屏窗口（6min）----------
"$ADB" -s $D shell "input keyevent 26" || true
sleep 3
off=$("$ADB" -s $D shell "dumpsys power | grep mWakefulness" | head -1)
echo "[P0] 息屏校验: $off"
sleep $(( OFF_MIN * 60 ))

# ---------- 5. 采集 ----------
# 注意：Windows Git Bash 把中文参数按 GBK 传给 adb → 设备 grep 匹配不到 UTF-8 日志里的中文
# 全部用 ASCII 模式计数
# 5.1 轮询计数（当前无 usage 权限 → 应≈0；P1 后 POLL 标记为 ASCII 可数）
POLL_COUNT=$("$ADB" -s $D shell "logcat -d 2>/dev/null | grep -c 'queryEvents'" || true)
# 5.2 应用层 WS 发送（每帧都含 pairCode，ASCII）
SEND_TOTAL=$("$ADB" -s $D shell "logcat -d 2>/dev/null | grep -c '\"pairCode\"'" || true)
STATUS_COUNT=$("$ADB" -s $D shell "logcat -d 2>/dev/null | grep -c '\"type\":\"device_status\"'" || true)
LOC_SEND=$("$ADB" -s $D shell "logcat -d 2>/dev/null | grep -c '\"type\":\"location\"'" || true)
# 5.3 窗口时间范围（首/末发送帧）
WIN_FIRST=$("$ADB" -s $D shell "logcat -d 2>/dev/null | grep '\"pairCode\"' | head -1 | cut -c1-18" || true)
WIN_LAST=$("$ADB" -s $D shell "logcat -d 2>/dev/null | grep '\"pairCode\"' | tail -1 | cut -c1-18" || true)
# 5.4 位置落库增量（MySQL，按 device_id）
LOC_AFTER=$("${MYSQL[@]}" -e "SELECT COUNT(*) FROM eye_location_points WHERE device_id='$DEVICE_ID'" 2>/dev/null)
LOC_DELTA=$(( LOC_AFTER - LOC_BEFORE ))

echo "================ 基线结果 ================"
echo "窗口范围            : $WIN_FIRST ~ $WIN_LAST"
echo "轮询触发次数        : $POLL_COUNT (queryEvents ASCII 计数；当前无 usage 授权→0)"
echo "WS 发送总帧(应用层) : $SEND_TOTAL"
echo "  ├ location 帧     : $LOC_SEND"
echo "  └ device_status   : $STATUS_COUNT (30s固定 + 网络/电量变化事件)"
echo "位置落库增量        : $LOC_DELTA (device=$DEVICE_ID)"
echo "心跳帧(OkHttp原生)  : 不可测（协议级无日志），理论值 窗口≈2/min"
echo "=========================================="

cat > .omc/state/battery-baseline.json <<EOF
{
  "scenario": "AVD 亮屏4min+息屏6min；无 usage-stats 授权（API36 AVD appops 拒绝生效）；位置注入2次跨200m",
  "window_sec": { "bright": $((BRIGHT_MIN*60)), "off": $((OFF_MIN*60)) },
  "log_window": "$WIN_FIRST ~ $WIN_LAST",
  "measured": {
    "poll_count": $POLL_COUNT,
    "ws_send_total": $SEND_TOTAL,
    "location_frames": $LOC_SEND,
    "status_frames": $STATUS_COUNT,
    "location_rows_delta": $LOC_DELTA
  },
  "theoretical_fixed_rate": {
    "note": "固定速率源不可测（无日志）时的理论值；P4 用同窗口对比",
    "heartbeat_ping_30s": $(( (BRIGHT_MIN+OFF_MIN)*2 )),
    "device_status_30s": $(( (BRIGHT_MIN+OFF_MIN)*2 ))
  },
  "caveats": [
    "AVD appops 拒绝授权 GET_USAGE_STATS → 轮询未运行（accessibility 也未启用）",
    "OkHttp 原生 ping 帧无应用层日志，pong 收到即活跃",
    "MuMu 不支持息屏（游戏模拟器常亮），基线全部在 AVD",
    "device_status 含变化事件（模拟器网络抖动），高于纯 30s 理论值",
    "P4 用同场景同脚本对比（ASCII 计数模式）"
  ],
  "device": { "id": "$DEVICE_ID", "pair": "$PAIR" },
  "ts": "$(date -Iseconds)"
}
EOF
echo "[P0] 报告已写 .omc/state/battery-baseline.json"