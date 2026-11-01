package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Composes installed Windows Cargo execution under the caller's existing identity.
 * Complete explicit input identities are checked before native bindings, handles or processes are acquired.
 */
final class WindowsCargoSchedule
{
    /** Installed wrapper application subtree relative to the current bundled runtime. */
    private static final Path WRAPPER = Path.of("wrapper-image", "codex-cargo-wrapper", "codex-cargo-wrapper.exe");

    /**
     * Prevents construction of this command composition boundary.
     */
    private WindowsCargoSchedule() {}

    /**
     * Runs the installed Windows adapter with the current runtime and original native standard handles.
     * @param tooling borrowed caller services retained throughout native cleanup
     * @param settings validated explicit Windows command settings
     * @return original Cargo status after complete cleanup
     * @throws IOException if input identity, execution or cleanup cannot be established
     * @throws Scheduler.PendingCleanup if native borrowers retain resources after failure
     */
    static int run(ToolingScope tooling, Scheduler.Settings settings) throws IOException
    {
        Path runtime = Path.of(System.getProperty("java.home")).toRealPath();
        return run(tooling, settings, runtime, WindowsCargoSchedule::execute);
    }

    /**
     * Validates explicit inputs before entering the native execution boundary.
     * @param tooling borrowed caller services
     * @param settings validated command settings
     * @param runtime current installed runtime root resolved by the production boundary
     * @param execution native execution boundary receiving only identity-checked inputs
     * @return original executor status without normalization
     * @throws IOException if configuration or pinned inputs cannot be verified
     * @throws Scheduler.PendingCleanup if execution retains the invocation scope until native cleanup
     */
    static int run(ToolingScope tooling, Scheduler.Settings settings, Path runtime, Execution execution) throws IOException
    {
        WindowsCargoScope scope = new WindowsCargoScope(tooling);
        boolean pending = false;
        try
        {
            WindowsCargoLaunchConfiguration configuration = WindowsCargoLaunchConfiguration.read(scope,
                settings.windowsLaunchConfiguration());
            Path wrapper = runtime.resolve(WRAPPER);
            configuration.requireIdentities(settings.fridaLibrary(), runtime, wrapper);
            if (!Files.isDirectory(settings.workspace()) || !Files.isDirectory(settings.scratch()))
                throw new IOException("Windows workspace and scratch must be existing directories");
            return execution.run(scope, settings, configuration, wrapper);
        }
        catch (Scheduler.PendingCleanup failure) { pending = true; throw failure; }
        finally { if (!pending) scope.close(); }
    }

    /**
     * Produces exact immutable native launch inputs after standard handle capture.
     * @param settings validated command arguments, working directory and scheduler controls
     * @param configuration identity-checked explicit tools and environment
     * @param wrapper installed pinned sibling wrapper executable
     * @param streams retained original local native standard handles
     * @return immutable native invocation configuration
     */
    static WindowsCargoInvocation.Configuration inputs(Scheduler.Settings settings,
        WindowsCargoLaunchConfiguration configuration, Path wrapper, WindowsCargoStreams.Root streams)
    {
        List<String> arguments = new ArrayList<>();
        arguments.add(configuration.cargo().toString());
        arguments.addAll(settings.arguments());
        return new WindowsCargoInvocation.Configuration(arguments, settings.workspace(), configuration.environment(),
            streams.input(), streams.output(), streams.error(), configuration.rustc(), wrapper, settings.fridaLibrary(),
            Map.of(wrapper, configuration.identities().get("wrapper")), settings.budget().jobs(), settings.controlTimeout());
    }

    /**
     * Constructs the actual invocation and shared coordinator from previously verified inputs.
     * @param scope invocation-owned services retained until all native borrowers close
     * @param settings validated scheduler controls and unchanged tool arguments
     * @param configuration identity-checked caller launch inputs
     * @param wrapper installed pinned wrapper executable
     * @return actual Cargo status after whole-invocation cleanup
     * @throws IOException if native execution or cleanup fails
     * @throws Scheduler.PendingCleanup if cleanup remains incomplete, retaining scope and original handles
     */
    private static int execute(WindowsCargoScope scope, Scheduler.Settings settings,
        WindowsCargoLaunchConfiguration configuration, Path wrapper) throws IOException
    {
        WindowsCargoStreams.Root streams = WindowsCargoStreams.root(scope);
        WindowsCargoInvocation invocation = null;
        Throwable primary = null;
        try
        {
            WindowsCargoInvocation.Configuration inputs = inputs(settings, configuration, wrapper, streams);
            invocation = configuration.memory() == null ? new WindowsCargoInvocation(scope, inputs) :
                new WindowsCargoInvocation(scope, inputs, configuration.memory());
            invocation.start();
            return new SchedulerCoordinator<>(invocation.session(), settings.budget(), () -> Thread.sleep(10)).run();
        }
        catch (IOException | RuntimeException | Error failure) { primary = failure; throw failure; }
        finally
        {
            try
            {
                if (invocation != null && !invocation.isClosed()) invocation.close();
                if (invocation != null && !invocation.isClosed())
                    throw new IOException("Windows Cargo attempts retain original cleanup authority");
                streams.close();
            }
            catch (IOException | RuntimeException | Error cleanup)
            {
                Throwable retained = primary == null ? cleanup : primary;
                if (retained != cleanup) retained.addSuppressed(cleanup);
                WindowsCargoInvocation owner = invocation;
                throw new Scheduler.PendingCleanup(retained, () ->
                {
                    if (owner != null && !owner.isClosed()) owner.close();
                    if (owner != null && !owner.isClosed())
                        throw new IOException("Windows Cargo attempts require their original queue cleanup");
                    streams.close();
                    scope.close();
                });
            }
        }
    }

    /**
     * Runs native execution only after explicit input and installed-image preflight.
     */
    @FunctionalInterface
    interface Execution
    {
        /**
         * Borrows verified configuration and keeps the invocation scope alive until all native borrowers finish.
         * A PendingCleanup must retain and eventually close that scope after successful native cleanup.
         * @param scope invocation-owned services
         * @param settings immutable scheduler controls and tool arguments
         * @param configuration immutable identity-checked launch inputs
         * @param wrapper pinned installed executable
         * @return original Cargo status
         * @throws IOException if native execution fails
         * @throws Scheduler.PendingCleanup if native cleanup retains the scope
         */
        int run(WindowsCargoScope scope, Scheduler.Settings settings,
            WindowsCargoLaunchConfiguration configuration, Path wrapper) throws IOException;
    }
}
