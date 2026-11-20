package org.traficinfo.dsfunctions;

import static org.junit.Assert.fail;

/** Check the fallback path when a failed handler has no usable failure metrics backend. */
public final class FunctionFailureAssertions {
    private FunctionFailureAssertions() { }

    @FunctionalInterface
    public interface Invocation {
        void run() throws Exception;
    }

    public static void assertFunctionFailure(Invocation invocation) {
        try {
            invocation.run();
            fail("Expected the failed invocation to throw IllegalStateException");
        } catch (IllegalStateException expected) {
            // Missing failure monitoring preserves the invocation error for the OCI alarm.
        } catch (Exception unexpected) {
            throw new AssertionError("Expected IllegalStateException", unexpected);
        }
    }
}
