package si.uni_lj.fri.wier.contracts;

/**
 * Optional document-level strings extracted from HTML (e.g. {@code <title>}, meta description).
 *
 * <p>Fields are absent when not extracted or not present in the document. Persistence of these values
 * (if any) is owned by storage/worker policy outside the minimal image/link paths in TS-10.
 *
 * @param title trimmed document title, or null if missing.
 * @param metaDescription trimmed meta description when present, or null.
 */
public record ExtractedPageMetadata(String title, String metaDescription) {}
