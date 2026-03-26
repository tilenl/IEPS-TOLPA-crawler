package si.uni_lj.fri.wier.contracts;

/**
 * One image reference produced by HTML parsing ({@code img[src]}) per TS-04.
 *
 * <p>Carries a canonical image URL plus optional filename and MIME hint derived during extraction.
 * Binary payloads are never part of this contract; {@code crawldb.image.data} remains NULL on insert
 * (TS-11).
 *
 * <p>Used inside {@link ParseResult}; storage maps rows to the originating HTML {@code page_id}.
 *
 * @param canonicalUrl canonical absolute URL for the image resource (after TS-05 normalization at the
 *     parser boundary).
 * @param filename display or path filename when inferable (e.g. last path segment); may be null if
 *     only the URL is known and the repository derives a short name.
 * @param contentType MIME type when inferable from extension or response metadata; may be null.
 */
public record ExtractedImage(String canonicalUrl, String filename, String contentType) {}
