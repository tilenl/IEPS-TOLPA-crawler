package si.uni_lj.fri.wier.contracts;

/**
 * Optional document-level strings extracted from HTML (e.g. {@code <title>}, meta description).
 *
 * <p>Fields are absent when not extracted or not present in the document. When present, Stage B storage
 * may persist them to {@code crawldb.page_data} (TS-10 {@code insertPageData}) inside
 * {@link si.uni_lj.fri.wier.contracts.Storage#persistFetchOutcomeWithLinks}.
 *
 * @param title trimmed document title, or null if missing.
 * @param metaDescription trimmed meta description when present, or null.
 */
public record ExtractedPageMetadata(String title, String metaDescription) {}
