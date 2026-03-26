package si.uni_lj.fri.wier.contracts;

import java.time.Instant;

public record FetchResult(int statusCode, String contentType, String body, Instant fetchedAt) {}
