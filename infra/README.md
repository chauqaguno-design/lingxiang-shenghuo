# 本机开发依赖

需要 Docker Desktop 的 Linux containers 模式。应用运行在宿主机，端口为 18081。

```powershell
./scripts/start-infra.ps1
./scripts/init-demo-db.ps1  # 只在空数据库导入一次，已有表时主动停止
```

| 服务 | 固定版本 | 宿主机地址 | 演示身份 |
| --- | --- | --- | --- |
| MySQL | 8.0.43 | `127.0.0.1:13306/linxiang` | `linxiang` / `linxiang_demo_password` |
| Redis | 7.2.10 | `127.0.0.1:16379` | 本机无密码 |
| RocketMQ NameServer | 4.9.6 | `127.0.0.1:19876` | 本机演示未启用 ACL |
| RocketMQ Broker | 4.9.6 | `127.0.0.1:10911`（VIP 10909） | 由 NameServer 发现 |

所有映射仅绑定 `127.0.0.1`；配置中的密码为公开演示值。此 Compose 用于开发和复现，不应直接开放公网。MySQL root 演示密码为 `linxiang_root_demo_password`，应用使用普通 `linxiang` 用户。

每个服务使用本项目专属 `linxiang_*` 命名卷，容器名以 `linxiang-` 开头。Broker 目录初始化服务只为本项目新卷设置 rocketmq 用户所有权，Broker 进程仍以普通用户运行。MQ 的 `brokerIP1=127.0.0.1` 专门适配宿主机 Java 应用；如果把应用也放入容器，需要改成应用可访问的 Broker 地址。

停止服务：`./scripts/stop-infra.ps1`，数据卷保留。脚本不包含删卷命令，不会操作其他项目容器。

检查健康及 Broker 注册：

```powershell
docker compose -f infra/compose.yaml ps
docker exec linxiang-broker sh mqadmin clusterList -n namesrv:9876
```

采用单节点 MQ、异步刷盘和有限的内存，是为了在学习机器上完整运行真实链路；这不是高可用生产部署。进程崩溃与消息持久性边界需要在项目可靠性说明中区分。

参考：[RocketMQ 官方 4.x Docker Compose](https://rocketmq.apache.org/docs/4.x/quickstart/03quickstartWithDockercompose/)。
