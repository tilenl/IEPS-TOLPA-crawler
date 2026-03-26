package si.uni_lj.fri.wier.downloader.dedup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import si.uni_lj.fri.wier.contracts.ContentHasher;

/** SHA-256 hasher implementation for content dedup contracts. */
public final class ContentHasherImpl implements ContentHasher {
    @Override
    public String sha256(String html) {
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
