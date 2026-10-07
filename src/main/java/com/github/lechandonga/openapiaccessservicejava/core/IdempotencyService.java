package com.github.lechandonga.openapiaccessservicejava.core;

import com.github.lechandonga.openapiaccessservicejava.api.ApiError;
import com.github.lechandonga.openapiaccessservicejava.api.ApiException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Idempotent write execution with a small explicit slot state machine.
 *
 * <p>An {@code Idempotency-Key} maps to at most one slot. Slot states:
 * <ul>
 *   <li><b>RUNNING</b>: a leader is executing the action; concurrent identical
 *       submits wait on the slot lock.</li>
 *   <li><b>DONE</b>: the action finished and its result is cached. Replays
 *       return the cached result and never execute the action again.</li>
 * </ul>
 * If the leader's action fails, the slot is removed. Waiters then compete to
 * become the new leader, so a transient failure (e.g. quota exhausted at that
 * instant) is not fanned out to all concurrent peers, and the client may retry.
 *
 * <p>Payload contract: the stable {@code payloadHash} binds key to content
 * (content includes the negotiated API version). A key reused with different
 * content is rejected with {@code IDEMPOTENCY_CONFLICT}.
 */
@Service
public class IdempotencyService {

    /** Result wrapper: {@link #replayed()} is true when the cached result was returned. */
    public record Outcome<T>(T value, boolean replayed) {
    }

    private enum State {RUNNING, DONE}

    private static final class Slot {
        private volatile String payloadHash;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile State state = State.RUNNING;
        private volatile Object result;
    }

    private final Map<String, Slot> store = new ConcurrentHashMap<>();

    /**
     * Execute {@code action} at most once per idempotency key.
     *
     * @param idempotencyKey caller-supplied key (non-blank)
 * @param payloadHash    stable content hash (incl. API version)
     * @param action         business write; only the single leader runs it
     * @throws ApiException IDEMPOTENCY_KEY_MISSING / IDEMPOTENCY_CONFLICT,
     *                      or the first action's exception for the current leader
     */
    @SuppressWarnings("unchecked")
    public <T> Outcome<T> execute(String idempotencyKey, String payloadHash, Callable<T> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ApiError.IDEMPOTENCY_KEY_MISSING);
        }

        // Fast replay path: a completed identical submit needs no lock and consumes no quota.
        Slot existing = store.get(idempotencyKey);
        if (existing != null && existing.state == State.DONE) {
            if (!existing.payloadHash.equals(payloadHash)) {
                throw conflict(idempotencyKey);
            }
            return new Outcome<>((T) existing.result, true);
        }

        while (true) {
            Slot candidate = new Slot();
            candidate.payloadHash = payloadHash;
            Slot slot = store.putIfAbsent(idempotencyKey, candidate);

            if (slot == null) {
                // Became the leader.
                candidate.lock.lock();
                try {
                    T value;
                    try {
                        value = action.call();
                    } catch (ApiException ex) {
                        store.remove(idempotencyKey, candidate);
                        throw ex;
                    } catch (Exception ex) {
                        store.remove(idempotencyKey, candidate);
                        throw new ApiException(ApiError.INTERNAL_ERROR,
                                ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                    }
                    candidate.result = value;
                    candidate.state = State.DONE;
                    return new Outcome<>(value, false);
                } finally {
                    candidate.lock.unlock();
                }
            }

            // A slot exists (RUNNING or DONE) under this key.
            if (!slot.payloadHash.equals(payloadHash)) {
                throw conflict(idempotencyKey);
            }
            slot.lock.lock();
            try {
                if (slot.state == State.DONE) {
                    return new Outcome<>((T) slot.result, true);
                }
                // Leader failed and removed the slot; loop to become the new leader.
            } finally {
                slot.lock.unlock();
            }
        }
    }

    private static ApiException conflict(String idempotencyKey) {
        return new ApiException(ApiError.IDEMPOTENCY_CONFLICT,
                "Idempotency-Key '" + idempotencyKey + "' is already associated with a different payload.");
    }

    /** Number of completed idempotent outcomes (diagnostics/tests). */
    public int completedSize() {
        return (int) store.values().stream().filter(s -> s.state == State.DONE).count();
    }
}
