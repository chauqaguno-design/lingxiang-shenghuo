# 邻享生活前端

本目录是与 Spring Boot 同源部署的静态前端。首页使用本地 Vue 2 与原生 Fetch，不需要 npm 构建，也不依赖外部 CDN。

## 页面与部署

- `/` 或 `/index.html#/home`：商户分类、商户列表、已加载商户筛选、优惠券展示。
- `/index.html#/shop/1`：商户详情、真实优惠券、使用规则、限时抢购。
- `/index.html#/orders`：我的订单、待支付倒计时、模拟支付。
- Maven 将本目录复制到 jar 的 `static/`；请通过后端端口访问，不要直接双击 HTML。
- 静态目录、首页和查询接口由后端放行，业务请求使用根路径，如 `/shop-type/list`，没有 `/api` 前缀。
- HTML 别名入口统一重定向到首页路由。主页面只加载 `js/vue.js`、`js/linxiang.js`、`css/linxiang.css`。

## 演示流程

1. 启动带演示配置的后端，打开首页。
2. 点击“登录体验”，输入演示手机号（默认 `13800000001`）。这是演示账号，无验证码。
3. 打开商户、阅读优惠券规则、点击“立即抢购”。提交后展示“正在确认”，最多自动查询约 30 秒；尚未确认可手动查询或稍后查看订单。
4. 后端确认建单且订单为待支付时，才出现“模拟支付”按钮。模拟支付不会产生真实资金交易。
5. 订单过期时前端停止提供支付入口，但只有后端返回取消状态后才显示“已取消”。

## 状态约定

- `POST /demo/login` 请求 `{phone}`，成功返回 token 字符串。后续请求使用 `authorization: token`。
- `POST /voucher-order/seckill/{voucherId}` 成功返回订单 ID 字符串。这仅代表提交成功。
- `GET /voucher-order/result/{orderId}`：`PREPARING`、`RESERVED` 为处理中；`SUCCEEDED` 且 `orderStatus=1/2/3` 才显示已确认；`FAILED` 或 `orderStatus=4` 显示失败/关闭。
- `GET /voucher-order/my` 返回订单列表，`status=1` 待支付、`2` 已支付、`4` 已取消。
- `POST /voucher-order/{orderId}/pay` 为模拟支付。
- Long 类型 ID 必须由后端序列化为字符串，防止浏览器丢失精度；金额是“分”，支持字符串形式。
- 券展示信息可能来自缓存，因此不展示“实时剩余库存”，也不根据展示库存为零直接禁止请求。实际资格与库存由后端提交接口裁决。
- 网络异常或提交超时显示“结果尚未确认”，不自动重发写请求；429 显示稍后再试，401 清除过期凭证并打开登录框。
- 每次抢购操作携带 `X-Request-Id`；因网络异常而手动重试时复用原标识，配合后端请求幂等。
- 退出先调用 `POST /user/logout` 注销服务端凭证，再清理本地会话。网络失败仍在本机退出，并明确提示服务端注销尚未确认。
- 搜索明确只筛选已加载商户；首页优惠来自当前分类已加载的前 6 家商户，最多展示 4 张，不伪造商户/销量/评价或成功订单。

## 验证

```powershell
node --check frontend/js/linxiang.js
node --test frontend/test/state.test.cjs
# 本地 demo 服务就绪后执行，会使用券 105 创建新演示订单并模拟支付。
python frontend/test/http-smoke.py --base-url http://localhost:18081
```

测试覆盖处理中不误报成功、长整型 ID、分类请求竞态、401/429/网络失败、轮询超时、支付状态保护等。浏览器还应按桌面与手机尺寸检查首页、详情、登录、抢购结果、订单和模拟支付。不要把测试目录打包为静态资源。

## 素材说明

`assets/brandmark.svg`、`neighborhood.svg`、`shop-placeholder.svg` 为本项目绘制的矢量图，不代表真实店铺照片。商户图片加载失败时使用店铺插画占位。商户和优惠券信息从后端查询。Vue 2.5.16 保留版权头，MIT 许可见 `THIRD_PARTY_NOTICES.txt`。
