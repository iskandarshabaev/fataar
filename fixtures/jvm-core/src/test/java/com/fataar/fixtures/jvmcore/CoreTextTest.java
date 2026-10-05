package com.fataar.fixtures.jvmcore;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class CoreTextTest {
    @Test public void combinesMarkers() { assertEquals("left:right", CoreText.join("left", "right")); }
}
