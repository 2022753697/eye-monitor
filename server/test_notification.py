#!/usr/bin/env python3
"""
模拟 Android 设备测试完整通知流程
"""
import asyncio
import json
import time
import websockets

SERVER_URL = "ws://localhost:8080/ws/eye"

async def test_notification():
    print("=" * 60)
    print("测试: 完整通知功能流程")
    print("=" * 60)

    # 模拟设备 A（Android 设备）
    print("\n[设备A] 连接并创建配对")
    ws_a = await websockets.connect(SERVER_URL)
    msg = {
        "type": "pair_request",
        "deviceId": "android-device-a",
        "pairCode": None,
        "payload": {},
        "timestamp": int(time.time() * 1000)
    }
    await ws_a.send(json.dumps(msg))
    resp = json.loads(await ws_a.recv())
    code = resp['payload']['pairCode']
    print(f"[设备A] 配对码: {code}")

    # 模拟设备 B
    print("\n[设备B] 连接并加入配对")
    ws_b = await websockets.connect(SERVER_URL)
    msg['deviceId'] = "android-device-b"
    msg['pairCode'] = code
    await ws_b.send(json.dumps(msg))
    resp = json.loads(await ws_b.recv())
    print(f"[设备B] 配对成功, peerOnline={resp['payload']['peerOnline']}")

    # 设备A收到通知
    resp_a = json.loads(await ws_a.recv())
    print(f"[设备A] 收到: peerOnline={resp_a['payload']['peerOnline']}")

    # 模拟设备A的App切换（AccessibilityService触发）
    print("\n[测试] 模拟设备A切换到微信")
    app_switch_msg = {
        "type": "app_switch",
        "deviceId": "android-device-a",
        "pairCode": code,
        "payload": {
            "packageName": "com.tencent.mm",
            "appName": "微信",
            "action": "OPENED"
        },
        "timestamp": int(time.time() * 1000)
    }
    await ws_a.send(json.dumps(app_switch_msg))

    # 设备B应该收到通知
    print("[设备B] 等待通知...")
    notification = json.loads(await asyncio.wait_for(ws_b.recv(), timeout=5))
    print(f"[设备B] 收到通知: {notification['type']}")
    print(f"[设备B] App: {notification['payload']['appName']}")
    print(f"[设备B] 包名: {notification['payload']['packageName']}")
    assert notification['type'] == 'app_switch'
    assert notification['payload']['appName'] == '微信'
    print("[设备B] [OK] 通知接收成功")

    # 模拟另一个App切换
    print("\n[测试] 模拟设备A切换到Chrome")
    app_switch_msg['payload']['appName'] = 'Chrome'
    app_switch_msg['payload']['packageName'] = 'com.android.chrome'
    await ws_a.send(json.dumps(app_switch_msg))

    notification2 = json.loads(await asyncio.wait_for(ws_b.recv(), timeout=5))
    print(f"[设备B] 收到通知: {notification2['payload']['appName']}")
    assert notification2['payload']['appName'] == 'Chrome'
    print("[设备B] [OK] 第二次通知接收成功")

    print("\n" + "=" * 60)
    print("全部测试通过！通知功能正常 [OK]")
    print("=" * 60)

    await ws_a.close()
    await ws_b.close()

if __name__ == "__main__":
    asyncio.run(test_notification())
