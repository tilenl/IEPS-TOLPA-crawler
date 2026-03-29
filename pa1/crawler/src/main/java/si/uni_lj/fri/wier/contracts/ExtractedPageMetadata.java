package si.uni_lj.fri.wier.contracts;

/**
 * Optional document-level strings extracted from HTML (e.g. {@code <title>}, meta description).
 *
 * <p>Fields are absent when not extracted or not present in the document. They are not persisted to
 * {@code crawldb.page_data}; consumers read document-level strings from {@code crawldb.page.html_content} when
 * needed (see {@link si.uni_lj.fri.wier.contracts.Storage#persistFetchOutcomeWithLinks}).
 *
 * @param title trimmed document title, or null if missing.
 * @param metaDescription trimmed meta description when present, or null.
 */
public record ExtractedPageMetadata(String title, String metaDescription) {}
