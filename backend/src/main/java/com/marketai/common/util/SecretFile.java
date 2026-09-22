package com.marketai.common.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

/**
 * Writes a file that holds a secret, then takes away everyone else's access to it.
 *
 * <p>{@code gmail.env} carries the generated {@code JWT_SECRET} and
 * {@code PDF_PASSWORD_ENC_KEY} — the JWT signing key and the key that decrypts saved PDF
 * passwords. Both were written with the process's default umask, which on a typical host leaves
 * them world-readable: any local account could read the signing key and mint tokens for any user.
 *
 * <p>Permissions are tightened after the write rather than at creation, because the file is
 * usually being rewritten in place and an existing file keeps its old mode either way. On a
 * filesystem without POSIX permissions the restriction is skipped rather than failing the write —
 * losing the secret entirely would be worse.
 */
public final class SecretFile {

    private static final Set<PosixFilePermission> OWNER_ONLY =
        PosixFilePermissions.fromString("rw-------");

    private SecretFile() {}

    public static void writeLines(Path path, List<String> lines) throws IOException {
        Files.write(path, lines, StandardCharsets.UTF_8);
        restrictToOwner(path);
    }

    public static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem, or the owner changed underneath us. Nothing to do here; the
            // caller's own logging covers the write itself.
        }
    }
}
