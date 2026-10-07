package com.github.lechandonga.openapiaccessservicejava.store;

import com.github.lechandonga.openapiaccessservicejava.model.DataRecord;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 已接入数据的内存存储。putIfAbsent 保证同一 ID 只可能写入一次。
 */
@Component
public class DataStore {
    private final ConcurrentMap<String, DataRecord> records = new ConcurrentHashMap<>();

    public DataRecord putIfAbsent(DataRecord record) {
        DataRecord existing = records.putIfAbsent(record.id(), record);
        return existing == null ? record : existing;
    }

    public Optional<DataRecord> find(String id) {
        return Optional.ofNullable(records.get(id));
    }

    public List<DataRecord> listByKey(String keyId) {
        return records.values().stream()
                .filter(r -> r.apiKeyId().equals(keyId))
                .sorted((a, b) -> Long.compare(a.createdAtMillis(), b.createdAtMillis()))
                .toList();
    }
}
