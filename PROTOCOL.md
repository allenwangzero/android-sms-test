# 局域网协议 v1

HTTP JSON，UTF-8。电脑 Python 服务监听 0.0.0.0:8765，电脑页面同源。所有时间是 Unix 毫秒。手机保持前台，每 2 秒轮询。不自动执行写入，必须手机确认。

## 数据

短信 `{ "sender": "13800138000", "body": "内容", "timestamp": 1789372800000 }`。sender 为 1–100 字符非空字符串，body 为 1–4000 字符非空字符串；timestamp 为 0–4102444800000 整数。每批 1–10000 条，请求最多 16 MiB。

任务 `{ "id": "uuid", "deviceId": "uuid", "status": "queued", "count": 100, "written": 0, "error": "", "createdAt": 0, "updatedAt": 0, "messages": [...] }`。
状态 queued → received → writing → completed/failed/interrupted；可 received → failed/interrupted（written 必须为 0）。后者处理手机已经持久化开始标记、但 writing 请求尚未到达电脑时进程退出的窗口。completed 必须 written=count；written 不递减且 <= count；终态不能转回活动态。interrupted 指手机重启时发现曾经开始执行，不能自动重新执行。

## 电脑管理 API

Authorization: Bearer ADMIN_TOKEN。管理链接 `http://127.0.0.1:8765/#token=ADMIN_TOKEN`，页面存入 sessionStorage 后清除 fragment。管理 token 不能出现在 QR。

- GET /api/state → `{devices:[{id,name,lastSeen}], jobs:[任务不含messages]}`。
- GET /api/pairing → `{url:"sms-test://pair?url=http%3A%2F%2F192.168.1.2%3A8765&token=TOKEN", expiresAt:毫秒, serverUrl:"http://192.168.1.2:8765"}`。
- GET /api/pairing/qr → SVG 二维码（前端 fetch Bearer 后以 blob 显示）。
- POST /api/pairing/rotate，body `{}` → 与 GET pairing 相同。配对码有效 10 分钟；有效期内可配对多台手机。
- POST /api/jobs，body `{requestId:"uuid",deviceIds:["uuid"],messages:[短信]}` → `{jobs:[任务不含messages]}`。requestId 重试返回原任务，同 id 不同数据返回409；创建任务快照，后续网页编辑不影响已发送任务。

## 手机 API

- POST /api/pair，body `{token:"配对token",name:"设备显示名",clientId:"手机持久UUID"}` → `{deviceId:"uuid",deviceToken:"随机token",name:"..."}`。无需 Authorization，仅凭短时配对 token。相同 clientId 再配对保持设备ID/token以支持网络重试。
- GET /api/device/jobs，Authorization: Bearer DEVICE_TOKEN → `{job:任务含messages或null}`。返回该手机最早未结束任务，更新 lastSeen。
- POST /api/device/jobs/ID/status，Bearer DEVICE_TOKEN，body `{status:"received|writing|completed|failed|interrupted",written:0,error:""}` → `{ok:true}`。只能修改自己的任务，可重复报告同状态同进度，禁止回退。

错误：HTTP 4xx/5xx JSON `{error:"中文原因"}`。手机在网络失败时保留结果并重试反馈；任务去重以本地持久化 jobId 为准。插入前持久化 writing 标记；进程崩溃后报告 interrupted，不自动续写；不得声称跨短信 Provider 和本地存储实现严格原子 exactly-once。

电脑端用 SQLite 保存任务、配对设备和 token，重启保留去重与反馈。绑定与传输为局域网 HTTP，限可信测试网络；令牌不是链路加密。不允许跨域访问管理 API，不启用 CORS。
