package si.uni_lj.fri.wier.contracts;

public record IngestRejection(DiscoveredUrl discoveredUrl, String reasonCode) {}
