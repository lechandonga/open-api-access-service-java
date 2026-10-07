package com.github.lechandonga.openapiaccessservicejava.tests.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.openapiaccessservicejava.support.BusinessArea;
import com.github.lechandonga.openapiaccessservicejava.support.BusinessCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@BusinessArea("数据接入 API 端到端：鉴权错误码 / 429 重试 / 并发幂等 / v1·v2 版本契约")
@SpringBootTest
@AutoConfigureMockMvc
class DataApiIntegrationTest {

    private static final String INGEST = "/api/v/data/ingest";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper json = new ObjectMapper();

    private String issueKey(Set<String> scopes, long limit, long refillMillis) throws Exception {
        Map<String, Object> req = Map.of(
                "scopes", scopes == null ? Set.of("data:write") : scopes,
                "quotaLimit", limit,
                "refillMillis", refillMillis);
        MvcResult result = mockMvc.perform(post("/admin/keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("keyId").asText();
    }

    private MvcResult ingest(String key, String version, String idempotencyKey, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(INGEST)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request.header("X-Api-Key", key);
        }
        if (version != null) {
            request.header("X-Api-Version", version);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request).andReturn();
    }

    private int errorCode(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("code").asInt();
    }

    @Test
    @BusinessCase("缺少密钥：401 / 40101 AUTH_KEY_MISSING，且不进入业务处理")
    void missing_key_returns_distinct_code() throws Exception {
        MvcResult result = ingest(null, "v1", "id-missing", "{\"name\":\"a\"}");
        assertEquals(401, result.getResponse().getStatus());
        assertEquals(40101, errorCode(result));
    }

    @Test
    @BusinessCase("无效密钥：401 / 40102 AUTH_KEY_INVALID")
    void invalid_key_returns_distinct_code() throws Exception {
        MvcResult result = ingest("ak_bogus", "v1", "id-invalid", "{\"name\":\"a\"}");
        assertEquals(401, result.getResponse().getStatus());
        assertEquals(40102, errorCode(result));
    }

    @Test
    @BusinessCase("密钥吊销后立即失效：401 / 40103 AUTH_KEY_REVOKED（吊销前后结果不同）")
    void revoked_key_is_immediately_rejected() throws Exception {
        String key = issueKey(null, 5L, 60_000L);
        assertEquals(201, ingest(key, "v1", "id-before-revoke", "{\"name\":\"a\"}")
                .getResponse().getStatus());

        mockMvc.perform(post("/admin/keys/{id}/revoke", key))
                .andExpect(status().isOk());

        MvcResult after = ingest(key, "v1", "id-after-revoke", "{\"name\":\"a\"}");
        assertEquals(401, after.getResponse().getStatus());
        assertEquals(40103, errorCode(after), "吊销必须立即生效，不存在宽限窗口");
    }

    @Test
    @BusinessCase("权限不足：403 / 40301 AUTH_FORBIDDEN，且连续被拒不消耗配额")
    void forbidden_scope_is_distinct_and_costs_no_quota() throws Exception {
        String key = issueKey(Set.of("other:scope"), 1L, 60_000L);
        for (int i = 0; i < 3; i++) {
            MvcResult result = ingest(key, "v1", "id-scope-" + i, "{\"name\":\"a\"}");
            assertEquals(403, result.getResponse().getStatus());
            assertEquals(40301, errorCode(result));
        }
    }

    @Test
    @BusinessCase("配额打满：429 / 42901 RATE_LIMITED，带 Retry-After 秒数")
    void quota_exceeded_returns_429_and_retry_after() throws Exception {
        String key = issueKey(null, 2L, 60_000L);
        assertEquals(201, ingest(key, "v1", "id-q-1", "{\"name\":\"a\"}").getResponse().getStatus());
        assertEquals(201, ingest(key, "v1", "id-q-2", "{\"name\":\"b\"}").getResponse().getStatus());

        MvcResult rejected = ingest(key, "v1", "id-q-3", "{\"name\":\"c\"}");
        assertEquals(429, rejected.getResponse().getStatus());
        assertEquals(42901, errorCode(rejected));
        String retryAfter = rejected.getResponse().getHeader("Retry-After");
        assertNotNull(retryAfter);
        long seconds = Long.parseLong(retryAfter);
        assertTrue(seconds >= 1 && seconds <= 60, "Retry-After 必须告知可重试时间: " + seconds);
    }

    @Test
    @BusinessCase("幂等重放不消耗配额：配额=2，相同键重复 3 次均成功，第二次起 200 + replay=true")
    void idempotent_replay_does_not_consume_quota() throws Exception {
        String key = issueKey(null, 2L, 60_000L);
        MvcResult first = ingest(key, "v1", "shared-key", "{\"name\":\"payload\"}");
        MvcResult second = ingest(key, "v1", "shared-key", "{\"name\":\"payload\"}");
        MvcResult third = ingest(key, "v1", "shared-key", "{\"name\":\"payload\"}");

        assertEquals(201, first.getResponse().getStatus());
        assertEquals("false", first.getResponse().getHeader("Idempotent-Replay"));
        assertEquals(200, second.getResponse().getStatus());
        assertEquals("true", second.getResponse().getHeader("Idempotent-Replay"));
        assertEquals(200, third.getResponse().getStatus());
        assertEquals(
                json.readTree(first.getResponse().getContentAsString()).get("id").asText(),
                json.readTree(third.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    @BusinessCase("相同键不同内容：409 / 40901 IDEMPOTENCY_CONFLICT")
    void same_key_different_body_conflicts() throws Exception {
        String key = issueKey(null, 10L, 60_000L);
        ingest(key, "v1", "conflict-key", "{\"name\":\"first\"}");
        MvcResult result = ingest(key, "v1", "conflict-key", "{\"name\":\"different\"}");
        assertEquals(409, result.getResponse().getStatus());
        assertEquals(40901, errorCode(result));
    }

    @Test
    @BusinessCase("缺少 Idempotency-Key：400 / 40001")
    void missing_idempotency_header_rejected() throws Exception {
        String key = issueKey(null, 10L, 60_000L);
        MvcResult result = ingest(key, "v1", null, "{\"name\":\"a\"}");
        assertEquals(400, result.getResponse().getStatus());
        assertEquals(40001, errorCode(result));
    }

    @Test
    @BusinessCase("版本缺失或不受支持：400 / 40002 VERSION_UNSUPPORTED，不静默回退")
    void version_must_be_explicit_and_supported() throws Exception {
        String key = issueKey(null, 10L, 60_000L);
        MvcResult missing = ingest(key, null, "id-ver-1", "{\"name\":\"a\"}");
        MvcResult unknown = ingest(key, "v9", "id-ver-2", "{\"name\":\"a\"}");
        assertEquals(400, missing.getResponse().getStatus());
        assertEquals(40002, errorCode(missing));
        assertEquals(400, unknown.getResponse().getStatus());
        assertEquals(40002, errorCode(unknown));
    }

    @Test
    @BusinessCase("v1 / v2 同一入口契约不同：v1 无 type，v2 要求 type，越界字段显式报错")
    void v1_and_v2_coexist_under_one_entry() throws Exception {
        String key = issueKey(null, 10L, 60_000L);

        MvcResult v1 = ingest(key, "v1", "id-v1", "{\"name\":\"alice\"}");
        assertEquals(201, v1.getResponse().getStatus());
        JsonNode v1Body = json.readTree(v1.getResponse().getContentAsString());
        assertEquals("v1", v1Body.get("version").asText());
        assertTrue(v1Body.get("type").isNull(), "v1 响应不包含 type 业务字段");
        assertNotNull(v1Body.get("id"));

        MvcResult v2 = ingest(key, "v2", "id-v2", "{\"name\":\"bob\",\"type\":\"event\"}");
        assertEquals(201, v2.getResponse().getStatus());
        JsonNode v2Body = json.readTree(v2.getResponse().getContentAsString());
        assertEquals("v2", v2Body.get("version").asText());
        assertEquals("event", v2Body.get("type").asText());

        MvcResult v2MissingType = ingest(key, "v2", "id-v2-bad", "{\"name\":\"bob\"}");
        assertEquals(400, v2MissingType.getResponse().getStatus());
        assertEquals(40003, errorCode(v2MissingType));

        MvcResult v1UnknownField = ingest(key, "v1", "id-v1-unknown",
                "{\"name\":\"bob\",\"type\":\"event\"}");
        assertEquals(400, v1UnknownField.getResponse().getStatus(),
                "v1 不认识的字段必须显式报错，不允许静默忽略");
        assertEquals(40003, errorCode(v1UnknownField));
    }

    @Test
    @BusinessCase("并发重复提交（HTTP 层）：20 个相同请求仅一次 201，其余全部 200 replay")
    void concurrent_duplicate_http_requests_apply_once() throws Exception {
        String key = issueKey(null, 20L, 60_000L);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    MvcResult result = ingest(key, "v1", "race-key-http", "{\"name\":\"raced\"}");
                    int status = result.getResponse().getStatus();
                    if (status == 201) {
                        created.incrementAndGet();
                    } else if (status == 200) {
                        replayed.incrementAndGet();
                    } else {
                        failures.incrementAndGet();
                    }
                } catch (Exception ex) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(1, created.get(), "并发重复提交只能有一次 201");
        assertEquals(threads - 1, replayed.get(), "其余请求必须为重放 200");
        assertEquals(0, failures.get());
    }

    @Test
    @BusinessCase("不同幂等键并发打满配额：成功数=配额，超额请求得到 429 与 Retry-After")
    void concurrent_distinct_keys_respect_quota() throws Exception {
        String key = issueKey(null, 5L, 60_000L);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    MvcResult result = ingest(key, "v2", "quota-key-" + index,
                            "{\"name\":\"n" + index + "\",\"type\":\"event\"}");
                    if (result.getResponse().getStatus() == 201) {
                        ok.incrementAndGet();
                    } else if (result.getResponse().getStatus() == 429) {
                        limited.incrementAndGet();
                        assertNotNull(result.getResponse().getHeader("Retry-After"));
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(5, ok.get(), "并发下不得超发");
        assertEquals(15, limited.get());
    }
}
