/*
 * TS-09 Level 2 content deduplication: SHA-256 over UTF-8 HTML for {@code page.content_hash} and
 * {@code content_owner} registration in storage.
 *
 * {@link si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository} uses this implementation by default so
 * fetch-time and persist-time hashes stay identical.
 *
 * Change log: 2026-03 — file header and API docs for TS-09 traceability.
 */

package si.uni_lj.fri.wier.downloader.dedup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import si.uni_lj.fri.wier.contracts.ContentHasher;

/**
 * Produces lowercase hexadecimal SHA-256 digests of HTML payloads for TS-09 / TS-10 content ownership.
 */
public final class ContentHasherImpl implements ContentHasher {

    /**
     * Computes a deterministic SHA-256 fingerprint of the given string.
     *
     * @param html body text; {@code null} is treated as empty (hash of empty string) so callers need not branch
     * @return 64-character lowercase hex string, never null
     * @throws IllegalStateException if the JRE lacks SHA-256 (should not happen on supported runtimes)
     */
    @Override
    public String sha256(String html) {
        // Null payloads are normalized so optional bodies do not produce NPEs; empty string is a stable choice.
        String value = html == null ? "" : html;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Missing SHA-256 support", e);
        }
    }
}
