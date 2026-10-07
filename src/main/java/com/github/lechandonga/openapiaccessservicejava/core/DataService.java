package com.github.lechandonga.openapiaccessservicejava.core;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory data store for ingested records. */
@Service
public class DataService {

    private final Map<String, DataRecord> records = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public DataRecord create(String name, String type, String ownerKeyId) {
        byte[] raw = new byte[12];
        random.nextBytes(raw);
        String id = "rec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        DataRecord record = new DataRecord(id, name, type, Instant.now(), ownerKeyId);
        DataRecord previous = records.putIfAbsent(id, record);
        if (previous != null) {
            throw new IllegalStateException("Generated record id collided: " + id);
        }
        return record;
    }

    public List<DataRecord> list() {
        return List.copyOf(records.values());
    }

    public int size() {
        return records.size();
    }
}
