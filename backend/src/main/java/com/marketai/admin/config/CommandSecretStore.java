package com.marketai.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Hands the secret to a command you control (an `aws secretsmanager` / `vault` / `gcloud` wrapper).
 * The command is run without a shell; the secret name is its final argument and the value arrives
 * on stdin, so it never appears in a process listing, the environment, or our logs.
 */
@Component
@Slf4j
@ConditionalOnExpression("!'${app.admin.secret-store-command:}'.isEmpty()")
public class CommandSecretStore implements SecretStore {

    private final List<String> command;

    public CommandSecretStore(@Value("${app.admin.secret-store-command}") String command) {
        this.command = Arrays.stream(command.trim().split("\\s+")).toList();
    }

    @Override public boolean available() { return true; }

    @Override
    public void put(String name, String value) {
        List<String> cmd = new ArrayList<>(command); cmd.add(name);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            try (var out = p.getOutputStream()) { out.write(value.getBytes(StandardCharsets.UTF_8)); }
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The secret manager did not respond in time."); }
            if (p.exitValue() != 0) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The secret manager refused the update.");
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) {
            log.warn("Secret store command failed: {}", e.getClass().getSimpleName());   // never the message: it could echo arguments
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The secret manager could not be reached.");
        }
    }
}
