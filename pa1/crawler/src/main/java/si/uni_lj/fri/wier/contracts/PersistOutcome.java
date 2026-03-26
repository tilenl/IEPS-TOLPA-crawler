package si.uni_lj.fri.wier.contracts;

public record PersistOutcome(
        long pageId, PageOutcomeType outcomeType, Long canonicalOwnerPageId, IngestResult ingestResult) {}
