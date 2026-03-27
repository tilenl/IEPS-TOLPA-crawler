/*
 * TS-02 worker loop — claim, fetch, parse, persist (not yet implemented).
 *
 * Callers: future scheduler using {@link si.uni_lj.fri.wier.scheduler.policies.SchedulingPolicy#newVirtualThreadWorkerExecutor()}.
 *
 * TS-14: each loop should use a stable {@link si.uni_lj.fri.wier.scheduler.policies.SchedulingPolicy#newWorkerId()}
 * for lease ownership and observability; graceful shutdown must finish the current leased page before exit; do not
 * hold JDBC connections across fetch or headless segments.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.downloader.worker;

import si.uni_lj.fri.wier.contracts.Worker;
import si.uni_lj.fri.wier.error.ProcessingFailureHandler;
import si.uni_lj.fri.wier.scheduler.policies.SchedulingPolicy;

/**
 * Worker loop placeholder (TS-02). Use {@link SchedulingPolicy#newWorkerId()} for TS-14 identity when implementing
 * {@link #runLoop()}.
 *
 * <p>TS-12: route processing failures through {@link ProcessingFailureHandler#handleProcessingFailure} so retries
 * use frontier and storage semantics.
 */
public final class WorkerLoop implements Worker {

    /** Delegates to {@link si.uni_lj.fri.wier.scheduler.policies.SchedulingPolicy#newWorkerId()} (TS-14). */
    public static String generateWorkerId() {
        return SchedulingPolicy.newWorkerId();
    }

    @Override
    public void runLoop() {
        // NOTE: When the pipeline is implemented, route processing failures through ProcessingFailureHandler
        // (see class Javadoc). Do not bypass Frontier/Storage reschedule and terminal semantics (TS-12).
        throw new UnsupportedOperationException("WorkerLoop is pending TS-02 implementation");
    }
}
