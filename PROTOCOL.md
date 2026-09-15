# 局域网协议 v5

HTTP JSON，UTF-8。电脑 Python 服务监听 0.0.0.0:8765，电脑页面同源。所有时间是 Unix 毫秒。手机前台每 2 秒轮询；每个完整任务只确认一次，随后依次获取和导入分批短信。失败立即停止，不自动续写。

v5（v1.4.0）需要电脑服务、网页和 APK 同步升级。旧的 POST /api/jobs 已移除；所有 /api/device/ 请求必须携带 `X-SMS-Protocol: 5`，否则返回 426，提示升级。配对接口不变。旧 SQLite 任务仍保留，并按 v5 分页读取，缺失的附加字段由手机应用默认值，服务端不改写历史快照。

## 数据与限制

短信 `{ "sender": "13800138000", "body": "内容", "timestamp": 1789372800000 }`，这三个字段必填。sender 为 1–100 字符非空字符串，body 为 1–4000 字符非空字符串；timestamp 为 0–4102444800000 整数。

短信还支持以下平铺可选字段；未知字段拒绝。数字必须是 JSON 整数，不接受布尔值或数字字符串。缺失字段不由服务器补齐，原样保存与返回；下表默认值在手机写入时应用。显式 null 与缺失字段不同。

| 字段 | 合法值 | 缺失时默认值 |
|---|---|---|
| type | 仅整数 1（收件箱），其他类型拒绝 | 1 |
| protocol | null 或整数 0–255 | 0 |
| subject | null 或最多 4000 字符字符串，允许空串 | null |
| service_center | null 或最多 100 字符字符串，允许空串 | null |
| read | 整数 0/1 | 1 |
| status | 整数 -1–255，短信状态报告值，与任务上报状态无关 | -1 |
| locked | 整数 0/1 | 0 |
| toa、sc_toa | 仅 null；Android 标准短信数据库没有这两列，非空明确报错 | null |

附加字段随完整快照、预览和每批短信原样传输，重试时字段缺失与显式默认值仍属于不同快照。read/status/locked 在预览中展示，手机按提供的标准数据库字段写入；toa/sc_toa 的 null 表示无值，不创建不存在的数据库列。

每个完整任务 1–100000 条短信，每分批固定 500 条，最后一批为余数；索引从 0 开始。每次 HTTP 请求最多 16 MiB。完整短信列表规范 JSON 的 UTF-8 字节数最多 256 MiB：不转义 Unicode、无多余空白，包含数组括号和分隔逗号。服务端按已上传分批累加核验，同内容重试不重复占用额度。

任务元信息 `{ "id": "uuid", "deviceId": "uuid", "status": "queued", "count": 10001, "written": 0, "error": "", "createdAt": 0, "updatedAt": 0, "batchSize": 500, "batchCount": 21 }`。count 和 written 均为整个任务总数。

状态 queued → received → writing → completed/failed/interrupted；可 received → failed/interrupted（written 必须为 0）。后者处理手机持久化开始标记、但 writing 请求尚未到达电脑时进程退出的窗口。completed 必须 written=count；written 不递减且 <= count；终态不能改变。interrupted 指手机重启时发现曾经开始执行，不自动重新执行。

## 电脑管理 API

Authorization: Bearer ADMIN_TOKEN。管理链接 `http://127.0.0.1:8765/#token=ADMIN_TOKEN`，页面存入 sessionStorage 后清除 fragment。管理 token 不能出现在 QR。

- GET /api/apk → `{version:"v1.4.0",url:"http://电脑局域网地址:端口/downloads/android-sms-test-v1.4.0.apk"}`；GET /api/apk/qr 返回对应 SVG，均需管理员认证。下载本身 GET `/downloads/android-sms-test-v1.4.0.apk` 无需令牌，仅暴露 `dist` 中此固定文件；缺包返回 404，不提供目录浏览。
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
- GET /api/device/jobs，Bearer DEVICE_TOKEN，`X-SMS-Protocol: 5` → `{job:任务元信息加preview或null}`。preview 最多前 20 条短信，不含全量 messages。返回该手机最早未结束任务，更新 lastSeen。
- GET /api/device/jobs/ID/batches/INDEX，同样需要设备认证和协议头 → `{jobId:"uuid",index:0,offset:0,total:10001,messages:[短信]}`。只允许任务所属设备读取，状态必须 received/writing；要求 offset = 服务端已确认 written。因此手机必须在每批结束时成功报告总进度，才能读取下一批；终态拒绝读取。
- POST /api/device/jobs/ID/status，同样需要设备认证和协议头，body `{status:"received|writing|completed|failed|interrupted",written:0,error:""}` → `{ok:true}`。只能修改自己的任务；可重复报告同状态同进度，禁止回退。

手机先展示总数量和前 20 条预览，用户确认一次后写入。分批获取、权限检查、Provider 写入或进度反馈失败时停止本次任务，不跳过失败条目继续下一批。终态反馈失败可以重试反馈，但不能重做写入。

错误：HTTP 4xx/5xx JSON `{error:"中文原因"}`。任务去重以手机本地持久化 jobId 为准。插入前持久化 writing 标记；进程崩溃后报告 interrupted，不自动续写。短信 Provider 与本地存储不是同一个事务：极端崩溃时已写数量可能比记录进度多一条，不保证严格原子 exactly-once。

电脑端 SQLite 保存上传分批、任务、配对设备和 token，重启保留上传进度、去重与反馈。旧数据库新增 jobs.upload_id 列，原有 messages 数据不删除。绑定与传输为局域网 HTTP，限可信测试网络；令牌不是链路加密。不允许跨域访问管理 API，不启用 CORS。


## 手机短信读取和删除（v5）

管理请求独立于导入任务持久化在 `sms_requests`，升级旧数据库自动建表，不改动旧导入数据。管理页只单独查询某次请求；`/api/state` 不包含手机短信结果。每设备最多一个 queued/ready/running 管理请求，其余返回 409。requestId 必须为规范小写 UUID，同 ID、规范化同请求重试返回当前结果，异体返回 409；终态保留防止重放。

- POST `/api/sms/requests`，管理员 Bearer。body `{requestId,deviceId,action:"list",filters,page:0}` 或 `{requestId,deviceId,action:"delete",filters,selection}`，返回请求对象本身。
- GET `/api/sms/requests/ID`，管理员 Bearer，返回同一请求对象，不存在 404。
- GET `/api/device/sms/requests`，设备 Bearer + 协议 5，返回 `{request:对象或null}`；仅返回本设备活跃请求，并更新心跳。设备对象附带 filters/page/selection。
- POST `/api/device/sms/requests/ID/status`，设备 Bearer + 协议 5。body 全字段 `{status,count,processed,deleted,error,result}`，返回更新后对象。只允许所属设备报告，异设备返回 404。成功报告和同报告重试均更新心跳。

请求对象 `{id,deviceId,action,status,count:null,processed:0,deleted:0,error:"",result:null}`。提交阶段 count 未知；手机报告后为非负整数。error 最多 2000 字符，result 可以 null。未知字段拒绝；整数拒绝布尔值。

filters 默认 `{sender:"",keyword:"",dateFrom:null,dateTo:null,read:null,status:null,locked:0}`。sender 为精确匹配，最多 100 字符；keyword 为内容子串，最多 4000 字符；空字符串不限制。日期包含两端，为 0–4102444800000 的毫秒整数或 null，开始不能晚于结束；read/locked 为 null 或 0/1；status 为 null 或 -1–255。null 表示不限制。page 从 0 开始，最大 10000000；固定 pageSize=50。

selection 有三种形式：`{mode:"selected",items:[{id:"1",fingerprint:"64位小写SHA256"}]}`、`{mode:"filtered"}`、`{mode:"all"}`。selected 最多 100000 项、至少 1 项，ID 不重复，规范化按数值排序；ID 为 Android 正 64 位整数的十进制字符串，不能有前导零。filtered 根据 filters 生成手机端固定目标快照。all 忽略条件，将 filters 规范化为文本空串、其他 null，包含锁定短信。读取请求不能带 selection；filtered/all 不能带 items。删除请求的 page 统一为 0。

短信预览行 `{id,fingerprint,sender,body,timestamp,type,read,status,locked}`，sender 最多 200 字符、body 最多 2000 字符，手机只截断显示文本，指纹基于原始记录生成。timestamp 为非负 64 位毫秒数；type 为 0–6，read/locked 为 0/1，status 为 -1–255。页面内 ID 不能重复。

读取状态仅 queued → completed/failed。completed 必须返回 `{total,page,pageSize:50,rows}`，count=total，processed=deleted=0，rows 为该页实际条数且最多 50 条。失败可 result=null。

删除状态 queued → ready → running → completed/failed/interrupted；queued/ready 也可转 failed/interrupted/cancelled。准备过程被进程中断可直接 interrupted。ready 必须返回 `{count,preview:[最多10行],selectionMode}`，目标数自此固定，后续 result 可 null（服务器保留已确认预览），不能修改已有预览。手机确认前 processed=deleted=0；确认后 running 进度可重复且不能回退，始终 deleted<=processed<=count；completed 必须 processed=count。失败立即停止，已删除项不回滚，不自动续删。终态只接受完全相同的最后报告幂等重试。服务没有远程确认删除接口，真正删除必须经手机本地确认。


## 导入批次历史与批次清理（v5）

- 管理员 POST `/api/sms/requests`：`{requestId,deviceId,action:"batches",page:0}`，经原管理请求队列中继，由手机读取其私有批次数据库。无需读取系统短信，不受普通短信筛选限制，也不允许 selection。每页 50 条，按导入时间降序稳定排序。
- `batches` 状态与 `list` 一致，仅 queued → completed/failed，processed=deleted=0，成功必须返回 `{total,page,pageSize:50,batches:[{jobId,createdAt,requested,recorded,status}]}`。count=total，jobId 为规范 UUID，createdAt 为 Unix 毫秒，requested 为 1–100000，recorded 为 0–requested，status 为 writing/completed/failed/interrupted。recorded 是持久化导入记录数，不是现存短信数。
- 清理请求 `action:"delete",selection:{mode:"batch",jobId:"UUID"}`。filters 忽略并规范化为不限制，page=0；不可指定 items。手机按当前配对设备 ID + jobId 查批次，不允许跨设备查找或根据内容猜测。
- 准备阶段要求完整短信访问权限及默认短信应用角色。以导入时实际返回 ID 回读并记录的完整 14 字段指纹匹配现存短信；不存在项计入 missing，指纹已变化项计入 changed，均跳过；匹配项冻结入已有删除快照。
- batch 的 ready 结果为 `{count,preview,selectionMode:"batch",jobId,missing,changed}`，jobId 必须与请求选择的批次一致，count 为实际可删目标，preview 最多 10 条，missing/changed 为非负整数，三者合计最多 100000。手机确认后沿用原逐条原子校验、每批最多 200 条及终态幂等规则；零目标也需确认后完成。确认后目标变化仍失败停止。

手机 SQLite 升级保留已有删除快照，新增导入批次与实际插入 ID/字段指纹记录。开始写入前创建批次；每次插入后立即回读并持久保存实际记录，失败停止。进程恢复将未结束批次标 interrupted，不重复执行写入。旧版无实际 ID 记录的导入不创建推断历史。Provider 与本地记录非原子，最后一条可能实际写入但未记录，不纳入批次清理。
