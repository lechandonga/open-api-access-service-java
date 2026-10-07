package com.github.lechandonga.openapiaccessservicejava.tests.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.openapiaccessservicejava.tests.support.BusinessArea;
import com.github.lechandonga.openapiaccessservicejava.tests.support.BusinessLoggingExtension;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(BusinessLoggingExtension.class)
@BusinessArea("端到端接入流程")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiIntegrationTest {
    private static final String ADMIN_TOKEN = "dev-admin-token";
    private static final String V1 = "2024-01-01";
    private static final String V2 = "2025-06-01";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    private static class Resp {
        int status;
        JsonNode body;
        String replayHeader;
    }

    private Resp call(HttpMethod method, String path, String apiKey, String version,
                      String idempotencyKey, Object payload) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.set("X-Api-Key", apiKey);
        }
        if (version != null) {
            headers.set("X-Api-Version", version);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        HttpEntity<Object> entity = new HttpEntity<>(payload, headers);
        ResponseEntity<String> response = rest.exchange(path, method, entity, String.class);
        Resp r = new Resp();
        r.status = response.getStatusCode().value();
        r.replayHeader = response.getHeaders().getFirst("Idempotency-Replayed");
        try {
            r.body = JSON.readTree(response.getBody());
        } catch (Exception e) {
            r.body = null;
        }
        return r;
    }

    private Resp write(String apiKey, String version, String idem, String payload, String category) {
        Map<String, String> body = category == null
                ? Map.of("payload", payload)
                : Map.of("payload", payload, "category", category);
        return call(HttpMethod.POST, "/api/data/write", apiKey, version, idem, body);
    }

    private JsonNode issueKeyFull(String name, String scopes, Long capacity, Long refillTokens, Long interval) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        String[] scopeArray = scopes == null ? new String[]{"data:write", "data:read"}
                : scopes.split(",");
        Map<String, Object> req = Map.of(
                "callerName", name,
                "scopes", scopeArray,
                "capacity", capacity == null ? 10L : capacity,
                "refillTokens", refillTokens == null ? 1L : refillTokens,
                "refillIntervalMillis", interval == null ? 1000L : interval);
        ResponseEntity<String> response = rest.exchange("/admin/keys", HttpMethod.POST,
                new HttpEntity<>(req, headers), String.class);
        try {
            return JSON.readTree(response.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String issueKey(String name, String scopes, Long capacity, Long refillTokens, Long interval) {
        return issueKeyFull(name, scopes, capacity, refillTokens, interval).path("apiKey").asText();
    }

    private String keyIdOfSecret(String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        ResponseEntity<String> listing = rest.exchange("/admin/keys", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        try {
            for (JsonNode k : JSON.readTree(listing.getBody())) {
                if (k.path("secret").asText().equals(secret)) {
                    return k.path("keyId").asText();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        throw new IllegalStateException("key not found");
    }

    private void revoke(String keyId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        rest.exchange("/admin/keys/" + keyId + "/revocation", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("鉴权四态：缺密钥1001/无效密钥1002/已吊销1003/权限不足2001，互不混淆")
    void authFailuresHaveDistinctStableCodes() {
        Resp missing = write(null, V1, "i1", "p", null);
        assertEquals(401, missing.status);
        assertEquals("1001", missing.body.path("code").asText());

        Resp invalid = write("sk_does_not_exist", V1, "i2", "p", null);
        assertEquals(401, invalid.status);
        assertEquals("1002", invalid.body.path("code").asText());

        JsonNode revokedNode = issueKeyFull("revoked-caller", null, null, null, null);
        revoke(revokedNode.path("keyId").asText());
        Resp revokedResp = write(revokedNode.path("apiKey").asText(), V1, "i3", "p", null);
        assertEquals(401, revokedResp.status);
        assertEquals("1003", revokedResp.body.path("code").asText());

        String readOnly = issueKey("ro", "data:read", null, null, null);
        Resp forbidden = write(readOnly, V1, "i4", "p", null);
        assertEquals(403, forbidden.status);
        assertEquals("2001", forbidden.body.path("code").asText());
    }

    @Test
    @DisplayName("吊销立即生效：可用 -> 吊销 -> 下一个请求立即 1003，无宽限窗口")
    void revocationTakesEffectImmediately() {
        String key = issueKey("immediate", null, null, null, null);
        assertEquals(200, write(key, V1, "before-revoke", "p", null).status);
        revoke(keyIdOfSecret(key));
        Resp after = write(key, V1, "after-revoke", "p", null);
        assertEquals(401, after.status);
        assertEquals("1003", after.body.path("code").asText());
    }

    @Test
    @DisplayName("管理面：错误管理令牌返回 1004，与调用方密钥错误区分")
    void adminTokenProtected() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Token", "wrong-token");
        ResponseEntity<String> response = rest.exchange("/admin/keys", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(401, response.getStatusCode().value());
        assertTrue(response.getBody().contains("\"code\":\"1004\""));
    }

    @Test
    @DisplayName("配额边界：打满后返回 3001 与 retryAfterMillis，窗口过后恢复且不超发")
    void quotaBoundaryAndRefill() throws InterruptedException {
        String key = issueKey("quota", null, 2L, 1L, 800L);
        assertEquals(200, write(key, V1, "q1", "p", null).status);
        assertEquals(200, write(key, V1, "q2", "p", null).status);
        Resp exceeded = write(key, V1, "q3", "p", null);
        assertEquals(429, exceeded.status);
        assertEquals("3001", exceeded.body.path("code").asText());
        long retryAfter = exceeded.body.path("retryAfterMillis").asLong();
        assertTrue(retryAfter > 0 && retryAfter <= 800L, "必须告诉调用方何时可重试");

        Thread.sleep(retryAfter + 150L);
        assertEquals(200, write(key, V1, "q4", "p", null).status, "窗口过后应恢复 1 个令牌");
        assertEquals(429, write(key, V1, "q5", "p", null).status, "仅恢复 1 个，再次打满");
    }

    @Test
    @DisplayName("鉴权拒绝不消耗配额：另一密钥合法写入仍保有完整配额")
    void rejectedRequestsDoNotConsumeQuota() {
        String readOnly = issueKey("ro-quota", "data:read", 2L, 1L, 1000L);
        for (int i = 0; i < 5; i++) {
            assertEquals(403, write(readOnly, V1, "x" + i, "p", null).status);
        }
        String writeKey = issueKey("wr-quota", "data:write", 2L, 1L, 1000L);
        for (int i = 0; i < 5; i++) {
            assertEquals(403, call(HttpMethod.GET, "/api/quota", writeKey, null, null, null).status);
        }
        assertEquals(200, write(writeKey, V1, "still-full", "p", null).status);
        assertEquals(200, write(writeKey, V1, "still-full-2", "p", null).status);
        assertEquals(429, write(writeKey, V1, "now-empty", "p", null).status);
    }

    @Test
    @DisplayName("幂等重放不扣配额：容量 1 时重复提交持续成功并带 Idempotency-Replayed: true")
    void replayDoesNotConsumeQuota() {
        String key = issueKey("replay-quota", null, 1L, 1L, 60_000L);
        Resp first = write(key, V1, "same-key", "same payload", null);
        Resp second = write(key, V1, "same-key", "same payload", null);
        Resp third = write(key, V1, "same-key", "same payload", null);
        assertEquals(200, first.status);
        assertEquals(200, second.status);
        assertEquals(200, third.status);
        assertEquals("false", first.replayHeader);
        assertEquals("true", second.replayHeader);
        assertEquals("true", third.replayHeader);
        assertEquals(first.body.path("recordId").asText(), second.body.path("recordId").asText());

        Resp fresh = write(key, V1, "different-key", "other", null);
        assertEquals(429, fresh.status, "新写入仍受 1 令牌限制，证明重放未消耗配额");
    }

    @Test
    @DisplayName("幂等冲突：相同标识不同内容返回 4001")
    void idempotencyKeyContentConflict() {
        String key = issueKey("conflict", null, 10L, 1L, 1000L);
        assertEquals(200, write(key, V1, "dup", "payload-A", null).status);
        Resp conflict = write(key, V1, "dup", "payload-B", null);
        assertEquals(409, conflict.status);
        assertEquals("4001", conflict.body.path("code").asText());
    }

    @Test
    @DisplayName("并发重复提交：24 个相同请求只落 1 条数据，仅 1 次非重放")
    void concurrentDuplicatesOverHttp() throws InterruptedException {
        String key = issueKey("concurrent", null, 24L, 1L, 60_000L);
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger replays = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Resp r = write(key, V2, "concurrent-idem", "same payload", "orders");
                    if (r.status == 200) {
                        ok.incrementAndGet();
                        if ("true".equals(r.replayHeader)) {
                            replays.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(threads, ok.get());
        assertEquals(threads - 1, replays.get(), "恰好 1 次执行，其余全部重放");

        Resp list = call(HttpMethod.GET, "/api/data", key, null, null, null);
        assertEquals(200, list.status);
        assertEquals(1, list.body.size(), "并发重复提交只能落一条数据");
    }

    @Test
    @DisplayName("版本兼容：未声明/未知版本 4002；v1 缺 category 归一化 default；v2 缺 category 4003")
    void versionCompatibilityAtSingleEntry() {
        String key = issueKey("version", null, 50L, 10L, 1000L);

        Resp noVersion = write(key, null, "v-none", "p", null);
        assertEquals(400, noVersion.status);
        assertEquals("4002", noVersion.body.path("code").asText());

        Resp badVersion = write(key, "1999-01-01", "v-bad", "p", null);
        assertEquals(400, badVersion.status);
        assertEquals("4002", badVersion.body.path("code").asText());

        Resp v1 = write(key, V1, "v1-id", "p", null);
        assertEquals(200, v1.status);
        assertEquals("default", v1.body.path("category").asText(), "v1 缺省 category 必须显式归一化");

        Resp v2Missing = write(key, V2, "v2-missing", "p", null);
        assertEquals(400, v2Missing.status);
        assertEquals("4003", v2Missing.body.path("code").asText(), "v2 的必填字段不得静默放行");

        Resp v2 = write(key, V2, "v2-id", "p", "events");
        assertEquals(200, v2.status);
        assertEquals("events", v2.body.path("category").asText());

        Resp fetched = call(HttpMethod.GET,
                "/api/data/" + v1.body.path("recordId").asText(), key, null, null, null);
        assertEquals(200, fetched.status);
        assertEquals(V1, fetched.body.path("sourceVersion").asText(), "读取结果保留写入时的版本溯源");
    }

    @Test
    @DisplayName("其他异常输入：缺幂等头、空 payload、非法 JSON 均返回稳定 4xxx 错误码")
    void malformedInputsHaveStableErrors() {
        String key = issueKey("malformed", null, 50L, 10L, 1000L);

        Resp noIdem = call(HttpMethod.POST, "/api/data/write", key, V1, null,
                Map.of("payload", "p"));
        assertEquals(400, noIdem.status);
        assertEquals("4003", noIdem.body.path("code").asText());

        Resp blankPayload = write(key, V1, "blank", "   ", null);
        assertEquals(400, blankPayload.status);
        assertEquals("4003", blankPayload.body.path("code").asText());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", key);
        headers.set("X-Api-Version", V1);
        headers.set("Idempotency-Key", "broken-json");
        headers.set("Content-Type", "application/json");
        ResponseEntity<String> response = rest.exchange("/api/data/write", HttpMethod.POST,
                new HttpEntity<>("{not-json", headers), String.class);
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().contains("\"code\":\"4004\""));
    }
}
