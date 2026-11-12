package org.traficinfo.dsfunctions;

import static org.junit.Assert.fail;

/** Check that failed handlers reach the runtime as errors rather than successful KO responses. */
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
            // The Functions runtime records an error response for the alarm.
        } catch (Exception unexpected) {
            throw new AssertionError("Expected IllegalStateException", unexpected);
        }
    }
}
