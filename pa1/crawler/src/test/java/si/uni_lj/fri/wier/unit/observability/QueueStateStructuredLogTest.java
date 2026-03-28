package si.uni_lj.fri.wier.unit.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.observability.QueueStateStructuredLog;

class QueueStateStructuredLogTest {

    @Test
    void safeDomainForLog_extractsHost() {
        assertEquals("example.com", QueueStateStructuredLog.safeDomainForLog("https://Example.com/path"));
    }

    @Test
    void safeDomainForLog_emptyOnBadUrl() {
        assertEquals("", QueueStateStructuredLog.safeDomainForLog("not-a-url"));
    }
}
