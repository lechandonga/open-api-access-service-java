# Open API Access Service（Java / Spring Boot 引导工程）

一个对外开放的数据接入 API 参考实现。所有数据与状态（密钥、配额计数、幂等记录、
业务数据）均保存在**本地内存**中，重启即清空，无需数据库。

## 1. 接口总览

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/admin/keys` | 无（运维边界，见 §6） | 签发调用方密钥 |
| POST | `/admin/keys/{keyId}/revoke` | 无 | 吊销密钥，立即生效 |
| POST | `/api/v/data/ingest` | `X-Api-Key` | 数据接入写入（版本协商 + 幂等 + 配额） |

接入请求必须携带的请求头：

| 请求头 | 必需 | 说明 |
| --- | --- | --- |
| `X-Api-Key` | 是 | 签发得到的密钥 |
| `X-Api-Version` | 是 | `v1` 或 `v2`，缺失/未知一律报错，不做隐式回退 |
| `Idempotency-Key` | 写入必需 | 调用方生成的幂等标识（建议 UUID） |

错误响应统一为：

```json
{ "code": 42901, "error": "RATE_LIMITED",
  "message": "Quota exhausted; retry later. Retry after 42 second(s).",
  "retryAfter": "42s", "timestamp": "2026-10-08T03:10:00Z" }
```

## 2. 鉴权模型与错误码

请求处理顺序固定，前一步失败不会进入后续环节：

1. 认证（拦截器 `AuthInterceptor`）；
2. 权限范围（scope）校验；
3. 版本协商；
4. 请求体校验；
5. 幂等命中检查（命中直接返回，**不扣配额**）；
6. 配额获取（原子扣减）；
7. 幂等保护下执行业务写入。

四类鉴权/授权失败使用**互相区分、稳定不变**的错误码：

| 场景 | HTTP | code | 枚举 |
| --- | --- | --- | --- |
| 缺少密钥 | 401 | `40101` | `AUTH_KEY_MISSING` |
| 密钥从未签发 | 401 | `40102` | `AUTH_KEY_INVALID` |
| 密钥已吊销 | 401 | `40103` | `AUTH_KEY_REVOKED` |
| 权限范围不足 | 403 | `40301` | `AUTH_FORBIDDEN` |

**吊销立即生效，无宽限窗口**：服务端不缓存任何鉴权决策，每个请求都实时读取密钥的
当前状态（`KeyService.requireActiveKey`）；吊销动作以新记录替换内存中的密钥，
此后该密钥的请求立即得到 `40103`。

**被鉴权拒绝的请求不消耗配额**：配额扣减发生在认证、授权、版本与报文校验之后。

签发示例：

```bash
curl -s -X POST localhost:8080/admin/keys \
  -H 'Content-Type: application/json' \
  -d '{"scopes":["data:write"],"quotaLimit":10,"refillMillis":60000}'
```

## 3. 配额与限流规则

采用**固定窗口**（fixed window），规则明确可解释：

- 每个密钥有配额上限 `quotaLimit` 与窗口长度 `refillMillis`（签发时指定，
  默认 10 次 / 60 秒）；
- 窗口起点对齐该密钥的第一次请求；一次成功的写入消耗 1 个令牌；
- 进入新窗口后配额**整体恢复为上限**（不是缓慢补充），窗口切换在请求到达时
  惰性计算，无后台定时器；
- 扣减在每密钥对象监视器内完成「检查 + 扣减」单步操作，并发打满时**绝不超发**；
- 超额请求返回 `429 / 42901 RATE_LIMITED`，并通过 `Retry-After` 响应头
  （整数秒）与响应体 `retryAfter` 字段告知**何时可以重试**，时间精确指向下一窗口起点；
- 幂等重放（重复提交）与鉴权/校验失败都不扣配额；只有真正生效的写入扣 1 个令牌。

## 4. 幂等写入语义

`Idempotency-Key` 与请求内容（含协商出的 API 版本，规范化后做 SHA-256）绑定：

- **首次提交**：执行业务一次，返回 `201`，响应头 `Idempotent-Replay: false`；
- **重复提交（内容相同）**：不再次执行、不扣配额，直接返回首次结果，
  HTTP `200`，`Idempotent-Replay: true`，返回体中的记录 `id` 与首次一致；
- **同键不同内容**：明确拒绝 `409 / 40901 IDEMPOTENCY_CONFLICT`，不会覆盖首次结果；
- **并发重复提交**：相同键的并发请求中只有一个 leader 真正执行业务，
  其余请求等待并拿到同一结果（200 replay），并发下同样保证「只生效一次」；
- 缺少幂等头：`400 / 40001 IDEMPOTENCY_KEY_MISSING`；
- leader 执行业务失败（如当时恰好超额）时槽位被移除，等待者竞争成为新 leader，
  瞬时失败不会被放大成所有并发请求的失败，客户端可安全重试。

## 5. 版本兼容范围

版本经 `X-Api-Version` **显式协商**，同一入口 `/api/v/data/ingest` 同时支持：

| 版本 | 请求体 | 响应体 |
| --- | --- | --- |
| `v1` | `{"name": "..."}` | `id, name, createdAt, version`；`type` 为 `null` |
| `v2` | `{"name": "...", "type": "..."}` | 在 v1 基础上返回 `type` 与 `version=v2` |

兼容性约束（不允许版本差异导致静默错误）：

- 缺少版本头或发送未知版本：`400 / 40002 VERSION_UNSUPPORTED`，不隐式选择版本；
- v2 缺少必填 `type`：`400 / 40003 VALIDATION_ERROR`；
- 给 v1 发送其不认识的字段（如 `type`）：同样 `400 / 40003`，严格报错而非
  静默忽略（服务端对报文启用 `FAIL_ON_UNKNOWN_PROPERTIES`）；
- 幂等内容哈希包含版本号：同一 `Idempotency-Key` 以不同版本提交会被视为不同内容，
  返回 `40901`，避免跨版本语义混淆；
- v1 的 `name` 语义在 v2 中保持不变，v2 只做向后兼容的字段新增。

## 6. 本地验证

需要 JDK 21+ 与 Maven。

```bash
# 运行全部单测（26 个用例），日志按业务分组打印覆盖范围与每个用例通过情况
mvn test

# 启动服务
mvn spring-boot:run
```

手工调用：

```bash
# 1) 签发密钥
KEY=$(curl -s -X POST localhost:8080/admin/keys -H 'Content-Type: application/json' \
  -d '{"scopes":["data:write"],"quotaLimit":2,"refillMillis":60000}' | sed -n 's/.*"keyId":"\([^"]*\)".*/\1/p')

# 2) v1 首次写入 -> 201
curl -i -X POST localhost:8080/api/v/data/ingest \
  -H "X-Api-Key: $KEY" -H 'X-Api-Version: v1' -H 'Idempotency-Key: k1' \
  -H 'Content-Type: application/json' -d '{"name":"alice"}'

# 3) 相同键重复提交 -> 200，Idempotent-Replay: true，不消耗第二次配额
curl -i -X POST localhost:8080/api/v/data/ingest \
  -H "X-Api-Key: $KEY" -H 'X-Api-Version: v1' -H 'Idempotency-Key: k1' \
  -H 'Content-Type: application/json' -d '{"name":"alice"}'

# 4) 再发一个不同键请求用掉最后配额，随后触发 429 + Retry-After
curl -i -X POST localhost:8080/api/v/data/ingest \
  -H "X-Api-Key: $KEY" -H 'X-Api-Version: v2' -H 'Idempotency-Key: k2' \
  -H 'Content-Type: application/json' -d '{"name":"bob","type":"event"}'

# 5) 吊销后立即 401/40103
curl -s -X POST "localhost:8080/admin/keys/$KEY/revoke"
```

`/admin/**` 是本地引导用的无鉴权运维入口，仅适用于内存演示；部署到真实环境时
应置于运维网络边界或增加管理员鉴权。

## 7. 测试覆盖的业务场景

`mvn test` 输出会按业务分组打印 `[业务]` 与每个用例的通过/耗时，覆盖：

- **密钥与鉴权**（`KeyServiceTest`）：缺密钥/无效/已吊销三类错误码区分、
  吊销立即生效、权限不足；
- **配额限流**（`QuotaServiceTest`）：配额边界、固定窗口恢复、
  50 并发争抢 10 配额严格不超发、`Retry-After` 计算；
- **幂等写入**（`IdempotencyServiceTest`）：顺序重复只生效一次、
  40 并发重复提交只执行一次、同键不同内容冲突、缺幂等键；
- **端到端**（`DataApiIntegrationTest`）：全部错误码的 HTTP 映射、
  吊销状态变更前后差异、429 与重试头、幂等重放不扣配额、v1/v2 契约与
  严格字段校验、HTTP 层 20 并发重复提交、20 并发不同键打满 5 配额。
