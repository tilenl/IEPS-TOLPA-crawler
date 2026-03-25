package si.uni_lj.fri.wier.downloader.fetch;

import si.uni_lj.fri.wier.contracts.Contracts;
import si.uni_lj.fri.wier.contracts.Fetcher;

public final class HttpFetcher implements Fetcher {
    @Override
    public Contracts.FetchResult fetch(String canonicalUrl) throws Contracts.FetchException {
        throw new Contracts.FetchException("HttpFetcher not implemented yet");
    }
}
