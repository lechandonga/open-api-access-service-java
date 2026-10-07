package com.github.lechandonga.openapiaccessservicejava.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.openapiaccessservicejava.api.ApiError;
import com.github.lechandonga.openapiaccessservicejava.api.ApiException;
import com.github.lechandonga.openapiaccessservicejava.api.ErrorBody;
import com.github.lechandonga.openapiaccessservicejava.core.ApiKey;
import com.github.lechandonga.openapiaccessservicejava.core.DataRecord;
import com.github.lechandonga.openapiaccessservicejava.core.DataService;
import com.github.lechandonga.openapiaccessservicejava.core.IdempotencyService;
import com.github.lechandonga.openapiaccessservicejava.core.KeyService;
import com.github.lechandonga.openapiaccessservicejava.core.QuotaResult;
import com.github.lechandonga.openapiaccessservicejava.core.QuotaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Public data ingestion endpoint, versioned by {@code X-Api-Version}.
 *
 * <p>Request pipeline (order matters):
 * <ol>
 *   <li>authentication (interceptor): missing / invalid / revoked key rejected</li>
 *   <li>scope check: {@code data:write} required</li>
 *   <li>version negotiation: v1 or v2 explicitly</li>
 *   <li>payload validation per the negotiated version</li>
 *   <li>idempotency replay check: a finished identical submit is returned
 *       <em>without</em> consuming quota</li>
 *   <li>quota acquisition: atomic, never over-issued; rejected requests carry
 *       {@code Retry-After}</li>
 *   <li>idempotent business write: runs exactly once even under concurrency</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v/data")
public class DataController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String VERSION_HEADER = "X-Api-Version";

    private final KeyService keyService;
    private final QuotaService quotaService;
    private final IdempotencyService idempotencyService;
    private final DataService dataService;
    private final ObjectMapper objectMapper;

    public DataController(KeyService keyService,
                          QuotaService quotaService,
                          IdempotencyService idempotencyService,
                          DataService dataService,
                          ObjectMapper objectMapper) {
        this.keyService = keyService;
        this.quotaService = quotaService;
        this.idempotencyService = idempotencyService;
        this.dataService = dataService;
        // Strict binding for request payloads: unknown fields fail loudly instead of being
        // silently dropped (Spring Boot's default mapper disables that check).
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @PostMapping(path = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> ingest(HttpServletRequest request, @RequestBody String rawBody) {
        ApiKey key = (ApiKey) request.getAttribute(AuthInterceptor.ATTR_API_KEY);
        keyService.requireScope(key, KeyService.SCOPE_DATA_WRITE);

        Version version = Version.fromHeader(request.getHeader(VERSION_HEADER));

        String name;
        String type;
        String payloadHashInput;
        if (version == Version.V1) {
            IngestRequestV1 body = parse(rawBody, IngestRequestV1.class);
            name = requireField("name", body.name());
            type = null;
            payloadHashInput = canonicalize(Map.of("v", "v1", "name", name));
        } else {
            IngestRequestV2 body = parse(rawBody, IngestRequestV2.class);
            name = requireField("name", body.name());
            type = requireField("type", body.type());
            payloadHashInput = canonicalize(Map.of("v", "v2", "name", name, "type", type));
        }

        String idempotencyKey = request.getHeader(IDEMPOTENCY_HEADER);
        String payloadHash = sha256(payloadHashInput);

        IdempotencyService.Outcome<DataRecord> outcome = idempotencyService.execute(
                idempotencyKey,
                payloadHash,
                () -> writeOnce(key, name, type));

        HttpStatusInfo status = outcome.replayed() ? HttpStatusInfo.OK : HttpStatusInfo.CREATED;
        IngestResponse body = IngestResponse.of(outcome.value(), version);
        return ResponseEntity.status(status.code)
                .header("Idempotent-Replay", String.valueOf(outcome.replayed()))
                .body(body);
    }

    /**
     * Quota debit and store write happen inside the idempotency leader action,
     * so exactly one of N concurrent identical submits reaches this method and
     * consumes one quota token.
     */
    private DataRecord writeOnce(ApiKey key, String name, String type) {
        QuotaResult quota = quotaService.tryAcquire(key);
        if (!quota.allowed()) {
            throw new QuotaExceededException(quota);
        }
        return dataService.create(name, type, key.keyId());
    }

    private <T> T parse(String rawBody, Class<T> type) {
        try {
            return objectMapper.readValue(rawBody, type);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ApiError.VALIDATION_ERROR,
                    ApiError.VALIDATION_ERROR.defaultMessage() + ": " + ex.getOriginalMessage());
        }
    }

    private static String requireField(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ApiError.VALIDATION_ERROR,
                    "Field '" + field + "' is required and must be non-blank.");
        }
        return value;
    }

    private String canonicalize(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ApiError.INTERNAL_ERROR, ex.getMessage());
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new ApiException(ApiError.INTERNAL_ERROR, ex.getMessage());
        }
    }

    private enum HttpStatusInfo {
        OK(200), CREATED(201);
        private final int code;

        HttpStatusInfo(int code) {
            this.code = code;
        }
    }

    /** Internal control signal: quota rejection raised within the idempotent action. */
    static final class QuotaExceededException extends ApiException {
        private final transient QuotaResult quotaResult;

        QuotaExceededException(QuotaResult quotaResult) {
            super(ApiError.RATE_LIMITED,
                    ApiError.RATE_LIMITED.defaultMessage()
                            + " Retry after " + quotaResult.retryAfterSeconds() + " second(s).");
            this.quotaResult = quotaResult;
        }

        QuotaResult quotaResult() {
            return quotaResult;
        }
    }

    /** Map quota failures to 429 with a Retry-After header. */
    @org.springframework.web.bind.annotation.ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ErrorBody> handleQuota(QuotaExceededException ex) {
        return ResponseEntity.status(429)
                .header("Retry-After", String.valueOf(ex.quotaResult().retryAfterSeconds()))
                .body(ErrorBody.of(ApiError.RATE_LIMITED, ex.getMessage(),
                        ex.quotaResult().retryAfterSeconds() + "s"));
    }

}
