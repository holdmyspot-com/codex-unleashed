package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Objects;

final class Libcgroup implements AutoCloseable {
    /**
     * Linux FILENAME_MAX capacity required by libcgroup's public mount iterator.
     */
    private static final int MOUNT_PATH_CAPACITY = 4096;
    private final Arena libraryArena;
    private final MethodHandle create;
    private final MethodHandle load;
    private final MethodHandle controller;
    private final MethodHandle string;
    private final MethodHandle destroy;
    private final MethodHandle free;
    /**
     * Public iterator whose first result is libcgroup's selected memory-controller mount.
     */
    private final MethodHandle mountBegin;
    /**
     * Ends the public iterator even when obtaining or validating its first result fails.
     */
    private final MethodHandle mountEnd;

    private Libcgroup(Arena libraryArena, MethodHandle create, MethodHandle load,
                     MethodHandle controller, MethodHandle string, MethodHandle destroy,
                     MethodHandle free, MethodHandle mountBegin, MethodHandle mountEnd) {
        this.libraryArena = libraryArena;
        this.create = create;
        this.load = load;
        this.controller = controller;
        this.string = string;
        this.destroy = destroy;
        this.free = free;
        this.mountBegin = mountBegin;
        this.mountEnd = mountEnd;
    }

    static Libcgroup open(Path library) throws IOException {
        Objects.requireNonNull(library, "library");
        var arena = Arena.ofConfined();
        try {
            var linker = Linker.nativeLinker();
            var lookup = SymbolLookup.libraryLookup(library, arena);
            var init = linker.downcallHandle(lookup.find("cgroup_init").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
            int initialized = (int) call(init);
            if (initialized != 0)
                throw new IOException("cgroup_init returned status " + initialized);
            var version = linker.downcallHandle(lookup.find("cgroup_get_controller_version").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            try (var arguments = Arena.ofConfined()) {
                var output = arguments.allocate(ValueLayout.JAVA_INT);
                output.set(ValueLayout.JAVA_INT, 0, 0);
                int status = (int) call(version, arguments.allocateFrom("memory"), output);
                int actual = output.get(ValueLayout.JAVA_INT, 0);
                if (status != 0 || actual != 2)
                    throw new IOException("Memory controller is not readable as v2: status=" + status + " version=" + actual);
            }
            var create = linker.downcallHandle(lookup.find("cgroup_new_cgroup").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var load = linker.downcallHandle(lookup.find("cgroup_get_cgroup").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            var controller = linker.downcallHandle(lookup.find("cgroup_get_controller").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var string = linker.downcallHandle(lookup.find("cgroup_get_value_string").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var destroy = linker.downcallHandle(lookup.find("cgroup_free").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            var free = linker.downcallHandle(linker.defaultLookup().find("free").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            var mountBegin = linker.downcallHandle(lookup.find("cgroup_get_subsys_mount_point_begin").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var mountEnd = linker.downcallHandle(lookup.find("cgroup_get_subsys_mount_point_end").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            return new Libcgroup(arena, create, load, controller, string, destroy, free, mountBegin, mountEnd);
        } catch (IOException | RuntimeException | Error failure) {
            arena.close();
            throw failure;
        }
    }

    /**
     * Reads the first memory-controller mount through libcgroup's public iterator.
     * The library contract identifies this as the mount used for subsequent group pathname reads.
     * @return absolute selected mount path
     * @throws IOException if the iterator fails or returns a nonabsolute path
     */
    Path selectedMemoryMount() throws IOException {
        try (var arguments = Arena.ofConfined()) {
            var handle = arguments.allocate(ValueLayout.ADDRESS);
            handle.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            var path = arguments.allocate(MOUNT_PATH_CAPACITY);
            Throwable failure = null;
            try {
                int status = (int) call(mountBegin, arguments.allocateFrom("memory"), handle, path);
                if (status != 0)
                    throw new IOException("Memory mount iterator begin returned status " + status);
                Path selected = Path.of(path.getString(0));
                if (!selected.isAbsolute())
                    throw new IOException("Memory mount iterator returned a relative path: " + selected);
                return selected;
            } catch (IOException | RuntimeException | Error thrown) {
                failure = thrown;
                throw thrown;
            } finally {
                try {
                    int status = (int) call(mountEnd, handle);
                    if (status != 0)
                        throw new IOException("Memory mount iterator end returned status " + status);
                } catch (IOException | RuntimeException | Error cleanup) {
                    if (failure == null)
                        throw cleanup;
                    failure.addSuppressed(cleanup);
                }
            }
        }
    }

    CgroupBudget.Raw read(Path group) throws IOException {
        try (var arguments = Arena.ofConfined()) {
            var slot = arguments.allocate(ValueLayout.ADDRESS);
            var pointer = (MemorySegment) call(create, arguments.allocateFrom(group.toString()));
            if (pointer.equals(MemorySegment.NULL))
                throw new IOException("cgroup_new_cgroup returned NULL for " + group);
            slot.set(ValueLayout.ADDRESS, 0, pointer);
            try {
                int status = (int) call(load, pointer);
                if (status != 0)
                    return new CgroupBudget.Raw(status, false, -1, null, -1, null);
                var memory = (MemorySegment) call(controller, pointer, arguments.allocateFrom("memory"));
                if (memory.equals(MemorySegment.NULL))
                    return new CgroupBudget.Raw(0, false, -1, null, -1, null);
                var limit = value(arguments, memory, "memory.max");
                var current = value(arguments, memory, "memory.current");
                return new CgroupBudget.Raw(0, true, limit.status(), limit.text(), current.status(), current.text());
            } finally {
                call(destroy, slot);
                if (!slot.get(ValueLayout.ADDRESS, 0).equals(MemorySegment.NULL))
                    throw new IOException("cgroup_free retained the group pointer for " + group);
            }
        }
    }

    private Value value(Arena arguments, MemorySegment memory, String name) throws IOException {
        var slot = arguments.allocate(ValueLayout.ADDRESS);
        slot.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
        int status = (int) call(string, memory, arguments.allocateFrom(name), slot);
        var pointer = slot.get(ValueLayout.ADDRESS, 0);
        try {
            String text = null;
            if (status == 0 && !pointer.equals(MemorySegment.NULL))
                text = pointer.reinterpret(Long.MAX_VALUE).getString(0);
            return new Value(status, text);
        } finally {
            call(free, pointer);
        }
    }

    private record Value(int status, String text) {}

    private static Object call(MethodHandle operation, Object... arguments) throws IOException {
        try {
            return operation.invokeWithArguments(arguments);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IOException("Native C downcall failed", failure);
        }
    }

    @Override
    public void close() {
        libraryArena.close();
    }
}
