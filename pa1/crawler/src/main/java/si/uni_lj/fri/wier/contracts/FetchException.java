package si.uni_lj.fri.wier.contracts;

/**
 * Recoverable fetch failure surfaced by the {@link Fetcher} contract.
 *
 * <p>This exception class preserves a machine-readable category so worker recovery policies can map
 * failures to retry or terminal transitions without parsing ad-hoc strings.
 */
public class FetchException extends Exception {
    private final String category;

    public FetchException(String category, String message) {
        super(message);
        this.category = category;
    }

    public FetchException(String category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public String category() {
        return category;
    }
}
