# Windows PowerShell 复现入口

从项目根目录执行，先安装 Docker Desktop、Java 8 JDK 和 Maven。

```powershell
$env:JAVA_HOME = '你的 Java 8 JDK 目录'
./scripts/start-infra.ps1
./scripts/init-demo-db.ps1
mvn clean package
./scripts/start-app.ps1
```

`start-app.ps1` 在后台启动本项目 JAR，绑定 `127.0.0.1:18081`，开启本机 demo profile，日志与进程记录保存在 `target/runtime/`。默认 Caffeine 模式、订单超时 60 秒、MQ 延迟等级 5（1 分钟）。演示登录无需短信，只用于本机合成数据体验。

停止应用用 `./scripts/stop-app.ps1`。它同时核对进程 ID、启动时间及本项目 JAR 完整路径；不会通过进程名批量停止其他 Java。先停止应用，再执行 `./scripts/stop-infra.ps1`；数据库、Redis 和 MQ 的命名卷保留。

## 三组缓存对照

```powershell
./scripts/start-app.ps1 -CacheMode mysql -OrderTtlSeconds 600 -CloseDelayLevel 14
./scripts/run-loadtest.ps1 -Scenario hot-read -CacheMode mysql -Threads 20 -Loops 10
./scripts/run-loadtest.ps1 -Scenario hot-read -CacheMode mysql -Threads 20 -Loops 100
./scripts/stop-app.ps1
```

依次把两个位置的 `mysql` 换成 `redis`、`caffeine`，每组重新启动相同 JAR、预热后记录。应用脚本中的 `CacheMode` 设置真正的服务端模式，压测脚本中的 `CacheMode` 仅标注实验结果。

JMeter 未安装时先执行 `./scripts/install-jmeter.ps1`，已有工具时通过 `-JMeterHome` 指向包含 `bin` 的安装目录。首次演示和压测的其他参数见 [loadtest/README.md](../loadtest/README.md)。

## 独立正确性检查

- `./scripts/test-redis-lua.ps1`：独立测试 key，验证实际库存与限流 Lua，不修改业务数据。
- `./scripts/check-order-invariants.ps1 -VoucherId 101 -InitialStock 30`：只读查询 MySQL 和 Redis 的订单与库存，不重置演示数据。

每次抢券压测前确认本轮活动和账号没有历史参与记录。已存在的订单、过期关单和其他用户操作都会改变库存，不能直接拿运行过多轮的数据证明没有超卖。
