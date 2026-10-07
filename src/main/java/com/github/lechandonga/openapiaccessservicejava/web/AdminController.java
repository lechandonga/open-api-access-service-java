package com.github.lechandonga.openapiaccessservicejava.web;

import com.github.lechandonga.openapiaccessservicejava.core.ApiKey;
import com.github.lechandonga.openapiaccessservicejava.core.KeyService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local provisioning API for the in-memory bootstrap. Not intended to be
 * publicly exposed; in a deployment it would sit behind an operator boundary.
 */
@RestController
@RequestMapping("/admin/keys")
public class AdminController {

    private final KeyService keyService;

    public AdminController(KeyService keyService) {
        this.keyService = keyService;
    }

    @PostMapping
    public KeyResponse issue(@RequestBody(required = false) IssueKeyRequest request) {
        IssueKeyRequest body = request == null
                ? new IssueKeyRequest(null, null, null)
                : request;
        ApiKey key = keyService.issue(body.scopes(), body.quotaLimit(), body.refillMillis());
        return KeyResponse.of(key);
    }

    @PostMapping("/{keyId}/revoke")
    public KeyResponse revoke(@PathVariable String keyId) {
        return KeyResponse.of(keyService.revoke(keyId));
    }
}
