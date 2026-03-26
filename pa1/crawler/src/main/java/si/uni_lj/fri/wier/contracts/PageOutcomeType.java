package si.uni_lj.fri.wier.contracts;

public enum PageOutcomeType {
    // This page row holds the fetched HTML payload (canonical owner when hashes collide).
    HTML,
    // Same content as an existing owner; row stores hash/metadata but not duplicate HTML bytes.
    DUPLICATE,
    // Non-HTML response or unusable body; no stored HTML column.
    BINARY
}
