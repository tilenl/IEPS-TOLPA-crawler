package si.uni_lj.fri.wier.contracts;

import java.util.List;

public record IngestResult(List<Long> acceptedPageIds, List<IngestRejection> rejections) {
    public IngestResult {
        // Defensive copies so callers cannot mutate lists after construction; null lists mean "none".
        acceptedPageIds = acceptedPageIds == null ? List.of() : List.copyOf(acceptedPageIds);
        rejections = rejections == null ? List.of() : List.copyOf(rejections);
    }
}
