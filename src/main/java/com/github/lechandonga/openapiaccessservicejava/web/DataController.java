package com.github.lechandonga.openapiaccessservicejava.web;

import com.github.lechandonga.openapiaccessservicejava.idempotency.IdempotentOutcome;
import com.github.lechandonga.openapiaccessservicejava.idempotency.IdempotencyService;
import com.github.lechandonga.openapiaccessservicejava.model.ApiKey;
import com.github.lechandonga.openapiaccessservicejava.model.DataRecord;
import com.github.lechandonga.openapiaccessservicejava.quota.QuotaResult;
import com.github.lechandonga.openapiaccessservicejava.quota.QuotaService;
import com.github.lechandonga.openapiaccessservicejava.service.ClockService;
import com.github.lechandonga.openapiaccessservicejava.store.DataStore;
import com.github.lechandonga.openapiaccessservicejava.web.dto.DataRecordResponse;
import com.github.lechandonga.openapiaccessservicejava.web.dto.IngestRequest;
import com.github.lechandonga.openapiaccessservicejava.web.dto.IngestResponse;
import com.github.lechandonga.openapiaccessservicejava.web.dto.QuotaResponse;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外开放的数据接入入口。两个 API 版本共用同一入口，由 X-Api-Version 显式区分。
 */
@RestController
public class DataController {
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String IDEMPOTENCY_REPLAY_HEADER = "Idempotency-Replayed";

    private final DataStore dataStore;
    private final QuotaService quotaService;
    private final IdempotencyService idempotencyService;
    private final ClockService clock;

    public DataController(DataStore dataStore, QuotaService quotaService,
                          IdempotencyService idempotencyService, ClockService clock) {
        this.dataStore = dataStore;
        this.quotaService = quotaService;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    @PostMapping("/api/data/write")
    public ResponseEntity<IngestResponse> write(
            @RequestHeader(name = ApiVersions.HEADER, required = false) String versionHeader,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody IngestRequest body,
            @RequestAttribute(AuthenticatedRequest.ATTRIBUTE) AuthenticatedRequest auth) throws Exception {
        ApiKey key = auth.apiKey();
        String version = ApiVersions.requireSupported(versionHeader);

        String normalizedCategory = validate(body, version);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "header " + IDEMPOTENCY_HEADER + " is required for writes");
        }

        String fingerprint = fingerprint(version, body.payload(), normalizedCategory);
        IdempotencyService.Execution execution = idempotencyService.execute(
                key.keyId(), idempotencyKey, fingerprint, () -> {
                    // 只有首次执行的写入才申请令牌：幂等重放与内容冲突不消耗配额。
                    QuotaResult quota = quotaService.acquire(key, clock.nowMillis());
                    if (!quota.allowed()) {
                        throw new QuotaExceededException(quota.retryAfterMillis());
                    }
                    DataRecord record = new DataRecord(
                            UUID.randomUUID().toString(),
                            key.keyId(),
                            body.payload(),
                            normalizedCategory,
                            version,
                            idempotencyKey,
                            clock.nowMillis());
                    return dataStore.putIfAbsent(record);
                });

        if (execution.outcome() == IdempotentOutcome.CONFLICT) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "idempotency key '" + idempotencyKey + "' was already used with a different payload/version");
        }
        DataRecord record = (DataRecord) execution.result();
        boolean replay = execution.outcome() == IdempotentOutcome.REPLAY;
        IngestResponse response = new IngestResponse(record.id(), record.idempotencyKey(),
                record.sourceVersion(), record.category(), record.payload(),
                replay, record.createdAtMillis());
        return ResponseEntity.ok()
                .header(IDEMPOTENCY_REPLAY_HEADER, Boolean.toString(replay))
                .body(response);
    }

    @GetMapping("/api/data/{recordId}")
    public DataRecordResponse read(@PathVariable String recordId,
                                   @RequestAttribute(AuthenticatedRequest.ATTRIBUTE) AuthenticatedRequest auth) {
        DataRecord record = dataStore.find(recordId)
                .filter(r -> r.apiKeyId().equals(auth.apiKey().keyId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "record not found: " + recordId));
        return new DataRecordResponse(record.id(), record.payload(), record.category(),
                record.sourceVersion(), record.idempotencyKey(), record.createdAtMillis());
    }

    @GetMapping("/api/data")
    public List<DataRecordResponse> list(
            @RequestAttribute(AuthenticatedRequest.ATTRIBUTE) AuthenticatedRequest auth) {
        return dataStore.listByKey(auth.apiKey().keyId()).stream()
                .map(r -> new DataRecordResponse(r.id(), r.payload(), r.category(),
                        r.sourceVersion(), r.idempotencyKey(), r.createdAtMillis()))
                .toList();
    }

    @GetMapping("/api/quota")
    public QuotaResponse quota(
            @RequestAttribute(AuthenticatedRequest.ATTRIBUTE) AuthenticatedRequest auth) {
        ApiKey key = auth.apiKey();
        QuotaResult snapshot = quotaService.snapshot(key, clock.nowMillis());
        return new QuotaResponse(key.capacity(), key.refillTokens(), key.refillIntervalMillis(),
                snapshot.remaining(), snapshot.retryAfterMillis());
    }

    /**
     * 按版本校验请求体并归一化 category。
     * v1：category 可空，服务端归一化为 "default"；
     * v2：category 必填且只能是 alpha/digit/下划线/短横，不允许静默落库空值。
     */
    private String validate(IngestRequest body, String version) {
        if (body == null || body.payload() == null || body.payload().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "payload is required and must be non-blank");
        }
        if (ApiVersions.V1.equals(version)) {
            String category = body.category() == null || body.category().isBlank()
                    ? "default" : body.category();
            return category;
        }
        if (body.category() == null || body.category().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "category is required since version " + ApiVersions.V2);
        }
        if (!body.category().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "category must match [A-Za-z0-9_-]{1,64} in version " + ApiVersions.V2);
        }
        return body.category();
    }

    private String fingerprint(String version, String payload, String category) {
        String canonical = "version=" + version + "\npayload=" + payload + "\ncategory=" + category + "\n";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
