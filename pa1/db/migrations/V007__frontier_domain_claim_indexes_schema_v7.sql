-- Schema v7: support domain-scoped frontier claims (per-domain pump dequeue).
-- Partial index narrows to FRONTIER rows; site.domain filter uses idx_site_domain.

CREATE INDEX IF NOT EXISTS idx_site_domain ON crawldb.site (domain);

CREATE INDEX IF NOT EXISTS idx_page_frontier_site_priority
    ON crawldb.page (
        page_type_code,
        site_id,
        relevance_score DESC,
        next_attempt_at ASC,
        accessed_time ASC,
        id ASC
    )
    WHERE page_type_code = 'FRONTIER';

UPDATE crawldb.schema_version SET version = 7 WHERE id = 1;
