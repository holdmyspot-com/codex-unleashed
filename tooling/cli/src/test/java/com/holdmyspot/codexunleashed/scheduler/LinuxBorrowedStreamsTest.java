package com.holdmyspot.codexunleashed.scheduler;

import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Tests authorization and one-shot ownership using controlled native-operation fixtures, without native integration claims. */
public final class LinuxBorrowedStreamsTest {
    /** Creates focused owner checks. */
    public LinuxBorrowedStreamsTest() { }
    private static LinuxProcessTree.Launch launch(List<String> arguments, Map<String,String> environment) {
        Path path = Path.of("/approved");
        return new LinuxProcessTree.Launch(path,path,path,path,arguments,environment,path,1000,1000,
            LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
    }
    private static LinuxBorrowedStreams.TerminalContext terminal() {
        var stream = new LinuxBorrowedStreams.Terminal(false,"",0,0,List.of());
        return new LinuxBorrowedStreams.TerminalContext(1,1,List.of(stream,stream,stream));
    }
    private static LinuxBorrowedStreams.Binding binding(long sequence, byte[] secret, LinuxProcessTree.Launch launch) {
        return new LinuxBorrowedStreams.Binding(new UUID(1,1),sequence,new LinuxBorrowedStreams.Peer(1,1,1000,1000),
            Path.of("/owned/streams.sock"),secret,terminal(),launch);
    }
    /** A partial native install failure consumes installation before any retry can stage descriptors. */
    @Test public void partialInstallCannotBeRetried() throws Exception {
        for (int failedDup = 0; failedDup < 3; failedDup++) {
            int[] installs = {0}; int[] closes = {0}; int failureIndex = failedDup;
            var operations = new LinuxBorrowedStreams.Installation() {
                public void validate(LinuxBorrowedStreams.TerminalContext expected) { }
                public void install() throws IOException { installs[0]++; throw new IOException("controlled dup2 index" + failureIndex); }
                public void close() { closes[0]++; }
            };
            var pending = new LinuxBorrowedStreams.Pending(operations);
            try (pending) {
                requireException(IOException.class, () -> pending.installBeforeReady(terminal()));
                requireException(IllegalStateException.class, () -> pending.installBeforeReady(terminal()));
            }
            pending.close();
            requireThat(installs[0], "installationCalls").isEqualTo(1,"expected");
            requireThat(closes[0], "cleanupCalls").isEqualTo(1,"expected");
        }
    }
    /** Incompatible terminal validation never installs and also consumes the pending gate. */
    @Test public void terminalMismatchWithdrawsInstallation() throws Exception {
        int[] installs={0};
        var operations=new LinuxBorrowedStreams.Installation() {
            public void validate(LinuxBorrowedStreams.TerminalContext expected)throws IOException { throw new IOException("different session"); }
            public void install(){installs[0]++;}
            public void close(){}
        };
        try(var pending=new LinuxBorrowedStreams.Pending(operations)) {
            requireException(IOException.class,()->pending.installBeforeReady(terminal()));
            requireException(IllegalStateException.class,()->pending.installBeforeReady(terminal()));
        }
        requireThat(installs[0],"installedDescriptors").isEqualTo(0,"expected");
    }
    /** Closing consumes native descriptor numbers before invoking a failing close, preventing Linux close retries. */
    @Test public void nativeCloseFailuresRetainDiagnosticsWithoutRetryingNumbers()throws Exception {
        List<Integer> closed=new ArrayList<>();
        var descriptors=new LinuxBorrowedStreams.Descriptors(fd->{closed.add(fd);throw new IOException("close"+fd);});
        descriptors.add(8);descriptors.add(9);
        IOException failure=requireException(IOException.class,descriptors::close);
        descriptors.close();
        requireThat(closed,"closeCalls").isEqualTo(List.of(9,8),"expected");
        requireThat(failure.getSuppressed().length,"suppressedCleanupFailures").isEqualTo(1,"expected");
    }
    /** Approved immutable launch equality permits only owned containment derivation. */
    @Test public void authorizesExecutionButRejectsAnotherCommandOrEnvironment()throws Exception {
        var approved=launch(List.of("/compiler","","argument space"),Map.of("EMPTY","","VALUE","one"));
        byte[] secret=new byte[32];var binding=binding(1,secret,approved);secret[0]=7;
        Path changed=Path.of("/derived");
        binding.requireExecution(new LinuxProcessTree.Launch(changed,changed,approved.javaExecutable(),approved.classPath(),
            approved.command(),approved.environment(),approved.directory(),1000,1000,approved.executionMode()));
        requireException(IOException.class,()->binding.requireExecution(launch(List.of("/compiler","changed"),approved.environment())));
        requireException(IOException.class,()->binding.requireExecution(launch(approved.command(),Map.of("EMPTY","","VALUE","two"))));
        requireThat(binding.approvedLaunch(),"approvedLaunch").isEqualTo(approved,"expected");
        Properties values=new Properties();LinuxBorrowedStreams.writeTo(values,binding);
        requireThat(values.getProperty("borrowed.capability"),"copiedCapability").isEqualTo(Base64.getEncoder().encodeToString(new byte[32]),"expected");
    }
    /** Every execution field is checked, while environment iteration order does not grant a different command. */
    @Test public void rejectsChangesToExecutionIdentityAndPaths()throws Exception {
        var approved=launch(List.of("/compiler"),Map.of("A","1","B","2"));var binding=binding(1,new byte[32],approved);
        Path a=approved.directory();Path different=Path.of("/changed");
        List<LinuxProcessTree.Launch> changes=List.of(
            new LinuxProcessTree.Launch(a,a,different,a,approved.command(),approved.environment(),a,1000,1000,approved.executionMode()),
            new LinuxProcessTree.Launch(a,a,a,different,approved.command(),approved.environment(),a,1000,1000,approved.executionMode()),
            new LinuxProcessTree.Launch(a,a,a,a,approved.command(),approved.environment(),different,1000,1000,approved.executionMode()),
            new LinuxProcessTree.Launch(a,a,a,a,approved.command(),approved.environment(),a,1001,1000,approved.executionMode()),
            new LinuxProcessTree.Launch(a,a,a,a,approved.command(),approved.environment(),a,1000,1001,approved.executionMode()),
            new LinuxProcessTree.Launch(a,a,a,a,approved.command(),approved.environment(),a,1000,1000,LinuxProcessTree.ExecutionMode.DIRECT_EXEC));
        for(var change:changes)requireException(IOException.class,()->binding.requireExecution(change));
        Map<String,String> reordered=new LinkedHashMap<>();reordered.put("B","2");reordered.put("A","1");
        binding.requireExecution(launch(approved.command(),reordered));
    }
    /** Protected configuration preserves empty arguments and environment values at the actual receive boundary. */
    @Test public void configurationRoundTripPreservesApprovedLaunchAndRejectsMissingFields()throws Exception {
        var approved=launch(List.of("/compiler","","quote\"λ"),Map.of("EMPTY","","NL","value\n"));
        Properties values=new Properties();LinuxBorrowedStreams.writeTo(values,binding(2,new byte[32],approved));
        var output=new ByteArrayOutputStream();values.storeToXML(output,"fixture");
        Properties restored=new Properties();restored.loadFromXML(new ByteArrayInputStream(output.toByteArray()));
        var binding=LinuxBorrowedStreams.readFrom(restored);binding.requireExecution(approved);
        requireThat(binding.approvedLaunch(),"restoredLaunch").isEqualTo(approved,"expected");
        restored.remove("borrowed.arg.1");requireException(IOException.class,()->LinuxBorrowedStreams.readFrom(restored));
        Properties partial=new Properties();partial.setProperty("borrowed.sequence","1");
        requireException(IOException.class,()->LinuxBorrowedStreams.readFrom(partial));
        requireThat(LinuxBorrowedStreams.readFrom(new Properties()),"absentBinding").isNull();
    }
    /** Peer credentials alone cannot authorize a different action, retry sequence or capability. */
    @Test public void authenticatesActionSequenceAndCapabilityInAdditionToRootPeer()throws Exception {
        var approved=launch(List.of("/compiler"),Map.of());var binding=binding(1,new byte[32],approved);
        var root=new LinuxBorrowedStreams.Peer(1,1,0,0);
        binding.requireGate(root,binding.frame());
        requireException(IOException.class,()->binding.requireGate(new LinuxBorrowedStreams.Peer(1,1,1000,1000),binding.frame()));
        for(int offset:List.of(0,4,20,28)) {
            byte[] changed=binding.frame();changed[offset]^=1;
            requireException(IOException.class,()->binding.requireGate(root,changed));
        }
        requireException(IOException.class,()->binding.requireGate(root,new byte[0]));
    }
    /** A listener requires installed registration and consumes a failed transfer before accepting a later retry. */
    @Test public void listenerRequiresExplicitAuthorizationAndRejectsReplay()throws Exception {
        var peer=LinuxBorrowedStreams.Peer.current();var action=new UUID(1,1);var endpoint=Path.of("/owned/streams.sock");
        int[] transfers={0};
        var transport=new LinuxBorrowedStreams.Transfer() {
            public void sendBorrowed(Duration timeout,LinuxBorrowedStreams.Binding binding)throws IOException{transfers[0]++;throw new IOException("fixture disconnect");}
            public void close(){}
        };
        try(var listener=new LinuxBorrowedStreams.Listener(transport,peer,action,endpoint,terminal())) {
            requireException(IllegalStateException.class,()->listener.sendBorrowed(Duration.ofSeconds(1)));
            var approved=launch(List.of("/compiler"),Map.of());
            var first=new LinuxBorrowedStreams.Binding(action,1,peer,endpoint,new byte[32],terminal(),approved);
            listener.authorize(first);
            requireException(IllegalStateException.class,()->listener.authorize(first));
            requireException(IOException.class,()->listener.sendBorrowed(Duration.ofSeconds(1)));
            requireException(IllegalStateException.class,()->listener.sendBorrowed(Duration.ofSeconds(1)));
            requireException(IOException.class,()->listener.authorize(first));
            var next=new LinuxBorrowedStreams.Binding(action,2,peer,endpoint,new byte[32],terminal(),approved);
            listener.authorize(next);
            requireException(IOException.class,()->listener.sendBorrowed(Duration.ofSeconds(1)));
        }
        requireThat(transfers[0],"consumedAuthorizedTransfers").isEqualTo(2,"expected");
    }
    /** Launch mismatch is rejected by tree preparation before root authority or native acquisition. */
    @Test public void treeRejectsMismatchedBindingBeforeAcquisition()throws Exception {
        var approved=launch(List.of("/compiler"),Map.of());var binding=binding(1,new byte[32],approved);
        requireException(IOException.class,()->LinuxProcessTree.prepareBorrowed(launch(List.of("/other"),Map.of()),binding));
    }

    /** Already-signalled cancellation consumes authorization without invoking the native transport. */
    @Test public void interruptionPreventsTransferAndRetainsInterruptFlag() throws Exception {
        var peer=LinuxBorrowedStreams.Peer.current();var action=new UUID(1,1);var endpoint=Path.of("/owned/streams.sock");
        int[] transfers={0};
        var transport=new LinuxBorrowedStreams.Transfer() {
            public void sendBorrowed(Duration timeout,LinuxBorrowedStreams.Binding binding){transfers[0]++;}
            public void close(){}
        };
        try(var listener=new LinuxBorrowedStreams.Listener(transport,peer,action,endpoint,terminal())) {
            listener.authorize(new LinuxBorrowedStreams.Binding(action,1,peer,endpoint,new byte[32],terminal(),launch(List.of("/compiler"),Map.of())));
            try {
                Thread.currentThread().interrupt();
                requireException(InterruptedIOException.class,()->listener.sendBorrowed(Duration.ofSeconds(1)));
                requireThat(Thread.currentThread().isInterrupted(),"interruptFlag").isTrue();
            } finally { Thread.interrupted(); }
            requireException(IllegalStateException.class,()->listener.sendBorrowed(Duration.ofSeconds(1)));
        }
        requireThat(transfers[0],"nativeTransfers").isEqualTo(0,"expected");
    }
}
