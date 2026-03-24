package si.uni_lj.fri.wier.error;

public enum CrawlerErrorCategory {
    INVALID_URL,
    ROBOTS_DISALLOWED,
    FETCH_TIMEOUT,
    FETCH_HTTP_OVERLOAD,
    FETCH_HTTP_CLIENT,
    PARSER_FAILURE,
    DB_TRANSIENT,
    DB_CONSTRAINT
}
