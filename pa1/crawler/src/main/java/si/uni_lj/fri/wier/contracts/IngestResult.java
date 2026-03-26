package si.uni_lj.fri.wier.contracts;

import java.util.List;

public record IngestResult(List<Long> acceptedPageIds, List<IngestRejection> rejections) {
    public IngestResult {
        acceptedPageIds = acceptedPageIds == null ? List.of() : List.copyOf(acceptedPageIds);
        rejections = rejections == null ? List.of() : List.copyOf(rejections);
    }
}
