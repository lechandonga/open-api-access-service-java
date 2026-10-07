package com.github.lechandonga.openapiaccessservicejava.tests.unit;

import com.github.lechandonga.openapiaccessservicejava.store.KeyStore;
import com.github.lechandonga.openapiaccessservicejava.tests.support.BusinessArea;
import com.github.lechandonga.openapiaccessservicejava.tests.support.BusinessLoggingExtension;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(BusinessLoggingExtension.class)
@BusinessArea("密钥签发与吊销")
class KeyStoreTest {

    @Test
    @DisplayName("签发后可凭密钥原文查到，吊销后再次读取立即看到 revoked 状态")
    void revokeIsImmediatelyVisible() {
        KeyStore store = new KeyStore();
        var key = store.issue("sk-x", "caller", Set.of("data:read"),
                10, 1, 1000L, 100L);
        assertNotNull(store.findBySecret("sk-x"));
        assertFalse(store.findBySecret("sk-x").revoked());

        assertTrue(store.revoke(key.keyId(), 200L));
        assertTrue(store.findBySecret("sk-x").revoked(), "吊销必须立即生效，无宽限窗口");

        assertFalse(store.revoke(key.keyId(), 300L), "重复吊销不应再次改变状态");
    }
}
