package si.uni_lj.fri.wier.contracts;

public interface Fetcher {
    Contracts.FetchResult fetch(String canonicalUrl) throws Contracts.FetchException;
}
