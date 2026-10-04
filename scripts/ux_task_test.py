# -*- coding: utf-8 -*-
"""UX 测试辅助：用 WS 驱动任务闭环验证好感度加分（accept+2 / complete+5 / reward+3）。
用法：python ux_task_test.py
前置：后端 8080 运行、.env 在 server/ 下、pair 245163（user4=Test01, user5=ABC）"""
import json, sys, time
import websocket, urllib.request

BASE = "http://127.0.0.1:8080"
WS = "ws://127.0.0.1:8080/ws/eye"
PAIR = "245163"

def login(user, pwd):
    req = urllib.request.Request(BASE + "/api/auth/login",
        data=json.dumps({"username": user, "password": pwd}).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        body = json.loads(r.read().decode())
    d = body.get("data", {})
    return d.get("accessToken")

def connect(token, device):
    ws = websocket.create_connection(WS, header=["X-Auth-Token: " + token], timeout=15)
    return ws

def send(ws, mtype, device, payload):
    msg = {"type": mtype, "deviceId": device, "pairCode": PAIR,
           "payload": payload, "timestamp": int(time.time() * 1000)}
    ws.send(json.dumps(msg, ensure_ascii=False))
    time.sleep(1.2)

def drain(ws):
    out = []
    ws.settimeout(1.5)
    try:
        while True:
            out.append(ws.recv())
    except Exception:
        pass
    return out

def main():
    t4 = login("Test01", "123456789")
    t5 = login("ABC", "123456789")
    if not t4 or not t5:
        print("登录失败"); sys.exit(1)
    d4, d5 = "ux-test-4", "ux-test-5"
    ws4 = connect(t4, d4); ws5 = connect(t5, d5)
    print("双 WS 连接成功")

    task_id = "ux-task-%d" % int(time.time())
    # 1) user4 发布
    send(ws4, "task_publish", d4,
         {"taskId": task_id, "content": "UX测试任务：亲一个", "rewardType": "kiss",
          "rewardText": "亲亲一个", "from": "Test01"})
    # 2) user5 接受
    send(ws5, "task_respond", d5, {"taskId": task_id, "action": "accept"})
    # 3) user4 完成
    send(ws4, "task_complete", d4, {"taskId": task_id})
    # 4) user5 兑现
    send(ws5, "task_reward", d5, {"taskId": task_id})

    r4 = drain(ws4); r5 = drain(ws5)
    print("user4 收到:", len(r4), "条；user5 收到:", len(r5), "条")
    for m in r5[:6]:
        try:
            j = json.loads(m)
            print("  u5:", j.get("type"), json.dumps(j.get("payload"), ensure_ascii=False)[:80])
        except Exception:
            pass
    ws4.close(); ws5.close()
    print("done, task_id=", task_id)

if __name__ == "__main__":
    main()
