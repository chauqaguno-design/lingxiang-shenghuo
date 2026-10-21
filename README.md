# 邻享生活 · Linxiang Life

面向本地商户优惠活动的服务平台，提供商户查询、优惠券抢购、订单查询和模拟支付。围绕热点查询与限量抢券，实现缓存、限流、异步下单及超时关单。

![Java 8](https://img.shields.io/badge/Java-8-437291) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-2.3.12-6DB33F) ![RocketMQ](https://img.shields.io/badge/RocketMQ-4.9.6-D77310)

## 功能

- 商户查询：空值缓存；指定热点商户使用逻辑过期、互斥异步重建。
- 优惠券展示：Caffeine + Redis 两级缓存，支持 MySQL / Redis / Caffeine 三种查询模式对比。
- 抢券资格：Redis Lua 原子校验活动时间、剩余库存、一人一券。
- 异步下单：持久化请求 + 本地消息表 + RocketMQ；数据库条件扣减与联合唯一键兜底。
- 请求限流：注解 + AOP + Redis ZSet/Lua，按用户和活动限制重复请求。
- 订单管理：结果查询、模拟支付；延迟消息关单，定时任务补偿，库存按订单幂等回补。
- 前端：本地 Vue、响应式页面、原创 SVG 插画，无需额外 Node 构建即可运行。

这是单体应用加中间件的学习与演示项目。默认只监听本机；没有接入真实短信、支付、商户后台或 AI。

## 本地运行

准备 Java 8、Maven 3.9、Docker Desktop；Windows 使用 PowerShell 7。

```powershell
git clone https://github.com/chauqaguno-design/lingxiang-shenghuo.git
cd lingxiang-shenghuo
# JAVA_HOME 指向你自己的 JDK 8
.\scripts\start-infra.ps1
.\scripts\init-demo-db.ps1
mvn clean package
.\scripts\start-app.ps1
```

打开 **http://127.0.0.1:18081**，点击“登录体验”，使用任意格式正确的演示手机号。数据库仅含合成商户；所有优惠券均不可真实核销。demo 默认支付期限 60 秒。

初始化脚本只接受空数据库，不覆盖已有订单。Docker 服务端口为 MySQL 13306、Redis 16379、NameServer 19876、Broker 10911/10909。

```powershell
.\scripts\stop-app.ps1
.\scripts\stop-infra.ps1
```

停止基础设施默认保留命名卷。不要单独清空业务 Redis 库存或请求记录：存在预占时，需要先停止活动并对账。

## 目录

| 目录 | 内容 |
|---|---|
| src/main/java/com/linxiang | 后端业务代码 |
| src/main/resources/db/linxiang.sql | 表结构与合成演示数据 |
| frontend | 页面、本地依赖、SVG、前端状态测试 |
| infra | Docker Compose 与 RocketMQ 配置 |
| scripts | 启停、初始化、JMeter、库存核对脚本 |
| loadtest | JMX 测试计划、可公开的实测证据 |
| docs | 设计、学习顺序、接口、面试与实测报告 |

## 验证与阅读

```powershell
mvn test
node --test frontend/test/state.test.cjs
.\scripts\test-redis-lua.ps1
# 首次下载 JMeter
.\scripts\install-jmeter.ps1
.\scripts\run-loadtest.ps1 -Scenario hot-read -Threads 20 -Loops 100
```

- [从已有知识看实现](docs/设计与学习路线.md)
- [接口与状态](docs/接口说明.md)
- [简历描述与追问](docs/简历与面试准备.md)
- [JMeter 运行说明](loadtest/README.md)
- [实际压测报告](docs/压测报告.md)

## 实现边界

1. 入口先写一条数据库请求记录，再执行 Redis 预占。这样可以恢复中断请求，但并非完全绕开 MySQL 的极限吞吐方案。
2. MQ 为至少一次投递；“不重复创建订单”来自消费者数据库事务和幂等判断，不是 MQ 自动提供 exactly-once。
3. 两级缓存用于展示，不能用缓存里的库存判定抢购。常规更新时其他实例的本地缓存等待 5 秒过期；并发回源与删除交错时，旧值可能经历 Redis 60 秒 TTL 及之后的 L1 窗口。
4. 当前 Redis 是单节点。迁移 Redis Cluster 前，Lua 涉及的多个 key 必须设计同槽。
5. 延迟消息允许晚到，扫描任务负责补关单。Redis 故障期间可先完成数据库关单，库存缓存随后通过消息表重试回补。
6. 依赖版本面向现有 Java 8 学习环境。面向公网生产部署前需要升级支持版本，增加真实认证、管理权限、监控与容量规划。

第三方前端依赖许可证见 [frontend/THIRD_PARTY_NOTICES.txt](frontend/THIRD_PARTY_NOTICES.txt)。
