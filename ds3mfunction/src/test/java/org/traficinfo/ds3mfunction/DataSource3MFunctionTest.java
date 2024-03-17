package org.traficinfo.ds3mfunction;

import com.fnproject.fn.testing.*;
import org.junit.*;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class DataSource3MFunctionTest {

    @Rule
    public final FnTestingRule testing = FnTestingRule.createDefault();

    @Test
    public void shouldReturnGreeting() {
        testing.givenEvent().enqueue();
        testing.setConfig(DataSource3MFunction.CONFIG_KEY_NAMESPACE, "frivwhhfdcjn");
        testing.setConfig(DataSource3MFunction.CONFIG_KEY_BUCKET_NAME, "ds3mbucket");
        testing.setConfig(DataSource3MFunction.CONFIG_KEY_USE_RESOURCE_PRINCIPAL, "false");
        testing.thenRun(DataSource3MFunction.class, "handleRequest");

        FnResult result = testing.getOnlyResult();
        assertEquals("Hello, ds3mbucket!", result.getBodyAsString());
    }

}