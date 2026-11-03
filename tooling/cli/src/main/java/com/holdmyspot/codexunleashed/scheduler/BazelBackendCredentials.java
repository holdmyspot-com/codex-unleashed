package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Carries invocation-private PEM paths supplied by the trusted producer.
 * @param certificateAuthority PEM CA certificate used for this invocation only
 * @param serverCertificate PEM server certificate valid for localhost
 * @param serverKey PKCS8 PEM server private key
 * @param workerCertificate PEM authenticated worker/client certificate
 * @param workerKey PKCS8 PEM worker private key
 */
public record BazelBackendCredentials(Path certificateAuthority, Path serverCertificate, Path serverKey,
    Path workerCertificate, Path workerKey)
{
    /**
     * Retains absolute normalized host-owned credential paths.
     * @param certificateAuthority PEM invocation CA
     * @param serverCertificate PEM localhost server certificate
     * @param serverKey PEM server key
     * @param workerCertificate PEM client certificate
     * @param workerKey PEM client key
     */
    public BazelBackendCredentials
    {
        certificateAuthority = certificateAuthority.toAbsolutePath().normalize();
        serverCertificate = serverCertificate.toAbsolutePath().normalize();
        serverKey = serverKey.toAbsolutePath().normalize();
        workerCertificate = workerCertificate.toAbsolutePath().normalize();
        workerKey = workerKey.toAbsolutePath().normalize();
    }

    /**
     * Issues an invocation CA and localhost server/trusted-worker leaf certificates with JDK keytool.
     * The caller owns the new directory and must remove it after every server and native process closes.
     * Certificate validity is one day; each invocation receives a new CA and private keys.
     * @param credentialRoot new directory under an already private invocation root
     * @param keytool absolute trusted JDK keytool executable
     * @param protection mandatory native authority for all credential objects
     * @return issued PEM credentials; signing stores remain private beneath the owned directory
     * @throws IOException if issuance or protected-file creation fails
     */
    public static BazelBackendCredentials generate(Path credentialRoot, Path keytool, Protection protection) throws IOException
    {
        Path executable = keytool.toAbsolutePath().normalize();
        return generate(credentialRoot, (directory, arguments, log) -> runKeytool(executable, directory, arguments, log),
            protection);
    }

    /**
     * Runs one keytool operation in the private credential directory.
     */
    @FunctionalInterface
    public interface Keytool
    {
        /**
         * Runs keytool with the exact arguments, appending its combined output to the log.
         * @param directory private credential working directory
         * @param arguments exact keytool argument elements, excluding the executable
         * @param log existing protected file that receives keytool's output
         * @return keytool's exit status
         * @throws IOException if keytool cannot run
         * @throws InterruptedException if waiting for keytool is interrupted after it has been reaped
         */
        int run(Path directory, List<String> arguments, Path log) throws IOException, InterruptedException;
    }

    /**
     * Issues credentials through the supplied keytool operation; see {@link #generate(Path, Path, Protection)}.
     * @param credentialRoot new directory under an already private invocation root
     * @param keytool the keytool operation
     * @param protection mandatory native authority for all credential objects
     * @return issued PEM credentials
     * @throws IOException if issuance or protected-file creation fails
     */
    public static BazelBackendCredentials generate(Path credentialRoot, Keytool keytool, Protection protection)
        throws IOException
    {
        Path root = credentialRoot.toAbsolutePath().normalize();
        Objects.requireNonNull(keytool, "keytool");
        Objects.requireNonNull(protection, "protection");
        protection.requireDirectory(root.getParent());
        protection.createDirectory(root);
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        Path password = root.resolve("store-password");
        char[] chars = HexFormat.of().formatHex(secret).toCharArray();
        try
        {
            protection.createFile(password);
            Files.writeString(password, new String(chars), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            issue(keytool, root, protection, "-genkeypair", "-alias", "ca", "-dname", "CN=Invocation CA",
                "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "1", "-ext", "BC=ca:true",
                "-keystore", root.resolve("ca.p12").toString(), "-storetype", "PKCS12", "-storepass:file", password.toString());
            issue(keytool, root, protection, "-exportcert", "-rfc", "-alias", "ca", "-keystore", root.resolve("ca.p12").toString(),
                "-storepass:file", password.toString(), "-file", root.resolve("ca.pem").toString());
            for (String name : List.of("server", "worker")) issueLeaf(keytool, root, protection, name, password, chars);
            try (java.util.stream.Stream<Path> paths = Files.list(root))
            {
                for (Path path : paths.toList()) protection.protectFile(path);
            }
            return new BazelBackendCredentials(root.resolve("ca.pem"), root.resolve("server-cert.pem"), root.resolve("server-key.pem"),
                root.resolve("worker-cert.pem"), root.resolve("worker-key.pem"));
        }
        catch (GeneralSecurityException failure)
        {
            IOException reported = new IOException("JDK could not read the invocation certificate keystore", failure);
            try
            {
                protection.requireDirectory(root);
                org.apache.commons.io.file.PathUtils.deleteDirectory(root);
            }
            catch (IOException cleanupFailure) { reported.addSuppressed(cleanupFailure); }
            throw reported;
        }
        catch (IOException | RuntimeException | Error failure)
        {
            try
            {
                protection.requireDirectory(root);
                org.apache.commons.io.file.PathUtils.deleteDirectory(root);
            }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
        finally { java.util.Arrays.fill(chars, '\0'); java.util.Arrays.fill(secret, (byte) 0); }
    }

    /**
     * Issues one CA-signed leaf and exports its PKCS8 key without exposing a password in argv.
     * @param keytool the keytool operation
     * @param root private credential directory containing the CA store
     * @param protection mandatory native authority
     * @param name server or worker leaf role
     * @param password protected keystore password file
     * @param chars in-memory keystore password
     * @throws IOException if issuance or PEM writing fails
     * @throws GeneralSecurityException if the generated keystore cannot be read
     */
    private static void issueLeaf(Keytool keytool, Path root, Protection protection, String name, Path password, char[] chars)
        throws IOException, GeneralSecurityException
    {
        Path store = root.resolve(name + ".p12");
        issue(keytool, root, protection, "-genkeypair", "-alias", name, "-dname", "CN=" + name,
            "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "1", "-keystore", store.toString(),
            "-storetype", "PKCS12", "-storepass:file", password.toString());
        Path request = root.resolve(name + ".csr");
        issue(keytool, root, protection, "-certreq", "-alias", name, "-keystore", store.toString(),
            "-storepass:file", password.toString(), "-file", request.toString());
        String extendedUsage = name.equals("server") ? "serverAuth" : "clientAuth";
        issue(keytool, root, protection, "-gencert", "-rfc", "-alias", "ca", "-keystore", root.resolve("ca.p12").toString(),
            "-storepass:file", password.toString(), "-infile", request.toString(), "-outfile",
            root.resolve(name + "-cert.pem").toString(), "-validity", "1", "-ext", "KU=digitalSignature",
            "-ext", "EKU=" + extendedUsage, "-ext", "SAN=DNS:localhost,IP:127.0.0.1");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(store)) { keys.load(input, chars); }
        byte[] encoded = keys.getKey(name, chars).getEncoded();
        String pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded) + "\n-----END PRIVATE KEY-----\n";
        try
        {
            Path key = root.resolve(name + "-key.pem");
            protection.createFile(key);
            Files.writeString(key, pem, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
        finally { java.util.Arrays.fill(encoded, (byte) 0); }
    }

    /**
     * Runs one keytool operation with owned diagnostics and checks its exit status.
     * @param keytool the keytool operation
     * @param root private credential working directory
     * @param protection mandatory native authority
     * @param arguments exact keytool argument elements
     * @throws IOException if keytool cannot run or reports a failure
     */
    private static void issue(Keytool keytool, Path root, Protection protection, String... arguments) throws IOException
    {
        protection.requireDirectory(root);
        Path log = root.resolve("keytool.log");
        if (Files.exists(log, LinkOption.NOFOLLOW_LINKS)) protection.requireFile(log);
        else protection.createFile(log);
        int result;
        try { result = keytool.run(root, List.of(arguments), log); }
        catch (InterruptedException failure)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during invocation certificate issuance; keytool was reaped", failure);
        }
        protection.requireDirectory(root);
        if (result != 0) throw new IOException("JDK keytool failed with status " + result + ": " + Files.readString(log));
    }

    /**
     * Runs the JDK keytool executable and waits for its original exit, reaping it if interrupted.
     * @param keytool absolute trusted JDK keytool executable
     * @param root private credential working directory
     * @param arguments exact keytool argument elements
     * @param log existing protected file that receives keytool's output
     * @return keytool's exit status
     * @throws IOException if keytool cannot start
     * @throws InterruptedException if waiting was interrupted; keytool has been destroyed and reaped
     */
    private static int runKeytool(Path keytool, Path root, List<String> arguments, Path log)
        throws IOException, InterruptedException
    {
        List<String> command = new ArrayList<>();
        command.add(keytool.toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).
            redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        Process process = builder.start();
        boolean interrupted = false;
        int result;
        while (true)
        {
            try { result = process.waitFor(); break; }
            catch (InterruptedException failure) { interrupted = true; process.destroy(); }
        }
        if (interrupted) throw new InterruptedException("keytool was reaped after an interrupt");
        return result;
    }

    /**
     * Returns every credential file for no-follow permission validation.
     * @return immutable credential paths
     */
    public List<Path> paths() { return List.of(certificateAuthority, serverCertificate, serverKey, workerCertificate, workerKey); }
    /**
     * Supplies mandatory native creation and validation for one invocation's private files.
     * Implementations reject links, substituted known objects and paths outside their owned root.
     * These permissions exclude other identities; action isolation must also exclude the owner's identity.
     */
    public interface Protection
    {
        /**
         * Creates a new owner-only directory beneath an existing protected parent.
         * @param path absent directory to create
         * @throws IOException if the parent is unsafe, the path exists, or native creation fails
         */
        void createDirectory(Path path) throws IOException;

        /**
         * Creates a new empty owner-only regular file beneath a protected parent.
         * @param path absent file to create
         * @throws IOException if the parent is unsafe, the path exists, or native creation fails
         */
        void createFile(Path path) throws IOException;

        /**
         * Protects a tool-created regular file inside an already protected directory.
         * @param path existing owned file to protect and retain by identity
         * @throws IOException if its type, owner, identity or parent is unsafe
         */
        void protectFile(Path path) throws IOException;

        /**
         * Validates a protected directory and its retained no-follow identity.
         * @param path existing owned directory
         * @throws IOException if its type, owner, permissions, identity or ancestors are unsafe
         */
        void requireDirectory(Path path) throws IOException;

        /**
         * Validates a protected regular file and its retained no-follow identity.
         * @param path existing owned regular file
         * @throws IOException if its type, owner, permissions, identity or ancestors are unsafe
         */
        void requireFile(Path path) throws IOException;
    }

    /**
     * Captures NIO authority for an existing owner-only root with stable no-follow filesystem keys.
     * All operations remain bounded by this root. Unsupported attributes or missing keys fail without a fallback.
     * Windows owners must supply native file-ID protection when their JDK exposes no stable file keys.
     * @param trustedPrivateRoot existing real private directory owned by the current creating identity
     * @return independently owned native protection; callers retain the root until every consumer closes
     * @throws IOException if the root or its native authority cannot be verified
     */
    public static Protection nativeProtection(Path trustedPrivateRoot) throws IOException
    {
        return new NativeProtection(trustedPrivateRoot, BazelBackendCredentials::nioIdentity);
    }

    /**
     * Supplies bounded private-file protection and independent no-follow distribution identity reads.
     * Identity reads may cover trusted installed products outside the private invocation root;
     * every Protection operation remains bounded by that private root.
     */
    public interface NativeAuthority extends Protection, BazelNativeBackend.FileIdentity
    {
    }

    /**
     * Captures the current host's private-file authority while borrowing the caller's native services.
     * Windows reads full native volume/file IDs; POSIX reads stable no-follow NIO keys.
     * @param scope caller-owned Pouch services retained until all authority operations finish
     * @param trustedPrivateRoot existing real owner-only invocation root
     * @return native identity reader and bounded private-file protection sharing one identity mechanism
     * @throws IOException if the root's owner, permissions, type or identity cannot be authenticated
     * @throws UnsupportedOperationException if the Windows runtime ABI or native functions are unavailable
     */
    public static NativeAuthority nativeAuthority(ToolingScope scope, Path trustedPrivateRoot) throws IOException
    {
        return new NativeProtection(trustedPrivateRoot, nativeIdentity(scope));
    }

    /**
     * Selects a no-follow identity reader for existing trusted files and directories on the current host.
     * No private-root permission policy is applied by this reader. Each native Windows handle is closed
     * before the read returns; the caller retains the Pouch scope until all reads finish.
     * @param scope caller-owned Pouch services supplying the native linker when Windows requires it
     * @return immutable equality-comparable identity reader for the current host
     * @throws UnsupportedOperationException if the Windows runtime ABI or native functions are unavailable
     */
    public static BazelNativeBackend.FileIdentity nativeIdentity(ToolingScope scope)
    {
        Objects.requireNonNull(scope, "scope");
        if (System.getProperty("os.name").startsWith("Windows"))
            return new WindowsIdentity(scope.getNativeLinker());
        return BazelBackendCredentials::nioIdentity;
    }

    /**
     * Reads a stable NIO filesystem key while rejecting links and special files.
     * @param path existing trusted regular file or directory
     * @return non-null immutable filesystem key
     * @throws IOException if the object is indirect, special or lacks a stable native key
     */
    private static Object nioIdentity(Path path) throws IOException
    {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        boolean ordinary = attrs.isDirectory() || attrs.isRegularFile();
        if (!ordinary || attrs.isSymbolicLink() || attrs.isOther() || attrs.fileKey() == null)
            throw new IOException("Object is indirect, special or lacks a stable NIO identity: " + path);
        return attrs.fileKey();
    }

    /**
     * Enforces filesystem-native permissions and remembers the identity of each admitted object.
     * Operations are serialized within one invocation; independent invocations share no mutable state.
     */
    private static final class NativeProtection implements NativeAuthority
    {
        /** Owner-only directory traversal and mutation permissions. */
        private static final Set<PosixFilePermission> DIRECTORY_MODE =
            Set.copyOf(PosixFilePermissions.fromString("rwx------"));
        /** Owner-only regular-file read and write permissions. */
        private static final Set<PosixFilePermission> FILE_MODE =
            Set.copyOf(PosixFilePermissions.fromString("rw-------"));
        /** Invocation-private boundary for every admitted object. */
        private final Path root;
        /** Native owner verified against a file created by this process. */
        private final UserPrincipal owner;
        /** Indicates whether this filesystem uses POSIX modes rather than ACLs. */
        private final boolean posix;
        /** Native identity reader shared by arbitrary distribution reads and bounded private authority. */
        private final BazelNativeBackend.FileIdentity identityReader;
        /** No-follow filesystem keys retained for every successfully admitted path. */
        private final Map<Path, Object> identities = new HashMap<>();

        /**
         * Validates the initial private root and verifies its owner using a fresh empty native file.
         * @param root existing owned private root
         * @param identityReader current-host no-follow native identity reader
         * @throws IOException if native authority or the root's identity is unsafe
         */
        private NativeProtection(Path root, BazelNativeBackend.FileIdentity identityReader) throws IOException
        {
            this.identityReader = Objects.requireNonNull(identityReader, "identityReader");
            this.root = root.toAbsolutePath().normalize();
            this.posix = Files.getFileAttributeView(this.root, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS) != null;
            if (!posix && Files.getFileAttributeView(this.root, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS) == null)
                throw new IOException("Filesystem exposes neither POSIX permissions nor ACL authority: " + this.root);
            this.owner = Files.getOwner(this.root, LinkOption.NOFOLLOW_LINKS);
            validate(this.root, true, true);
            Path probe = Files.createTempFile(this.root, ".owner-", ".probe", attributes(false));
            try
            {
                if (!owner.equals(Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("Private root belongs to another creating identity: " + this.root);
            }
            finally { Files.delete(probe); }
            validate(this.root, true, true);
        }

        @Override
        public Object identity(Path path) throws IOException
        {
            return identityReader.identity(path);
        }

        @Override
        public synchronized void createDirectory(Path path) throws IOException
        {
            Path target = bounded(path);
            requireDirectory(target.getParent());
            Files.createDirectory(target, attributes(true));
            requireDirectory(target);
        }

        @Override
        public synchronized void createFile(Path path) throws IOException
        {
            Path target = bounded(path);
            requireDirectory(target.getParent());
            Files.createFile(target, attributes(false));
            requireFile(target);
        }

        @Override
        public synchronized void protectFile(Path path) throws IOException
        {
            Path target = bounded(path);
            ancestors(target);
            validate(target, false, false);
            if (posix) Files.setPosixFilePermissions(target, FILE_MODE);
            else Files.getFileAttributeView(target, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS).setAcl(acl(false));
            validate(target, false, true);
        }

        @Override
        public synchronized void requireDirectory(Path path) throws IOException
        {
            Path target = bounded(path);
            ancestors(target);
            validate(target, true, true);
        }

        @Override
        public synchronized void requireFile(Path path) throws IOException
        {
            Path target = bounded(path);
            ancestors(target);
            validate(target, false, true);
        }

        /**
         * Resolves an absolute native path while rejecting escape from this invocation.
         * @param path native object path
         * @return normalized absolute path inside or equal to the private root
         * @throws IOException if the path escapes the private root
         */
        private Path bounded(Path path) throws IOException
        {
            Path result = path.toAbsolutePath().normalize();
            if (!result.startsWith(root)) throw new IOException("Path escapes private root: " + result);
            return result;
        }

        /**
         * Validates the retained root and each intermediate directory without following links.
         * @param path bounded object whose ancestors are checked
         * @throws IOException if an ancestor's authority or identity changed
         */
        private void ancestors(Path path) throws IOException
        {
            validate(root, true, true);
            Path parent = path.getParent();
            if (path.equals(root)) return;
            Path current = root;
            for (Path component : root.relativize(parent))
            {
                if (component.toString().isEmpty()) continue;
                current = current.resolve(component);
                validate(current, true, true);
            }
        }

        /**
         * Checks native type, owner, permissions and retained filesystem identity.
         * @param path object being admitted or checked
         * @param directory indicates whether a directory rather than a regular file is required
         * @param permissions indicates whether owner-only permissions are already required
         * @throws IOException if any required property is absent or changed
         */
        private void validate(Path path, boolean directory, boolean permissions) throws IOException
        {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            boolean validType = directory ? attrs.isDirectory() : attrs.isRegularFile();
            if (!validType || attrs.isSymbolicLink() || attrs.isOther())
                throw new IOException("Private object is a link, special file or lacks native identity: " + path);
            Object identity = identityReader.identity(path);
            Object previous = identities.putIfAbsent(path, identity);
            if (previous != null && !previous.equals(identity))
                throw new IOException("Private object has been substituted: " + path);
            if (!owner.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("Private object belongs to another identity: " + path);
            if (permissions)
            {
                if (posix)
                {
                    Set<PosixFilePermission> expected = directory ? DIRECTORY_MODE : FILE_MODE;
                    if (!expected.equals(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)))
                        throw new IOException("Private object does not have required owner-only permissions: " + path);
                }
                else
                {
                    List<AclEntry> entries = Files.getFileAttributeView(path, AclFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).getAcl();
                    if (entries.isEmpty()) throw new IOException("Private object has an empty ACL: " + path);
                    for (AclEntry entry : entries)
                        if (entry.type() != AclEntryType.ALLOW || !owner.equals(entry.principal()))
                            throw new IOException("Private ACL admits another identity or unsupported entry: " + path);
                }
            }
        }

        /**
         * Supplies atomic owner-only creation attributes in the filesystem's supported native form.
         * @param directory indicates whether directory inheritance must protect tool-created children
         * @return initial native creation attributes
         */
        private FileAttribute<?>[] attributes(boolean directory)
        {
            if (posix) return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(
                directory ? DIRECTORY_MODE : FILE_MODE)};
            List<AclEntry> entries = acl(directory);
            FileAttribute<List<AclEntry>> attribute = new FileAttribute<>()
            {
                @Override public String name() { return "acl:acl"; }
                @Override public List<AclEntry> value() { return entries; }
            };
            return new FileAttribute<?>[]{attribute};
        }

        /**
         * Builds owner-only ACL authority with inheritance for private directory descendants.
         * @param directory indicates whether children inherit owner-only access
         * @return immutable owner-only ACL
         */
        private List<AclEntry> acl(boolean directory)
        {
            AclEntry.Builder builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            return List.of(builder.build());
        }
    }

    /**
     * Preserves the complete Windows FILE_ID_INFO identity without narrowing the native file ID.
     * @param volume native 64-bit volume serial number
     * @param first first eight bytes of the opaque 128-bit file ID
     * @param second remaining eight bytes of the opaque 128-bit file ID
     */
    private record WindowsFileId(long volume, long first, long second)
    {
    }

    /**
     * Reads native Windows identity through original no-follow disk-file handles.
     * Bindings borrow the caller's linker; argument arenas and file handles belong to each read.
     */
    private static final class WindowsIdentity implements BazelNativeBackend.FileIdentity
    {
        /** Captured GetLastError state supplied as the first downcall argument. */
        private static final MemoryLayout STATE = Linker.Option.captureStateLayout();
        /** Captured GetLastError DWORD offset, read before cleanup can overwrite it. */
        private static final long ERROR_OFFSET = STATE.byteOffset(
            MemoryLayout.PathElement.groupElement("GetLastError"));
        /** FILE_ATTRIBUTE_TAG_INFO consists of FileAttributes and ReparseTag DWORDs. */
        private static final MemoryLayout ATTRIBUTE_TAG = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("attributes"), ValueLayout.JAVA_INT.withName("tag"));
        /** FILE_ID_INFO consists of ULONGLONG volume and 16 opaque file-ID bytes. */
        private static final MemoryLayout FILE_ID = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("volume"), MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_BYTE)
                .withName("id"));
        /** FILE_INFO_BY_HANDLE_CLASS value selecting FILE_ATTRIBUTE_TAG_INFO. */
        private static final int FILE_ATTRIBUTE_TAG_INFO = 9;
        /** FILE_INFO_BY_HANDLE_CLASS value selecting the complete FILE_ID_INFO. */
        private static final int FILE_ID_INFO = 18;
        /** CreateFileW metadata-read access right. */
        private static final int FILE_READ_ATTRIBUTES = 0x80;
        /** CreateFileW share-read, share-write and share-delete bits. */
        private static final int SHARE_ALL = 7;
        /** CreateFileW disposition that never creates or truncates an object. */
        private static final int OPEN_EXISTING = 3;
        /** CreateFileW flags opening directory handles and the final reparse point itself. */
        private static final int NO_FOLLOW_DIRECTORY_FLAGS = 0x02000000 | 0x00200000;
        /** GetFileType result for a genuine disk-backed file or directory handle. */
        private static final int FILE_TYPE_DISK = 1;
        /** File-attribute bit marking every kind of reparse point, including junctions and cloud files. */
        private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;
        /** File-attribute bit marking a device rather than an ordinary disk object. */
        private static final int FILE_ATTRIBUTE_DEVICE = 0x40;
        /** Fixed CreateFileW signature with captured native error state. */
        private final MethodHandle open;
        /** Fixed GetFileInformationByHandleEx signature with captured native error state. */
        private final MethodHandle information;
        /** Fixed GetFileType signature with captured native error state. */
        private final MethodHandle type;
        /** Fixed CloseHandle signature with captured native error state. */
        private final MethodHandle close;

        /**
         * Binds documented Win32 LLP64 signatures for the current Windows host.
         * @param linker caller-owned native linker supplied by the Pouch scope
         * @throws UnsupportedOperationException if pointers are not 64-bit or a function is unavailable
         */
        private WindowsIdentity(Linker linker)
        {
            if (ValueLayout.ADDRESS.byteSize() != Long.BYTES)
                throw new UnsupportedOperationException("Windows native file identities require a 64-bit runtime");
            SymbolLookup symbols = symbols();
            this.open = function(linker, symbols, "CreateFileW", FunctionDescriptor.of(ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            this.information = function(linker, symbols, "GetFileInformationByHandleEx",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            this.type = function(linker, symbols, "GetFileType",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            this.close = function(linker, symbols, "CloseHandle",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        }

        /**
         * Borrows the Windows system library for the process lifetime, matching existing timestamp bindings.
         * @return current process's Kernel32 symbols
         */
        // The launcher grants restricted native access; this Windows-only branch uses the OS system library.
        @SuppressWarnings("restricted")
        private static SymbolLookup symbols()
        {
            return SymbolLookup.libraryLookup("Kernel32", Arena.global());
        }

        /**
         * Resolves one fixed documented signature while capturing its original native error state.
         * @param linker caller-owned linker
         * @param symbols existing system-library symbols
         * @param name exact Win32 export name
         * @param descriptor fixed native signature
         * @return captured-state downcall
         * @throws UnsupportedOperationException if the export is unavailable
         */
        // Every call uses a fixed documented ABI and confined argument allocations.
        @SuppressWarnings("restricted")
        private static MethodHandle function(Linker linker, SymbolLookup symbols, String name,
            FunctionDescriptor descriptor)
        {
            return linker.downcallHandle(symbols.find(name).orElseThrow(() ->
                new UnsupportedOperationException("Windows does not provide " + name)), descriptor,
                Linker.Option.captureCallState("GetLastError"));
        }

        @Override
        public Object identity(Path path) throws IOException
        {
            try (Arena arena = Arena.ofConfined(); Handle handle = new Handle(path, arena))
            {
                if (fileType(handle) != FILE_TYPE_DISK)
                    throw new IOException("Native object is not a disk file or directory: " + path);
                MemorySegment attributes = arena.allocate(ATTRIBUTE_TAG);
                readInformation(handle, FILE_ATTRIBUTE_TAG_INFO, attributes);
                int flags = attributes.get(ValueLayout.JAVA_INT, 0);
                if ((flags & (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DEVICE)) != 0)
                    throw new IOException("Native object is a reparse point or device: " + path);
                MemorySegment identity = arena.allocate(FILE_ID);
                readInformation(handle, FILE_ID_INFO, identity);
                return new WindowsFileId(identity.get(ValueLayout.JAVA_LONG, 0),
                    identity.get(ValueLayout.JAVA_LONG, Long.BYTES),
                    identity.get(ValueLayout.JAVA_LONG, 2L * Long.BYTES));
            }
        }

        /**
         * Reads the original handle's native type without replacing its native failure state.
         * @param handle original owned no-follow handle
         * @return Win32 FILE_TYPE value
         * @throws IOException if native invocation cannot complete
         */
        private int fileType(Handle handle) throws IOException
        {
            try { return (int) type.invokeExact(handle.state, handle.value); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IOException("GetFileType invocation failed for " + handle.path, failure); }
        }

        /**
         * Reads metadata from the same original no-follow handle used for type and identity checks.
         * @param handle original owned native handle
         * @param kind documented FILE_INFO_BY_HANDLE_CLASS value
         * @param destination correctly sized native metadata buffer
         * @throws IOException if the native read fails
         */
        private void readInformation(Handle handle, int kind, MemorySegment destination) throws IOException
        {
            int result;
            try
            {
                result = (int) information.invokeExact(handle.state, handle.value, kind, destination,
                    Math.toIntExact(destination.byteSize()));
            }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure)
            {
                throw new IOException("GetFileInformationByHandleEx invocation failed for " + handle.path, failure);
            }
            if (result == 0) throw handle.failure("GetFileInformationByHandleEx(" + kind + ")");
        }

        /**
         * Owns the original CreateFileW handle until CloseHandle completes, within the read's arena.
         */
        private final class Handle implements AutoCloseable
        {
            /** Native path used only for handle opening and failure context. */
            private final Path path;
            /** Captured native error state borrowed from this operation's arena. */
            private final MemorySegment state;
            /** Original handle returned by CreateFileW and collected by this owner. */
            private final MemorySegment value;

            /**
             * Opens an existing file or directory without traversing its final reparse point.
             * @param path existing trusted native object
             * @param arena operation-owned confined native allocation lifetime
             * @throws IOException if native opening fails
             */
            private Handle(Path path, Arena arena) throws IOException
            {
                this.path = path.toAbsolutePath().normalize();
                this.state = arena.allocate(STATE);
                String name = this.path.toString();
                if (!name.startsWith("\\\\?\\"))
                {
                    if (name.startsWith("\\\\")) name = "\\\\?\\UNC\\" + name.substring(2);
                    else name = "\\\\?\\" + name;
                }
                MemorySegment filename = arena.allocateFrom(ValueLayout.JAVA_CHAR, (name + '\0').toCharArray());
                try
                {
                    this.value = (MemorySegment) open.invokeExact(state, filename, FILE_READ_ATTRIBUTES,
                        SHARE_ALL, MemorySegment.NULL, OPEN_EXISTING, NO_FOLLOW_DIRECTORY_FLAGS, MemorySegment.NULL);
                }
                catch (RuntimeException | Error failure) { throw failure; }
                catch (Throwable failure) { throw new IOException("CreateFileW invocation failed for " + path, failure); }
                if (value.address() == -1L) throw failure("CreateFileW");
            }

            /**
             * Captures the current native failure before cleanup can replace GetLastError.
             * @param operation failed native operation
             * @return IOException retaining the exact native error and original path
             */
            private IOException failure(String operation)
            {
                return new IOException(operation + " failed for " + path + ": GetLastError=" +
                    Integer.toUnsignedString(state.get(ValueLayout.JAVA_INT, ERROR_OFFSET)));
            }

            @Override
            public void close() throws IOException
            {
                int result;
                try { result = (int) close.invokeExact(state, value); }
                catch (RuntimeException | Error failure) { throw failure; }
                catch (Throwable failure) { throw new IOException("CloseHandle invocation failed for " + path, failure); }
                if (result == 0) throw failure("CloseHandle");
            }
        }
    }
}
