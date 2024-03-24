package org.traficinfo.m3m.dsfunction;

import com.fnproject.fn.testing.*;
import org.junit.*;

import static org.junit.Assert.*;

public class DataSourceFunctionTest {

    @Rule
    public final FnTestingRule testing = FnTestingRule.createDefault();

    @Test
    public void shouldReturnGreeting() {
        testing.givenEvent().enqueue();
        testing.setConfig(DataSourceFunction.CONFIG_KEY_NAMESPACE, "frivwhhfdcjn");
        testing.setConfig(DataSourceFunction.CONFIG_KEY_BUCKET_NAME, "ds3mbucket");
        testing.setConfig(DataSourceFunction.CONFIG_KEY_USE_RESOURCE_PRINCIPAL, "false");
        testing.thenRun(DataSourceFunction.class, "handleRequest");

        FnResult result = testing.getOnlyResult();
        assertEquals("OK", result.getBodyAsString());
    }

}