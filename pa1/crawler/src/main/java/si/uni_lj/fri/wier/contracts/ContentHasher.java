package si.uni_lj.fri.wier.contracts;

/** Hashes HTML payloads for content deduplication contracts (TS-09/TS-10). */
public interface ContentHasher {
    String sha256(String html);
}
