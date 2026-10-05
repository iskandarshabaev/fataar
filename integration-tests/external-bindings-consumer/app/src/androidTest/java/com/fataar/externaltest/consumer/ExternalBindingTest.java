package com.fataar.externaltest.consumer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;
@RunWith(AndroidJUnit4.class)
public final class ExternalBindingTest {
    @Test public void transitiveApiBindingsInflateAndPropagateValues() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            assertEquals("from-sdk:from-sdk:from-sdk|from-consumer:from-consumer:from-consumer",
                ConsumerProbe.inspect(InstrumentationRegistry.getInstrumentation().getTargetContext())));
    }
}
