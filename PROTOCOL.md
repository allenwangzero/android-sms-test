# 局域网协议 v2

HTTP JSON，UTF-8。电脑 Python 服务监听 0.0.0.0:8765，电脑页面同源。所有时间是 Unix 毫秒。手机前台每 2 秒轮询；每个完整任务只确认一次，随后依次获取和导入分批短信。失败立即停止，不自动续写。

v2 需要电脑服务、网页和 APK 同步升级。旧的 POST /api/jobs 已移除；所有 /api/device/ 请求必须携带 `X-SMS-Protocol: 2`，否则返回 426，提示升级。配对接口不变。旧 SQLite 任务仍保留，并按 v2 分页读取。

## 数据与限制

短信 `{ "sender": "13800138000", "body": "内容", "timestamp": 1789372800000 }`，只能包含这三个字段。sender 为 1–100 字符非空字符串，body 为 1–4000 字符非空字符串；timestamp 为 0–4102444800000 整数。

每个完整任务 1–100000 条短信，每分批固定 500 条，最后一批为余数；索引从 0 开始。每次 HTTP 请求最多 16 MiB。完整短信列表规范 JSON 的 UTF-8 字节数最多 256 MiB：不转义 Unicode、无多余空白，包含数组括号和分隔逗号。服务端按已上传分批累加核验，同内容重试不重复占用额度。

任务元信息 `{ "id": "uuid", "deviceId": "uuid", "status": "queued", "count": 10001, "written": 0, "error": "", "createdAt": 0, "updatedAt": 0, "batchSize": 500, "batchCount": 21 }`。count 和 written 均为整个任务总数。

状态 queued → received → writing → completed/failed/interrupted；可 received → failed/interrupted（written 必须为 0）。后者处理手机持久化开始标记、但 writing 请求尚未到达电脑时进程退出的窗口。completed 必须 written=count；written 不递减且 <= count；终态不能改变。interrupted 指手机重启时发现曾经开始执行，不自动重新执行。

## 电脑管理 API

Authorization: Bearer ADMIN_TOKEN。管理链接 `http://127.0.0.1:8765/#token=ADMIN_TOKEN`，页面存入 sessionStorage 后清除 fragment。管理 token 不能出现在 QR。

- GET /api/state → `{devices:[{id,name,lastSeen}], jobs:[任务元信息]}`。
- GET /api/pairing → `{url:"sms-test://pair?url=http%3A%2F%2F192.168.1.2%3A8765&token=TOKEN", expiresAt:毫秒, serverUrl:"http://192.168.1.2:8765"}`。
- GET /api/pairing/qr → SVG 二维码（前端 fetch Bearer 后以 blob 显示）。
- POST /api/pairing/rotate，body `{}` → 与 GET pairing 相同。配对码有效 10 分钟；有效期内可配对多台手机。
- POST /api/uploads，body `{requestId:"uuid",deviceIds:["uuid"],count:10001}` → `{uploadId:"requestId",count:10001,batchSize:500,batchCount:21,receivedBatches:[0,1],jobs:[]}`。requestId 对应不可修改的设备集合与短信总数；重复请求恢复上传进度，不同元数据返回 409。已提交时 jobs 返回原有任务。
- POST /api/uploads/ID/batches/INDEX，body `{messages:[短信]}` → `{ok:true,uploadId:"uuid",index:0}`。要求精确分批长度；相同索引、相同短信内容重试成功，不同内容返回 409。可乱序上传，但必须全部齐备才能提交。
- POST /api/uploads/ID/commit，body `{}` → `{jobs:[任务元信息]}`。缺少分批返回 409；齐备后在单个 SQLite 事务中为每台选中设备创建一个总任务。重复提交返回原任务，不再创建。提交前手机完全不可见。新任务共享同一不可变上传快照，不按设备复制全部短信。

- POST /api/uploads/ID/cancel，body `{}` → `{ok:true}`。仅允许取消未提交的上传，事务删除上传和分批数据；不存在时幂等成功，已经提交则返回 409，不删除手机任务。取消与提交并发时只允许其中一个成功。ID 必须为 UUID；即使上传尚不存在也持久化取消记录，防止另一个网页延迟到达的创建请求重新建立该上传。取消 ID 的创建、分批上传和提交均返回 410，服务重启后仍生效；新发送须使用新 requestId。

电脑页面在发送前持久化完整列表、设备和 requestId。网络结果不确定时以相同 requestId 查询已接收分批并重试；新页面编辑不得改变已有快照。

## 手机 API

- POST /api/pair，body `{token:"配对token",name:"设备显示名",clientId:"手机持久UUID"}` → `{deviceId:"uuid",deviceToken:"随机token",name:"..."}`。无需 Authorization，仅凭短时配对 token。相同 clientId 再配对保持设备 ID/token。
- GET /api/device/jobs，Bearer DEVICE_TOKEN，`X-SMS-Protocol: 2` → `{job:任务元信息加preview或null}`。preview 最多前 20 条短信，不含全量 messages。返回该手机最早未结束任务，更新 lastSeen。
- GET /api/device/jobs/ID/batches/INDEX，同样需要设备认证和协议头 → `{jobId:"uuid",index:0,offset:0,total:10001,messages:[短信]}`。只允许任务所属设备读取，状态必须 received/writing；要求 offset = 服务端已确认 written。因此手机必须在每批结束时成功报告总进度，才能读取下一批；终态拒绝读取。
- POST /api/device/jobs/ID/status，同样需要设备认证和协议头，body `{status:"received|writing|completed|failed|interrupted",written:0,error:""}` → `{ok:true}`。只能修改自己的任务；可重复报告同状态同进度，禁止回退。

手机先展示总数量和前 20 条预览，用户确认一次后写入。分批获取、权限检查、Provider 写入或进度反馈失败时停止本次任务，不跳过失败条目继续下一批。终态反馈失败可以重试反馈，但不能重做写入。

错误：HTTP 4xx/5xx JSON `{error:"中文原因"}`。任务去重以手机本地持久化 jobId 为准。插入前持久化 writing 标记；进程崩溃后报告 interrupted，不自动续写。短信 Provider 与本地存储不是同一个事务：极端崩溃时已写数量可能比记录进度多一条，不保证严格原子 exactly-once。

电脑端 SQLite 保存上传分批、任务、配对设备和 token，重启保留上传进度、去重与反馈。旧数据库新增 jobs.upload_id 列，原有 messages 数据不删除。绑定与传输为局域网 HTTP，限可信测试网络；令牌不是链路加密。不允许跨域访问管理 API，不启用 CORS。
