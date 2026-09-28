#!/usr/bin/env python3
"""
EyeMonitor WebSocket 通信验证脚本
模拟两台设备：配对 -> 发送 app_switch -> 验证转发
"""
import sys
import io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

import asyncio
import json
import time
import websockets

SERVER_URL = "ws://localhost:8080/ws/eye"

async def device_connect(name: str, device_id: str, pair_code: str = None):
    """连接 WebSocket 并发送配对请求，返回 (ws, pairCode)"""
    ws = await websockets.connect(SERVER_URL)
    print(f"\n[{name}] 已连接 (deviceId={device_id})")

    # 发送配对请求
    msg = {
        "type": "pair_request",
        "deviceId": device_id,
        "pairCode": pair_code,
        "payload": {},
        "timestamp": int(time.time() * 1000)
    }
    await ws.send(json.dumps(msg))
    print(f"[{name}] 发送: pair_request (pairCode={pair_code or '无'})")

    # 接收配对响应
    resp = json.loads(await ws.recv())
    print(f"[{name}] 收到: {resp['type']} -> {resp.get('payload', {})}")
    assert resp['type'] == 'pair_confirm', \
        f"期望 pair_confirm，实际 {resp['type']}: {resp.get('payload', {}).get('message', '')}"
    code = resp['payload']['pairCode']
    print(f"[{name}] [OK] 配对成功, code={code}")

    return ws, code


async def test_pairing():
    """测试配对流程"""
    ws_a = None
    ws_b = None
    try:
        print("=" * 60)
        print("测试 1: 设备 A 创建配对")
        print("=" * 60)
        ws_a, code = await device_connect("设备A", "device-a-001")

        print("\n" + "=" * 60)
        print("测试 2: 设备 B 使用相同码加入配对")
        print("=" * 60)
        ws_b, _ = await device_connect("设备B", "device-b-001", code)

        # 设备 A 应收到 peerOnline=true 的通知
        print("\n" + "=" * 60)
        print("测试 3: 验证双方配对完成")
        print("=" * 60)
        resp_a2 = json.loads(await ws_a.recv())
        print(f"[设备A] 收到: {resp_a2['type']} -> {resp_a2.get('payload', {})}")
        assert resp_a2['payload']['peerOnline'] == True, "期望 peerOnline=true"
        print("[设备A] [OK] 配对完成，对端在线")

        print("\n" + "=" * 60)
        print("测试 4: 设备 A 发送 app_switch -> 设备 B 应收到")
        print("=" * 60)
        switch_msg = {
            "type": "app_switch",
            "deviceId": "device-a-001",
            "pairCode": code,
            "payload": {
                "packageName": "com.tencent.mm",
                "appName": "微信",
                "action": "OPENED"
            },
            "timestamp": int(time.time() * 1000)
        }
        await ws_a.send(json.dumps(switch_msg))
        print(f"[设备A] 发送: app_switch (微信)")

        forwarded = json.loads(await ws_b.recv())
        print(f"[设备B] 收到: {forwarded['type']} -> {forwarded.get('payload', {})}")
        assert forwarded['type'] == 'app_switch', f"期望 app_switch，实际 {forwarded['type']}"
        assert forwarded['payload']['appName'] == '微信', \
            f"期望 微信，实际 {forwarded['payload'].get('appName')}"
        print("[设备B] [OK] 成功收到转发消息")

        print("\n" + "=" * 60)
        print("测试 5: 设备 B 发送 location -> 设备 A 应收到")
        print("=" * 60)
        loc_msg = {
            "type": "location",
            "deviceId": "device-b-001",
            "pairCode": code,
            "payload": {
                "lat": 31.2304,
                "lng": 121.4737,
                "accuracy": 20.0
            },
            "timestamp": int(time.time() * 1000)
        }
        await ws_b.send(json.dumps(loc_msg))
        print(f"[设备B] 发送: location (31.23, 121.47)")

        forwarded2 = json.loads(await ws_a.recv())
        print(f"[设备A] 收到: {forwarded2['type']} -> {forwarded2.get('payload', {})}")
        assert forwarded2['type'] == 'location', f"期望 location，实际 {forwarded2['type']}"
        assert forwarded2['payload']['lat'] == 31.2304, f"lat 不匹配"
        print("[设备A] [OK] 成功收到位置转发")

        print("\n" + "=" * 60)
        print("测试 6: 心跳 ping/pong")
        print("=" * 60)
        ping_msg = {
            "type": "ping",
            "deviceId": "device-a-001",
            "pairCode": code,
            "payload": {},
            "timestamp": int(time.time() * 1000)
        }
        await ws_a.send(json.dumps(ping_msg))
        pong = json.loads(await ws_a.recv())
        print(f"[设备A] ping -> pong: {pong['type']}")
        assert pong['type'] == 'pong', f"期望 pong，实际 {pong['type']}"
        print("[设备A] [OK] 心跳正常")

        print("\n" + "=" * 60)
        print("全部测试通过 [OK]")
        print("=" * 60)

    finally:
        if ws_a:
            await ws_a.close()
        if ws_b:
            await ws_b.close()


if __name__ == "__main__":
    asyncio.run(test_pairing())