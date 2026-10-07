package com.github.lechandonga.openapiaccessservicejava.web;

import java.util.Set;

/** Admin request to issue a new caller key. */
public record IssueKeyRequest(Set<String> scopes, Long quotaLimit, Long refillMillis) {
}
