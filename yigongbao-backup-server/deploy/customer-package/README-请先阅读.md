# 医工宝备份系统 Windows 部署说明

本交付包包含后端 JAR、前端生产构建文件、Nginx/Windows、数据库初始化脚本和运行脚本。客户无需另外安装 Nginx；Java 和 MySQL 仍需在部署前准备好。

## 一、运行环境

- Windows 10/11 或 Windows Server。
- 64 位 JDK 21。Java 8、Java 17 不能运行本交付包。
- MySQL 8，当前支持 MySQL 8.0 和 8.2。
- 交付包已内置 64 位 Nginx/Windows，默认监听 `80` 端口。
- MySQL 服务已经启动，且部署人员拥有一个具备建库、建表、建索引和写入初始数据权限的管理账号。

Java 21 可以与机器上已有的 Java 8/17 并存。本系统通过 `start-backup.bat` 中的 `JAVA_HOME_CUSTOM` 指定自己的 JDK，不会要求修改其他系统使用的 Java。

## 二、目录说明

```text
yigongbao-backup-server-windows/
├─ yigongbao-backup-server.jar    后端程序
├─ frontend\dist\                前端生产构建文件
├─ nginx\
│  ├─ nginx.exe                   Nginx/Windows
│  ├─ conf\nginx.conf             当前系统的单项目配置
│  ├─ conf\mime.types             MIME 类型配置
│  └─ logs\                       Nginx 访问与错误日志
├─ .env                           客户现场运行配置（首次运行前必须修改）
├─ .env.example                   配置模板备份
├─ init-database.bat              初始化数据库
├─ start-backup.bat               启动后端并实时显示日志
├─ stop-backup.bat                停止后端
├─ start-nginx.bat                校验配置并启动前端
├─ restart-nginx.bat              重启 Nginx（修改配置后使用）
├─ stop-nginx.bat                 停止 Nginx
├─ check-status.bat               检查前端和后端状态
├─ README-请先阅读.md              本说明
├─ 版本信息.txt                    版本与构建信息
├─ SHA256校验.txt                  文件完整性校验值
└─ sql\init\                     数据库初始化 SQL
   ├─ yigongbao-backup-full-init.sql  默认执行的全量初始化脚本
   └─ 00～04 分段脚本                全量脚本缺失时的自动兜底
```

运行后会自动创建：

- `log`：运行日志和压缩归档。
- `run`：进程号、启动参数及临时文件。

请将整个目录解压到固定位置后再运行，不要直接在压缩包内双击脚本。建议目录：

```text
D:\YiGongBao\backup-system
```

## 三、配置生产环境

使用记事本或其他纯文本编辑器打开 `.env`，至少修改以下内容：

```dotenv
SPRING_PROFILES_ACTIVE=prod
BACKUP_SERVER_PORT=18080

BACKUP_DB_URL=jdbc:mysql://127.0.0.1:3306/yigongbao_backup?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia%2FShanghai
BACKUP_DB_USERNAME=yigongbao_backup_app
BACKUP_DB_PASSWORD=请填写应用数据库账号密码

BACKUP_MAX_UPLOAD_SIZE=2GB

BACKUP_SHARED_HMAC_SECRET=请填写与线上医工宝完全一致的至少32位随机密钥
BACKUP_EXTERNAL_ALLOWED_HOSTS=请填写实际COS或OSS域名

BACKUP_EXECUTION_MODE=REAL
BACKUP_SIMULATION_ROOT=
```

注意：

1. 数据库名必须保持 `yigongbao_backup`，否则初始化脚本会拒绝执行。
2. `BACKUP_EXTERNAL_ALLOWED_HOSTS` 只填写主机名，不填写 `http://`、`https://` 或路径；多个主机使用英文逗号分隔。
3. 生产环境必须使用 `BACKUP_EXECUTION_MODE=REAL`。
4. `.env` 含密码和密钥，不得通过聊天、邮件或公共网盘传播，也不要提交到代码仓库。
5. `.env` 中的应用数据库账号与初始化时输入的 MySQL 管理账号可以不同。

应用数据库账号应由 DBA 创建并按最小权限授权。参考权限为：

```sql
CREATE USER 'yigongbao_backup_app'@'实际来源地址' IDENTIFIED BY '强随机密码';
GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE
ON yigongbao_backup.* TO 'yigongbao_backup_app'@'实际来源地址';
```

不要直接照抄“实际来源地址”和示例密码，应由现场 DBA 按部署机器地址及安全规范替换。

## 四、初始化数据库

1. 确认 MySQL 服务已经启动。
2. 双击 `init-database.bat`。
3. 根据提示输入 MySQL 管理账号密码；输入密码时不会显示字符。
4. 看到 `[OK] 数据库初始化成功` 后关闭窗口。

脚本默认执行 `sql\init\yigongbao-backup-full-init.sql`。该文件缺失或执行报错时，会自动按顺序组合并执行 `00` 至 `04` 分段脚本兜底，客户无需手工逐个运行 SQL。报错切换到兜底方案时，按提示再次输入 MySQL 管理账号密码即可。

脚本默认使用 `root` 作为初始化账号。需要调整时，用文本编辑器修改 `init-database.bat` 顶部：

```bat
set "MYSQL_HOME_CUSTOM="
set "MYSQL_ADMIN_USER=root"
```

- MySQL 已加入系统 `PATH` 时，`MYSQL_HOME_CUSTOM` 保持为空。
- 未加入 `PATH` 时，填写 MySQL 安装根目录，例如 `C:\Program Files\MySQL\MySQL Server 8.2`。
- 初始化脚本可以重复执行，不会清空已有业务数据。
- 已投入使用的数据库升级不能只重复运行初始化脚本，应使用版本对应的迁移方案。

## 五、配置前端和 Nginx

默认配置已经可以直接使用：

- 前端访问地址：`http://服务器IP/`
- Nginx 监听端口：`80`
- 后端代理地址：`http://127.0.0.1:18080`
- 前端目录：交付包内的 `frontend\dist`

需要修改前端实际目录时，打开 `nginx\conf\nginx.conf`，修改 `root`：

```nginx
# 使用交付包内目录（默认）
root ../frontend/dist;

# 或改为客户现场的绝对目录，Windows 路径使用正斜杠
root D:/YiGongBao/frontend/dist;
```

如后端端口不是 `18080`，同步修改 `location /api/` 中的 `proxy_pass`。如 `80` 端口被占用，修改 `listen 80`，并在 `start-nginx.bat`、`check-status.bat` 中同步修改 `FRONTEND_URL`。本配置不包含其他项目，也不加载 `conf.d` 等外部配置。

## 六、启动后端

用文本编辑器打开 `start-backup.bat`，将 `JAVA_HOME_CUSTOM` 改为客户机器的 JDK 21 根目录：

```bat
set "JAVA_HOME_CUSTOM=C:\Program Files\Java\jdk-21"
set "REQUIRED_JAVA_MAJOR=21"
```

然后双击 `start-backup.bat`：

- 后端进程在后台运行。
- 当前窗口实时显示日志。
- 按 `Ctrl+C` 或关闭日志窗口只会停止日志显示，不会停止后端服务。
- 不要重复启动；脚本检测到已有实例后只会继续显示日志。
- 脚本会自动为 Windows JDK 配置短 NIO 临时目录，避免用户临时目录过长导致 Tomcat 无法启动。

浏览器访问以下地址检查健康状态：

```text
http://127.0.0.1:18080/actuator/health
```

端口改变时，请同步替换上面的 `18080`，并确认 Windows 防火墙及现场网络策略允许访问。

## 七、启动前端

确认后端健康后，双击 `start-nginx.bat`。脚本会先执行 `nginx -t` 校验配置，校验通过后才启动 Nginx；重复执行不会创建重复实例。

浏览器访问：

```text
http://127.0.0.1/
```

其他电脑访问时把 `127.0.0.1` 换成服务器 IP，并确认 Windows 防火墙允许配置的 Nginx 监听端口。前端固定通过同源 `/api/v1` 访问后端，客户浏览器不需要直接访问 `18080`。

修改 `nginx.conf` 或替换 `frontend\dist` 后，双击 `restart-nginx.bat`。停止前端服务时双击 `stop-nginx.bat`。

双击 `check-status.bat` 可同时检查：

- 前端首页能否由 Nginx 正常返回；
- 后端 `/actuator/health` 是否为 `UP`。

## 八、停止后端

双击 `stop-backup.bat`。看到以下提示表示停止成功：

```text
[OK] Backup server stopped.
```

不要通过删除 `run` 目录代替停止脚本，也不要直接结束不确定归属的 Java 进程。

## 九、日志策略

日志位于交付目录下的 `log` 文件夹：

```text
log\yigongbao-backup-server.log
```

- 单个日志文件最大 100MB。
- 按日期和文件大小切片并使用 GZip 压缩。
- 最多保留 30 天。
- 压缩归档总量最多 2GB。
- `console.log` 和 `console-error.log` 用于保留后台进程的标准输出和错误输出。

排查问题时，请同时提供问题发生时间、操作步骤以及对应时间段的日志，发送前注意检查其中是否包含现场敏感信息。

Nginx 日志位于：

```text
nginx\logs\access.log
nginx\logs\error.log
```

## 十、首次启动后的必要检查

1. 双击 `check-status.bat`，确认前端可访问且后端健康状态为 `UP`。
2. 确认 `log` 目录持续产生运行日志且没有数据库连接错误。
3. 确认系统配置中的 `online.base-url` 已从默认本机地址改为真实线上医工宝接口地址。
4. 确认 `online.enabled`、自动备份周期、重试策略和容量阈值符合现场要求。
5. 通过存储卷管理纳管热备盘、冷备盘或 NAS，不要直接修改数据库伪造存储卷。
6. 首次登录后立即修改初始化管理员密码。
7. 正式开放自动备份及云端清理前，先完成一轮小范围试运行和恢复验证。

## 十一、常见问题

### 提示找不到 Java

修改 `start-backup.bat` 顶部的 `JAVA_HOME_CUSTOM`，填写 JDK 21 根目录，不要填写到 `bin` 或 `java.exe`。

### 提示找不到 mysql.exe

修改 `init-database.bat` 顶部的 `MYSQL_HOME_CUSTOM`，或者将 MySQL 的 `bin` 目录加入系统 `PATH`。

### 数据库初始化失败

检查 MySQL 服务、主机、端口、管理账号密码和权限；确认 `.env` 中数据库名为 `yigongbao_backup`。

### 启动后立即退出

查看 `log\console-error.log`、`log\console.log` 和 `log\yigongbao-backup-server.log`。常见原因包括数据库账号无权访问、端口被占用、JDK 版本过低以及 `.env` 必填项未配置。

### 如何确认后台仍在运行

再次双击 `start-backup.bat`。如果实例仍在运行，脚本会显示已有进程号并继续展示日志，不会重复创建服务进程。

### Nginx 配置校验失败

查看脚本输出和 `nginx\logs\error.log`。如果修改了前端目录，确认 `root` 使用正斜杠、目录存在且其中包含 `index.html`。

### 前端可以打开但接口请求失败

先双击 `check-status.bat`。确认后端已启动、`nginx.conf` 的 `proxy_pass` 端口与 `.env` 的 `BACKUP_SERVER_PORT` 一致，并确认 `/api/` 路径没有被其他代理规则覆盖。

### 80 端口被占用

使用 `netstat -ano | findstr ":80"` 查找占用进程。可以停止冲突服务，或修改 `nginx.conf` 的 `listen` 端口，并同步修改两个 BAT 中的 `FRONTEND_URL`。修改后运行 `restart-nginx.bat`。
