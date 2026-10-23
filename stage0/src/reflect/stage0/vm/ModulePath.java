package reflect.stage0.vm;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import reflect.stage0.lib.Nullable;

/**
 * The module path (MOD-03, Q88): directories, in which the module of requires m is the file
 * m.rflc. Exactly one directory may hold it; a directory that the path names twice counts once.
 */
public final class ModulePath implements ModuleSource {
    private final List<Path> directories;

    public ModulePath(List<Path> directories) {
        this.directories = List.copyOf(directories);
    }

    /** The directories of a --module-path value, which the platform's path separator divides. */
    public static ModulePath parse(String text) {
        var directories = new ArrayList<Path>();
        for (String directory : text.split(File.pathSeparator)) {
            if (!directory.isEmpty()) {
                directories.add(Path.of(directory));
            }
        }
        return new ModulePath(directories);
    }

    List<Path> directories() {
        return directories;
    }

    @Override
    public @Nullable ModuleData find(String name) {
        Path found = null;
        for (Path directory : directories) {
            Path file = directory.resolve(name + ".rflc");
            if (Files.isRegularFile(file)) {
                if (found == null) {
                    found = file;
                } else if (!same(found, file)) {
                    throw new Stop("two directories of the module path hold " + name + ".rflc: " + found + " and "
                            + file + " (MOD-03)");
                }
            }
        }
        if (found == null) {
            return null;
        }
        try {
            return new ModuleData(found.toString(), Files.readAllBytes(found));
        } catch (IOException e) {
            throw new Stop("cannot read " + found + ": " + e);
        }
    }

    private static boolean same(Path first, Path second) {
        try {
            return Files.isSameFile(first, second);
        } catch (IOException e) {
            throw new Stop("cannot read " + second + ": " + e);
        }
    }
}
