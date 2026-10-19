package reflect.stage0.test;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

public final class TestRunner {
    private TestRunner() {
    }

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        if (args.length == 0) {
            System.err.println("Usage: TestRunner <classes-directory> [class ...]");
            System.exit(2);
        }
        Path root = Path.of(args[0]);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Test classes directory does not exist: " + root);
        }
        var names = new TreeSet<String>();
        if (args.length > 1) {
            names.addAll(Arrays.asList(args).subList(1, args.length));
        } else {
            try (var paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("Test.class"))
                        .filter(path -> !path.getFileName().toString().contains("$"))
                        .forEach(path -> {
                            String name = root.relativize(path).toString();
                            names.add(name.substring(0, name.length() - ".class".length())
                                    .replace(File.separatorChar, '.'));
                        });
            }
        }
        var classes = new ArrayList<Class<?>>();
        for (String name : names) {
            classes.add(Class.forName(name));
        }
        System.exit(run(classes, System.out));
    }

    public static int run(List<Class<?>> classes, PrintStream out) {
        int count = 0;
        int failures = 0;
        var ordered = new ArrayList<>(classes);
        ordered.sort(Comparator.comparing(Class::getName));
        for (Class<?> type : ordered) {
            Method[] methods = type.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName)
                    .thenComparing(Method::toGenericString));
            for (Method method : methods) {
                if (!method.isAnnotationPresent(Test.class)) {
                    continue;
                }
                count++;
                Throwable failure = null;
                if (!Modifier.isPublic(method.getModifiers())
                        || !Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 0 || method.getReturnType() != void.class) {
                    failure = new IllegalArgumentException(
                            "@Test method must be public static void with no parameters");
                } else {
                    try {
                        method.invoke(null);
                    } catch (InvocationTargetException e) {
                        failure = e.getCause();
                    } catch (IllegalAccessException e) {
                        failure = e;
                    }
                }
                String name = type.getName() + "." + method.getName();
                if (failure == null) {
                    out.println("PASS " + name);
                } else {
                    failures++;
                    out.println("FAIL " + name);
                    failure.printStackTrace(out);
                }
            }
        }
        out.println(count + " tests, " + failures + " failures");
        if (count == 0) {
            out.println("No @Test methods found.");
            return 2;
        }
        return failures == 0 ? 0 : 1;
    }
}
