package si.uni_lj.fri.wier.contracts;

import java.time.Duration;

public interface Scheduler {
    void start(int workerCount);

    void stopGracefully(Duration timeout);
}
