/*
 * TS-09: URL dedup policy is documented on {@link si.uni_lj.fri.wier.queue.dedup.UrlSeenIndex}; this test locks
 * the intent that the type is not publicly constructible.
 */

package si.uni_lj.fri.wier.unit.queue.dedup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.queue.dedup.UrlSeenIndex;

class UrlSeenIndexUnitTest {

    @Test
    void hasNoPublicConstructors() {
        assertEquals(0, UrlSeenIndex.class.getConstructors().length);
        Constructor<?>[] declared = UrlSeenIndex.class.getDeclaredConstructors();
        assertEquals(1, declared.length);
        assertTrue(Modifier.isPrivate(declared[0].getModifiers()));
    }
}
