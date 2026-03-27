package si.uni_lj.fri.wier.downloader.worker;

import si.uni_lj.fri.wier.contracts.Worker;
import si.uni_lj.fri.wier.error.ProcessingFailureHandler;

/**
 * TS-02 worker loop placeholder wired to the TS-01 {@link Worker} contract.
 *
 * <p>TS-12: when the pipeline is implemented, route processing failures through {@link
 * ProcessingFailureHandler#handleProcessingFailure} so retries use {@link si.uni_lj.fri.wier.contracts.Frontier}
 * and terminals use {@link si.uni_lj.fri.wier.contracts.Storage} with the locked strict semantics.
 */
public final class WorkerLoop implements Worker {
    @Override
    public void runLoop() {
        // NOTE: When the pipeline is implemented, route processing failures through ProcessingFailureHandler
        // (see class Javadoc). Do not bypass Frontier/Storage reschedule and terminal semantics (TS-12).
        throw new UnsupportedOperationException("WorkerLoop is pending TS-02 implementation");
    }
}
