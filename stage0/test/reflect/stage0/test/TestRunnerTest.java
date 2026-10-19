package reflect.stage0.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static reflect.stage0.test.Assertions.assertEquals;
import static reflect.stage0.test.Assertions.assertTrue;

public final class TestRunnerTest {
    @Test
    public static void passingFixture() throws IOException, InterruptedException {
        Result result = runFixture(PassingFixture.class);
        String prefix = "PASS " + PassingFixture.class.getName() + ".";
        assertEquals(0, result.exitCode());
        assertEquals(prefix + "alpha\n" + prefix + "zeta\n2 tests, 0 failures\n", result.output());
    }

    @Test
    public static void failingFixture() throws IOException, InterruptedException {
        Result result = runFixture(FailingFixture.class);
        assertEquals(1, result.exitCode());
        assertTrue(result.output().startsWith("FAIL " + FailingFixture.class.getName() + ".fails\n"));
        assertTrue(result.output().contains("java.lang.AssertionError: intentional fixture failure"));
        assertTrue(result.output().endsWith("1 tests, 1 failures\n"));
    }

    @Test
    public static void invalidMethods() throws IOException, InterruptedException {
        Result result = runFixture(InvalidFixture.class);
        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("@Test method must be public static void with no parameters"));
        assertTrue(result.output().endsWith("4 tests, 4 failures\n"));
    }

    @Test
    public static void continuesAfterFailure() {
        var bytes = new ByteArrayOutputStream();
        int exitCode = TestRunner.run(List.of(PassingFixture.class, FailingFixture.class),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, exitCode);
        assertTrue(output.indexOf("FAIL ") < output.indexOf("PASS "));
        assertTrue(output.endsWith("3 tests, 1 failures\n"));
    }

    @Test
    public static void emptySuiteFails() throws IOException, InterruptedException {
        Path empty = Files.createTempDirectory("reflect-empty-tests-");
        try {
            Result result = runJava("-cp", System.getProperty("java.class.path"),
                    TestRunner.class.getName(), empty.toString());
            assertEquals(2, result.exitCode());
            assertTrue(result.output().contains("No @Test methods found."));
        } finally {
            Files.delete(empty);
        }
    }

    @Test
    public static void runnerUsageAndMissingClassesFail() throws IOException, InterruptedException {
        String classpath = System.getProperty("java.class.path");
        Result usage = runJava("-cp", classpath, TestRunner.class.getName());
        assertEquals(2, usage.exitCode());
        assertTrue(usage.output().startsWith("Usage: TestRunner"));
        Result missing = runJava("-cp", classpath, TestRunner.class.getName(),
                "out/stage0/test-classes", "reflect.stage0.test.DoesNotExist");
        assertEquals(1, missing.exitCode());
        assertTrue(missing.output().contains("ClassNotFoundException"));
    }

    @Test
    public static void cliUsageAndErrors() throws IOException, InterruptedException {
        Result usage = runJava("-jar", "out/stage0/stage0.jar");
        assertEquals(0, usage.exitCode());
        assertTrue(usage.output().startsWith("Usage: stage0 "));
        assertEquals(usage, runJava("-jar", "out/stage0/stage0.jar", "--help"));
        assertEquals(usage, runJava("-jar", "out/stage0/stage0.jar", "-h"));
        Result unknown = runJava("-jar", "out/stage0/stage0.jar", "unknown");
        assertEquals(2, unknown.exitCode());
        assertTrue(unknown.output().startsWith("Unknown command or arguments: unknown\n"));
        assertEquals(2, runJava("-jar", "out/stage0/stage0.jar", "--help", "extra").exitCode());
    }

    private static Result runFixture(Class<?> fixture) throws IOException, InterruptedException {
        return runJava("-cp", System.getProperty("java.class.path"), TestRunner.class.getName(),
                "out/stage0/test-classes", fixture.getName());
    }

    private static Result runJava(String... args) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }

    private record Result(int exitCode, String output) {
    }

    public static final class PassingFixture {
        @Test
        public static void zeta() {
        }

        @Test
        public static void alpha() {
        }

        public static void unannotated() {
            throw new AssertionError("Unannotated methods must not run");
        }
    }

    public static final class FailingFixture {
        @Test
        public static void fails() {
            throw new AssertionError("intentional fixture failure");
        }
    }

    public static final class InvalidFixture {
        @Test
        public void instanceMethod() {
        }

        @Test
        public static int nonVoid() {
            return 0;
        }

        @Test
        static void nonPublic() {
        }

        @Test
        public static void withParameter(int value) {
        }
    }
}
