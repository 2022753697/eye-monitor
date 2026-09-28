#!/usr/bin/env python3
"""
测试设备重连后恢复配对功能
"""
import asyncio
import json
import time
import websockets

SERVER_URL = "ws://localhost:8080/ws/eye"

async def test_reconnect():
    print("=" * 60)
    print("测试: 设备重连后恢复配对")
    print("=" * 60)

    # 设备 A 连接并创建配对
    print("\n[步骤 1] 设备 A 创建配对")
    ws_a = await websockets.connect(SERVER_URL)
    msg = {
        "type": "pair_request",
        "deviceId": "test-device-a",
        "pairCode": None,
        "payload": {},
        "timestamp": int(time.time() * 1000)
    }
    await ws_a.send(json.dumps(msg))
    resp = json.loads(await ws_a.recv())
    print(f"[设备A] 配对码: {resp['payload']['pairCode']}")
    code = resp['payload']['pairCode']

    # 设备 B 连接加入配对
    print("\n[步骤 2] 设备 B 加入配对")
    ws_b = await websockets.connect(SERVER_URL)
    msg['deviceId'] = "test-device-b"
    msg['pairCode'] = code
    await ws_b.send(json.dumps(msg))
    resp = json.loads(await ws_b.recv())
    print(f"[设备B] 配对成功: {resp['payload']['pairCode']}, peerOnline={resp['payload']['peerOnline']}")

    # 设备 A 收到 peerOnline 通知
    resp_a = json.loads(await ws_a.recv())
    print(f"[设备A] 收到: {resp_a['type']}, peerOnline={resp_a['payload']['peerOnline']}")

    print("\n[步骤 3] 断开设备 B")
    await ws_b.close()
    await asyncio.sleep(1)

    print("\n[步骤 4] 设备 B 重连（应该自动恢复配对）")
    ws_b_new = await websockets.connect(SERVER_URL)
    msg['deviceId'] = "test-device-b"
    msg['pairCode'] = code
    await ws_b_new.send(json.dumps(msg))
    resp = json.loads(await ws_b_new.recv())
    print(f"[设备B新连接] 配对状态: {resp['type']}, peerOnline={resp['payload']['peerOnline']}")
    assert resp['payload']['peerOnline'] == True, "应该恢复配对"

    print("\n[步骤 5] 发送 app_switch 消息")
    switch_msg = {
        "type": "app_switch",
        "deviceId": "test-device-a",
        "pairCode": code,
        "payload": {
            "packageName": "com.chrome",
            "appName": "Chrome",
            "action": "OPENED"
        },
        "timestamp": int(time.time() * 1000)
    }
    await ws_a.send(json.dumps(switch_msg))
    forwarded = json.loads(await ws_b_new.recv())
    print(f"[设备B] 收到: {forwarded['type']}, appName={forwarded['payload']['appName']}")
    assert forwarded['payload']['appName'] == "Chrome"

    print("\n" + "=" * 60)
    print("测试通过！[OK]")
    print("=" * 60)

    await ws_a.close()
    await ws_b_new.close()

if __name__ == "__main__":
    asyncio.run(test_reconnect())
