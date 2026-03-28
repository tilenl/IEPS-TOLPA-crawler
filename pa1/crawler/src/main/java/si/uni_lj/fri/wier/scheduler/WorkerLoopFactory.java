/*
 * Factory for one TS-02 {@link si.uni_lj.fri.wier.downloader.worker.WorkerLoop} with a stable worker id.
 *
 * Callers: {@link VirtualThreadCrawlerScheduler}. Created: 2026-03.
 */

package si.uni_lj.fri.wier.scheduler;

import java.util.concurrent.atomic.AtomicBoolean;
import si.uni_lj.fri.wier.downloader.worker.WorkerLoop;

@FunctionalInterface
public interface WorkerLoopFactory {

    /**
     * @param workerId passed to frontier claims and logs (TS-14)
     * @param shutdown shared flag; when true the loop exits after finishing or skipping work
     */
    WorkerLoop create(String workerId, AtomicBoolean shutdown);
}
