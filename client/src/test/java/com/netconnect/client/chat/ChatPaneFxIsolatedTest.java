package com.netconnect.client.chat;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Runs {@link ChatPaneFxScenarios} (real JavaFX toolkit) in a child JVM and requires exit code 0. */
class ChatPaneFxIsolatedTest {
    @Test
    void chatPaneBehavesCorrectlyOnARealJavaFxToolkit() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ChatPaneFxScenarios.class.getName());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        String log = new String(output, StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            fail("child JVM timed out:" + File.separator + log);
        }
        assertEquals(0, process.exitValue(), "child JVM output:\n" + log);
        assertTrue(log.contains("PASS groupSendRunsOffTheFxThreadAndClearsInput"), log);
        assertFalse(log.contains("FAIL "), log);
    }
}
