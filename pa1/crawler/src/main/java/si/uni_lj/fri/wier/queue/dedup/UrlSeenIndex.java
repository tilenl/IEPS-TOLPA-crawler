/*
 * TS-09 queue-side deduplication anchor (URL level).
 *
 * This module does not host an in-memory URL set. TS-09 normative behavior is database-authoritative:
 * uniqueness on canonical URL in {@code crawldb.page} and insert-if-absent via
 * {@link si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository#insertFrontierIfAbsent}.
 *
 * Call paths: {@link si.uni_lj.fri.wier.queue.enqueue.EnqueueCoordinator#tryEnqueue} for Stage A enqueue, and
 * discovery batches inside {@link si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository#ingestDiscoveredUrls}
 * (invoked from {@code persistFetchOutcomeWithLinks}). There is no probabilistic pre-check stage.
 *
 * Change log: 2026-03 — replaced empty placeholder with documented non-instantiable type for TS-09 mapping.
 */

package si.uni_lj.fri.wier.queue.dedup;

/**
 * Marker type for TS-09 URL deduplication ownership in the queue package.
 *
 * <p>URL “seen” state lives entirely in PostgreSQL ({@code ON CONFLICT (url)} upsert returning the stable
 * {@code page} id). This class exists so the implementation directory and TS mapping have an explicit home for
 * the policy description; it is not a runtime index.
 */
public final class UrlSeenIndex {

    private UrlSeenIndex() {}
}
