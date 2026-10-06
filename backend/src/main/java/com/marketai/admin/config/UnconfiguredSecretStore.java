package com.marketai.admin.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnExpression("'${app.admin.secret-store-command:}'.isEmpty()")
public class UnconfiguredSecretStore implements SecretStore {
    @Override public boolean available() { return false; }
    @Override public void put(String name, String value) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
            "No secret manager is connected. Set ADMIN_SECRET_STORE_COMMAND to a command that stores a secret, or rotate it with scripts/secrets.sh.");
    }
}
