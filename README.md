# open-api-access-service-java

对外开放的数据接入 API（Spring Boot 3.4 / Java 21）。数据、密钥、配额、幂等记录全部保存在本地内存中，
重启即清空；但在单次运行周期内，所有规则在异常输入、并发请求和多版本调用下都给出一致、可解释的结果。

## 一、接口总览

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/admin/keys` | `X-Admin-Token` | 签发调用方密钥 |
| POST | `/admin/keys/{keyId}/revocation` | `X-Admin-Token` | 吊销密钥（立即生效） |
| GET | `/admin/keys` | `X-Admin-Token` | 列出全部密钥 |
| POST | `/api/data/write` | `X-Api-Key` + `data:write` | 幂等写入一条数据 |
| GET | `/api/data/{recordId}` | `X-Api-Key` + `data:read` | 读取单条（仅本密钥名下） |
| GET | `/api/data` | `X-Api-Key` + `data:read` | 列出本密钥名下数据 |
| GET | `/api/quota` | `X-Api-Key` + `data:read` | 查询当前配额状态与重试等待 |

管理令牌默认 `dev-admin-token`，可用 `app.admin-token` 覆盖。

## 二、鉴权模型

调用方密钥由管理面签发，密钥形如 `sk_xxxx`，并可携带权限范围集合：

- `data:write`：允许 `POST /api/data/write`
- `data:read`：允许读取数据与查询配额

鉴权在拦截器中按固定顺序执行，四种失败原因对应四个**互相独立、稳定不变**的错误码，调用方可以程序化区分：

| 场景 | HTTP | code |
| --- | --- | --- |
| 未携带 `X-Api-Key` | 401 | `1001` API_KEY_MISSING |
| 密钥不存在 | 401 | `1002` API_KEY_INVALID |
| 密钥已吊销 | 401 | `1003` API_KEY_REVOKED |
| 权限范围不足 | 403 | `2001` SCOPE_INSUFFICIENT |
| 管理令牌错误 | 401 | `1004` ADMIN_TOKEN_INVALID |

吊销语义：密钥状态保存在 `KeyStore` 的 `ConcurrentHashMap` 中，吊销通过原子替换完成，
每个请求都实时读取最新状态——**吊销后下一个请求立即失效，不存在任何宽限窗口或缓存延迟**。

## 三、配额与限流

每个密钥独立一个**令牌桶**，签发时通过三个参数定义，规则明确可解释：

- `capacity`：桶容量（突发上限），新密钥桶是满的；
- `refillTokens`：每周期补充的令牌数（不能超过容量）；
- `refillIntervalMillis`：补充周期（毫秒）。每经过完整的一个周期补充一次，
  累积量始终不超过 `capacity`。

关键规则：

- 只有「鉴权通过 + 请求合法 + 幂等判定为首次执行」的写入才扣减 1 个令牌；
  **鉴权被拒绝的请求（1001/1002/1003/2001）一律不消耗配额**；幂等重放与幂等冲突也不消耗配额。
- 桶内令牌扣减在每桶的内置锁上串行化，因此并发打满时成功数严格等于容量，**绝不超发**。
- 超额时返回 HTTP 429 与错误码 `3001`，响应体给出 `retryAfterMillis`
  （距下一个令牌出现的等待毫秒数），并回写响应头 `Retry-After-Millis`；调用方在此之前不应重试。
- `GET /api/quota` 返回 `capacity/refillTokens/refillIntervalMillis/remaining/retryAfterMillis`，只读不改桶。

## 四、幂等语义

写入必须携带 `Idempotency-Key` 头。幂等作用域为「密钥 + 幂等键」，并对
`版本 + payload + 归一化后的 category` 计算 SHA-256 内容指纹：

- 同一键 + 相同内容：第一次正常执行并落库；后续请求（无论串行还是并发）返回首条结果，
  响应头 `Idempotency-Replayed: true`，**业务动作全局只生效一次**。
- 同一键 + 不同内容（payload、category 或版本任一不同）：返回 HTTP 409
  `4001` IDEMPOTENCY_CONFLICT，明确拒绝，不会覆盖首次结果。
- 并发重复提交按「密钥 + 幂等键」分段加锁排队，第一个提交执行，其余得到重放结果，
  并发与串行行为完全一致。

## 五、版本兼容

版本通过 `X-Api-Version` 请求头在同一入口显式声明，支持：

- `2024-01-01`（v1）：`category` 可省略，服务端**显式归一化为 `"default"`** 后落库；
- `2025-06-01`（v2）：`category` 必填，且必须匹配 `[A-Za-z0-9_-]{1,64}`，不合规返回 `4003`。

未声明或声明未知版本一律返回 HTTP 400 `4002` VERSION_UNSUPPORTED，不做静默回退；
因此不会出现「用新版本字段打老接口却静默成功」之类的歧义。读取响应对 v1 只增不减
（含 `sourceVersion` 溯源字段），保持向后兼容。

## 六、错误码一览

`1001` 缺密钥 / `1002` 密钥无效 / `1003` 已吊销 / `1004` 管理令牌错误 /
`2001` 权限不足 / `3001` 配额超额（带 `retryAfterMillis`）/
`4001` 幂等内容冲突 / `4002` 版本不支持 / `4003` 参数校验失败 /
`4004` JSON 非法 / `4005` 资源不存在。

## 七、本地验证

```bash
mvn test
```

测试分两层：

- 单元测试（`tests/unit`）：`TokenBucketTest`（配额边界、恢复、50 线程并发不超发）、
  `IdempotencyServiceTest`（串行/冲突/32 线程并发只执行一次）、`KeyStoreTest`（吊销立即可见）；
- 集成测试（`tests/integration/OpenApiIntegrationTest`）：通过真实 HTTP 端口覆盖鉴权四态、
  吊销立即生效、配额打满与恢复、鉴权拒绝不耗配额、幂等重放不耗配额、24 线程并发重复提交只落一条、
  v1/v2 版本差异、非法 JSON 等场景。

测试日志由 `BusinessLoggingExtension` 统一输出：每个业务域打印用例清单与逐条结果
（`[用例] ... 开始`、`[结果] ... 通过/失败`），全部结束后打印「业务覆盖总结」表，
可以直接看出覆盖了哪些业务、各业务有哪些用例以及通过情况。

手工联调示例：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080

# 签发密钥（容量 5，每 1 秒补 1 个令牌）
curl -s -X POST localhost:8080/admin/keys -H 'X-Admin-Token: dev-admin-token' \
  -H 'Content-Type: application/json' \
  -d '{"callerName":"demo","scopes":["data:write","data:read"],"capacity":5,"refillTokens":1,"refillIntervalMillis":1000}'

# v1 幂等写入（category 可省略）
curl -s -X POST localhost:8080/api/data/write \
  -H 'X-Api-Key: sk_xxx' -H 'X-Api-Version: 2024-01-01' -H 'Idempotency-Key: order-1' \
  -H 'Content-Type: application/json' -d '{"payload":"hello"}'

# 查询配额
curl -s localhost:8080/api/quota -H 'X-Api-Key: sk_xxx'

# 吊销
curl -s -X POST localhost:8080/admin/keys/{keyId}/revocation -H 'X-Admin-Token: dev-admin-token'
```
