package com.github.lechandonga.openapiaccessservicejava.service;

import org.springframework.stereotype.Service;

/**
 * 统一时间源，便于把配额恢复规则解释为确定的时间窗口。
 */
@Service
public class ClockService {
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
