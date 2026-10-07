package com.github.lechandonga.openapiaccessservicejava.tests.core;

import com.github.lechandonga.openapiaccessservicejava.api.ApiError;
import com.github.lechandonga.openapiaccessservicejava.api.ApiException;
import com.github.lechandonga.openapiaccessservicejava.core.ApiKey;
import com.github.lechandonga.openapiaccessservicejava.core.KeyService;
import com.github.lechandonga.openapiaccessservicejava.support.BusinessArea;
import com.github.lechandonga.openapiaccessservicejava.support.BusinessCase;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@BusinessArea("密钥签发 / 鉴权 / 吊销立即生效 / 权限范围")
class KeyServiceTest {

    private final KeyService keyService = new KeyService();

    @Test
    @BusinessCase("缺少密钥：AUTH_KEY_MISSING，与无效密钥错误码不同")
    void missing_key_is_rejected() {
        ApiException missing = assertThrows(ApiException.class, () -> keyService.requireActiveKey(null));
        ApiException blank = assertThrows(ApiException.class, () -> keyService.requireActiveKey("  "));
        assertEquals(ApiError.AUTH_KEY_MISSING, missing.error());
        assertEquals(ApiError.AUTH_KEY_MISSING, blank.error());
    }

    @Test
    @BusinessCase("无效密钥：AUTH_KEY_INVALID，稳定区分")
    void invalid_key_is_rejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> keyService.requireActiveKey("ak_never_issued"));
        assertEquals(ApiError.AUTH_KEY_INVALID, ex.error());
    }

    @Test
    @BusinessCase("密钥吊销后立即失效：AUTH_KEY_REVOKED，无宽限窗口")
    void revoked_key_is_immediately_rejected() {
        ApiKey key = keyService.issue(Set.of(KeyService.SCOPE_DATA_WRITE), null, null);
        assertEquals(key, keyService.requireActiveKey(key.keyId()));

        keyService.revoke(key.keyId());

        ApiException ex = assertThrows(ApiException.class, () -> keyService.requireActiveKey(key.keyId()));
        assertEquals(ApiError.AUTH_KEY_REVOKED, ex.error());
        ApiKey revokedView = keyService.revoke(key.keyId());
        assertFalse(revokedView.active());
    }

    @Test
    @BusinessCase("吊销一个从未签发的密钥：AUTH_KEY_INVALID，而非伪造吊销成功")
    void revoke_unknown_key_is_invalid() {
        ApiException ex = assertThrows(ApiException.class, () -> keyService.revoke("ak_unknown"));
        assertEquals(ApiError.AUTH_KEY_INVALID, ex.error());
    }

    @Test
    @BusinessCase("权限不足：AUTH_FORBIDDEN，与鉴权失败区分")
    void missing_scope_is_forbidden() {
        ApiKey key = keyService.issue(Set.of("other:scope"), null, null);
        ApiException ex = assertThrows(ApiException.class,
                () -> keyService.requireScope(key, KeyService.SCOPE_DATA_WRITE));
        assertEquals(ApiError.AUTH_FORBIDDEN, ex.error());
        assertTrue(keyService.requireActiveKey(key.keyId()).active(),
                "鉴权通过但权限不足不应改变密钥状态");
    }
}
