package com.fataar.nativeplayground.consumer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;
@RunWith(AndroidJUnit4.class)
public final class NativeSmokeTest {
    @Test public void embeddedJniAndRegisteredNativesWorkThroughSdk() {
        assertEquals("34:30:117", ConsumerProbe.sdkValue(10));
        assertEquals("8:-9:104", ConsumerProbe.sdkValue(-3));
    }
    @Test public void consumerCompilesAndRunsSharedStaticAndHeaderOnlyPrefabModules() {
        assertEquals(245, ConsumerProbe.prefabValue(10));
        assertEquals(128, ConsumerProbe.prefabValue(-3));
    }
}
