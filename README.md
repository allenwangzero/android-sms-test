# 短信局域网测试工具

电脑编辑短信列表，安卓测试机扫码连接，在手机确认后把这些短信写入**系统收件箱**。支持一个电脑连接多台测试机；不对接上报接口，不通过运营商发送短信。

## 导入流程

1. 电脑启动本地服务，打开终端打印的管理页面链接。
2. 安卓工具扫描电脑页面上的配对二维码。电脑与手机需要在可互相访问的同一局域网。
3. 在电脑随机生成短信，或点击“上传 XML 并追加”导入短信文件，再编辑发送人、正文和接收时间。XML 支持 `<smses><sms address="发送人" body="内容" date="毫秒时间戳" type="1" /></smses>` 格式，仅支持收件短信。保留原顺序与内容，全部校验通过并保存后才追加；失败不改动原列表。单文件最多 64 MiB，列表最多 100,000 条。XML 在浏览器解析，点击发送后按原流程分批传输，元数据写入需要电脑端与 APK 同时更新至 v1.2.0。
4. 选择一台或多台已配对手机，点击发送；电脑按 500 条一批上传完整列表快照，全部上传完成后才创建手机任务。
5. 手机上检查预览、总条数和批次数，临时设为默认短信应用。只需确认一次，手机就会逐批下载并按原顺序写入，无需每批重复确认。
6. 电脑和手机显示累计写入进度。任何一批下载、写入或进度同步失败都会停止整个任务，已写入的短信保留。完成后在手机恢复原来的默认短信应用，再用原短信 App 查看。

例如 12,345 条短信会分成 25 批，前 24 批各 500 条，最后一批 345 条。手机只保留当前一批内容，不需要一次加载整个列表。

## 使用 Docker 启动电脑端（推荐）

需要 Docker 和 Docker Compose，无需在电脑安装 Python 或 Android SDK。

1. 将项目根目录的 `.env.example` 复制为 `.env`。
2. 编辑 `.env`，将 `SMS_ADVERTISE_HOST` 填为电脑的局域网 IPv4，例如 `192.168.1.20`。Mac 可在系统设置 → 网络 → 当前连接中查看；Windows 可运行 `ipconfig` 查看当前 Wi-Fi/以太网的 IPv4。
3. 在项目根目录执行：

```sh
docker compose up -d --build
docker compose logs desktop
```

复制日志中带 `#token=...` 的完整“电脑管理页面”链接到浏览器。手机与电脑连接同一局域网，在安卓工具中扫描网页上的二维码；不要扫描容器内部地址。

默认使用端口 `8765`。如端口被占用，在 `.env` 中修改 `SMS_PORT` 后重新执行启动命令；Compose 会同步修改监听、映射和二维码端口。电脑防火墙需允许手机访问该端口。电脑 IP 变化后更新 `.env` 并重新启动，手机重新扫码连接。

常用命令：

```sh
docker compose ps                    # 查看运行和健康状态
docker compose logs -f desktop       # 持续查看日志
docker compose down                  # 停止并移除容器，保留数据
docker compose up -d --build          # 更新镜像并重新启动
```

配对设备、令牌和任务保存在 `sms-data` 命名卷中，容器重建后保留。`docker compose down -v` 会删除该数据卷，仅在需要清空测试服务数据时使用。Docker 数据卷与原 Python 启动方式的 `.data` 目录独立；切换运行方式不会自动迁移旧数据。电脑编辑草稿仍保存在浏览器中。

如直接使用 `docker run`：

```sh
docker build -t android-sms-test-desktop .
docker run -d --name sms-test-desktop --init --restart unless-stopped \
  -p 8765:8765 -v sms-test-data:/app/.data \
  android-sms-test-desktop --advertise-host 192.168.1.20
docker logs sms-test-desktop
```

将示例 IP 替换为电脑真实局域网 IP。直接运行时也要保持内外端口一致；自定义端口需同时设置 `-p 8876:8876`、`-e SMS_PORT=8876` 和启动参数 `--port 8876`。

## 使用 Python 启动电脑端（不需要 Android SDK）

需要 Python 3.10 或更新版本。Mac / Linux：

```sh
./start-desktop.sh
```

Windows：双击 `start-desktop.cmd`，或在命令提示符中执行它。

首次启动只会在项目 `.venv` 内安装电脑端 Python 依赖。复制终端输出的完整管理链接到浏览器；链接中的令牌用于管理访问。电脑页面和手机连接不是同一个链接，手机应扫描页面上的二维码。

默认端口 `8765`。多网卡或 VPN 导致二维码地址不正确时，指定手机可访问的电脑局域网 IPv4：

```sh
./start-desktop.sh --advertise-host 192.168.1.20 --port 8765
```

允许系统防火墙接收此端口的局域网连接。若公司 Wi-Fi 开启设备隔离，即使网络名相同也可能无法连接，需要使用允许设备互访的测试网络。

## 取得安卓安装包：在 CI 构建

公开安装包见 [GitHub Releases](https://github.com/allenwangzero/android-sms-test/releases)。元数据导入使用协议 v3，需要电脑端与 APK 同时升级至 v1.2.0 或更新版本；旧 APK 无法获取新服务中的任务。已有电脑端数据库会保留，升级前请完成或停止正在执行的手机任务。

本机不需要安装 Android SDK。项目已提供 `.github/workflows/build.yml`：

1. 将项目放入你们的 GitHub 仓库。
2. 进入 Actions → **Test and build SMS tool**，手动运行或提交代码触发。
3. 构建成功后下载 **sms-lan-debug-apk** artifact，解压得到 APK。
4. 把 APK 安装到各测试机。只有首次安装/升级需要传安装包，日常短信列表通过局域网直接传输。

工作流在 GitHub 托管环境安装 SDK、运行检查并打包，不会在你的电脑安装 SDK。仓库地址为 [allenwangzero/android-sms-test](https://github.com/allenwangzero/android-sms-test)，构建状态和安装包见仓库的 Actions 页面。

这是测试用 debug APK。不同 CI 运行若使用不同调试签名，旧版本可能无法直接覆盖安装；正式长期分发时应由你们的构建系统配置固定签名。卸载会清除手机端的配对和任务记录，但不会清除已写入系统收件箱的短信。

已有安卓开发环境也可执行 `./gradlew :app:assembleDebug :app:lintDebug`；产物位于 `app/build/outputs/apk/debug/app-debug.apk`。要求 JDK 17、SDK Platform 35、Build Tools 34.0.0，项目使用 Gradle 8.9 / AGP 8.7.3。

## 编辑与发送

- 每个完整任务 1–100000 条，自动拆为每批最多 500 条；每条发送人 1–100 字符，正文 1–4000 字符，时间为有效日期。单次 HTTP 请求最多 16 MiB，完整列表的紧凑 JSON 数据最多 256 MiB，长正文可能先达到大小上限。
- 发送人是短信地址，可填测试号码或字母标识；不会创建通讯录联系人，最终显示由手机短信 App 决定。
- 随机内容默认带 `【测试】` 标记。自定义内容按编辑后的原文写入，不会被工具自动改写。
- XML 中的 `protocol`、`type`、`subject`、`service_center`、`read`、`status`、`locked` 会随原始短信保存、传输并写入 Android；继续仅接受 `type="1"` 收件短信。电脑列表与手机确认预览展示 `read`、`status`、`locked` 的含义和数值。
- 未提供元数据时默认 `type=1`、`protocol=0`、`read=1`、`status=-1`、`locked=0`，`subject` 和 `service_center` 为 null。XML 中可空属性的 `"null"` 转为真正空值；Android 写入时 `seen` 与 `read` 一致。
- `toa`、`sc_toa` 在 Android 标准短信表中没有对应列，仅接受缺省或 null，非空时明确拒绝导入，不会静默丢弃。它们的空值保留在任务中，不向系统提交未知列。依据：[AOSP 短信表定义](https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/refs/heads/main/src/com/android/providers/telephony/MmsSmsDatabaseHelper.java)。
- 电脑修改草稿不会改变已经开始上传或已发送的任务。需要新内容时发送新的完整任务。
- 电脑发送仅代表任务已创建，手机必须确认才写入。手机号不是网络短信接收目标，不会产生运营商短信费用。
- 手机保持工具在前台以接收任务、执行写入和反馈结果；不实现后台常驻服务。

## 重试与中断

- 电脑上传遇到网络错误时，按页面提示重试同一快照；服务端用请求 ID 和批次索引去重，已收到的批次无需重传。未上传完整的任务不会出现在手机上，刷新网页后可以恢复待发送快照。
- 需要修改被拒绝的内容时，选择“放弃未提交上传”，服务确认后释放待发送记录并保留编辑草稿。已提交的手机任务不能通过此入口取消；被放弃的旧上传 ID 不能再次提交。
- 手机处理任务使用持久化任务 ID。确认一次后顺序拉取批次，每批结束同步总进度，再继续下一批；进度同步失败也会停止，不会继续写入后面的批次。
- 下载、写入失败或离开应用前台时停止，已写入短信保留。失败后的结果反馈可重试，但不会自动续写；未确认写入数量不表示每条都尝试过。网络异常时电脑显示的是最后收到的进度，以手机反馈及系统收件箱核对为准。
- 手机进程在写入中被终止时，本地会将任务视为中断，不自动续写；恢复连接后反馈给电脑。短信数据库和工具本地记录无法跨进程原子提交，中断时已记录成功数可能少于实际写入数，应先检查收件箱。
- 电脑端使用本地 SQLite 保存任务和配对设备，重启后保留。不要把 `.data` 或带令牌的管理链接分享给无关人员。

## 安卓与网络边界

- 支持的最低安卓版本为 Android 8.0（API 26）；无需 root。仍需在实际品牌和版本上验证默认短信角色及收件箱展示。
- 安卓要求默认短信应用才能写入系统短信数据库。工具不能跳过系统授权；生成前临时切换，完成后恢复原应用。
- 临时接管期间会尝试保存收到的真实普通短信，但不提供短信通知；不支持发送短信、短信拒接和下载/保存彩信。因此仅用于测试机，并在完成后及时切回。
- 导入是直接写收件箱，不模拟运营商短信到达，不触发 `SMS_RECEIVED` 广播。
- 局域网使用 HTTP，配对码和设备令牌用于访问控制，不能提供链路加密。第一版用于可信测试网络，不应通过公网端口映射暴露。

## 验证

```sh
.venv/bin/python -m unittest discover -s tests -p 'test_*.py' -v
node --check desktop/static/app.js
sh scripts/test.sh
```

浏览器批次持久化的开发测试（Node 仅用于测试，不是电脑服务的运行依赖）：

```sh
npm install --prefix /tmp/sms-pending-test --ignore-scripts --no-audit --no-fund fake-indexeddb@6.2.5 jsdom@26.1.0
node scripts/test-pending-storage.cjs /tmp/sms-pending-test/node_modules/fake-indexeddb
node scripts/test-xml-import.cjs /tmp/sms-pending-test/node_modules/jsdom
```

电脑端自动化检查覆盖认证、配对、多设备任务、分块上传、请求幂等、状态和数量校验、持久化。浏览器测试覆盖并发页面、刷新恢复、缺块重传和失败停止。纯 Java 测试覆盖手机配对地址校验、顺序批次执行和失败停止，无需 SDK。Android 原生编译与 lint、Docker 镜像启动与接口测试由 CI 执行。

构建结果见 [GitHub Actions](https://github.com/allenwangzero/android-sms-test/actions)。手机侧协议和批次执行使用模拟客户端/写入器验证；真机扫码、授权、写入性能及收件箱效果仍需验收。

真机验收：

1. 两台测试机扫码，电脑显示两台设备，分别选择/同时发送。
2. 编辑一条发送人和正文，发送后再修改电脑草稿，确认手机仍收到发送时的快照。
3. 拒绝默认角色授权时不能写入；授权后写入 1 条和 10 条，原短信保留、增量正确。
4. 生成 12,345 条，手机确认一次，验证按 25 批处理、总进度与内容顺序一致；旋转屏幕不会重新确认或重复写入。
5. 写入较大批次时切换默认短信应用或强制结束进程，检查部分结果/中断提示，不自动续写。
6. 恢复原默认短信应用，确认能查看新增记录；按接收日期查找，过去时间的短信可能不在顶部。
7. 导入 `read=0`、`status=64`、`locked=1` 且包含非空 `subject/service_center/protocol` 的测试短信，核对电脑、手机预览和系统数据库字段一致，再验证 null 值写入。
8. 中途断开 Wi-Fi 或退出前台，确认停止整个任务；恢复网络只同步结果，不自动续写。电脑上传到一半刷新页面，重试应只补充缺失批次，并且仅创建一组任务。

接口定义见 [PROTOCOL.md](PROTOCOL.md)。安卓平台依据：[Telephony](https://developer.android.com/reference/android/provider/Telephony)、[默认应用角色](https://developer.android.com/reference/android/app/role/RoleManager)。
