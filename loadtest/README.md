# 邻享生活 JMeter 压测

本目录保存可以复现的测试计划。请只对自己的本机演示服务压测。

## 环境与工具

- JMeter 固定 5.6.3，下载自 Apache 官方地址并核验官方 SHA512；Java 8+。
- 应用：`http://127.0.0.1:18081`，必须连接真实 MySQL、Redis、RocketMQ。
- `scripts/start-infra.ps1` 启动项目独立容器。数据库和演示数据按项目 README 初始化。
- 压测采用 CLI 模式，运行时不要同时打开 JMeter GUI 或调试断点。

```powershell
./scripts/install-jmeter.ps1
$env:JAVA_HOME = '你的 JDK 目录'
./scripts/run-loadtest.ps1 -Scenario hot-read -Threads 20 -Loops 50 -Path '/voucher/list/1'
./scripts/run-loadtest.ps1 -Scenario seckill -Threads 50 -Loops 1 -VoucherId 101 -UserBase 10000000
```

如 JMeter 已放在其他目录，向 `run-loadtest.ps1` 传 `-JMeterHome`；传的是包含 `bin` 的目录。

三种展示缓存模式通过应用启动参数 `--app.voucher-cache.mode=mysql`、`redis`、`caffeine` 切换。每次只运行一个应用进程，使用相同数据、相同 JVM 参数，先用 20 线程 × 10 次预热，再用 20 线程 × 100 次记录。`run-loadtest.ps1 -CacheMode mysql` 仅给结果标注模式，不会修改服务端配置。

## 两个计划测什么

| 计划 | 行为 | 如何理解结果 |
| --- | --- | --- |
| `hot-read.jmx` | 并发访问指定商户/优惠券展示 GET 接口，断言 Result.success 和数据非空 | 用于观察热点读取延迟；先预热再正式记录 |
| `seckill.jmx` | 每个线程用不同演示手机号登录，然后抢同一张券 | 登录与抢券分别统计；需要继续核对异步订单落库 |

抢券手机号由 `188` + 八位 `UserBase + 线程编号` 组成。每轮使用新的 `UserBase` 和专用优惠券，防止历史订单污染结果。`Loops > 1` 可以观测重复购买和用户限流，不能把这些拒绝算成系统异常。

结果输出到 `loadtest/results/<时间>-<场景>/`：

- `samples.jtl`：逐请求时间、耗时、响应码，以及 `businessOutcome`、订单 ID、业务拒绝原因；不保存登录 token 或完整响应。
- `summary.json`：每个接口的均值、P95/P99、吞吐、异常数和业务结果分类。
- `run.json`：线程、轮次、活动等参数。
- `dashboard/index.html`：JMeter HTML 报告。

`accepted` 仅代表抢券请求已受理。库存不足、重复抢券等返回 `rejected`；HTTP 429 且响应符合 Result 格式时记为 `rate_limited`。原始 JMeter 失败标记不修改，因此 CLI/HTML 报告会把 429 计为失败；`summary.json` 同时列出原始失败率、HTTP 非 2xx 比例、429 比例、业务拒绝率和排除预期 429 后的系统异常率。必须看 `rejectionMessages`，不能把全部 HTTP 200 当成抢券成功。脚本保留未知拒绝原因供人工确认；503、5xx、网络失败仍算异常。

## 必须补做的正确性验收

开始前记录活动初始库存，使用独立活动并留够支付有效期；测超卖时先关闭用户操作，避免退款、关单污染数据。待 MQ 消费稳定后检查：

1. 本轮受理订单是否均有最终状态；不能只看 JMeter 请求已经结束。
2. `SELECT user_id, voucher_id, COUNT(*) FROM tb_voucher_order WHERE voucher_id = <本轮券ID> GROUP BY user_id, voucher_id HAVING COUNT(*) > 1;` 必须无结果。
3. 数据库库存和 Redis 库存都不为负；在没有取消/补货的轮次中，`初始库存 = 剩余库存 + 已创建订单数`。
4. 单独测试重复投递同一订单、重复关单；检查不会重复扣减或重复回补。
5. 单独测试模拟支付与超时关单的竞争，确认一个订单只有一个最终状态。

单机压测应同时记录 CPU/内存、JVM 堆、数据库/MQ版本、并发数、请求数、数据规模、预热方式。首次编译脚本、首次建连接和机器上的其他程序都会影响结果；这些结果不是生产容量承诺。

## 真实 Redis 脚本验证

```powershell
./scripts/test-redis-lua.ps1
```

脚本加载项目实际的预占、释放及限流 Lua，在独立测试 key 上验证幂等、库存边界和一人一券等行为，结束后精确清理测试 key。逐项 JSON 结果写入 `results/`，已执行的可公开证据放在 [evidence](evidence/README.md)。

## 官方资料

- [JMeter 官方下载与校验](https://jmeter.apache.org/download_jmeter.cgi)
- [JMeter CLI 与 HTML 报告](https://jmeter.apache.org/usermanual/get-started.html#non_gui)
- [RocketMQ 4.x Docker Compose](https://rocketmq.apache.org/docs/4.x/quickstart/03quickstartWithDockercompose/)
