import me.shedaniel.rei.impl.client.registry.display.DisplayRegistryImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DisplayRegistryBootstrapTest {
    @Test
    void constructsEmptyHolderBeforeClientInternalsAreAttached() {
        // The registry superclass creates this holder while REI is attaching its internals.
        // Reading ConfigObject here caused the reported startup assertion.
        var holder = new DisplayRegistryImpl.ClientDisplaysHolder();
        assertEquals(0, holder.size());
        assertEquals(0, holder.getUnmodifiable().size());
    }
}
