package si.uni_lj.fri.wier.unit.queue;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.queue.enqueue.EnqueueService;

class EnqueueServiceLoggingTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(EnqueueService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void budgetDropped_log_contains_configKey_and_remediationHint() {
        new EnqueueService().logBudgetDropped("https://github.com/x", "github.com");
        String msg = appender.list.getFirst().getFormattedMessage();
        assertTrue(msg.contains("configKey=crawler.budget.maxTotalPages"));
        assertTrue(msg.contains("remediationHint="));
        assertTrue(msg.contains("crawler.budget.maxTotalPages"));
        assertTrue(msg.contains("workerId=ingestion"));
        assertTrue(msg.contains("pageId=0"));
    }

    @Test
    void frontierDeferred_log_contains_maxFrontierRows_key() {
        new EnqueueService().logFrontierDeferred("https://github.com/y", "github.com");
        String msg = appender.list.getFirst().getFormattedMessage();
        assertTrue(msg.contains("configKey=crawler.budget.maxFrontierRows"));
        assertTrue(msg.contains("workerId=ingestion"));
    }
}
