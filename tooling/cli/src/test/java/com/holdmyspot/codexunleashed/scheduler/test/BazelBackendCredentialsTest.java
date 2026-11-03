package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.BazelBackendCredentials;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.commons.io.file.PathUtils;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises credential issuance and native authority with independently owned method-local fixtures.
 */
public final class BazelBackendCredentialsTest
{
    /**
     * Creates a stateless method-parallel test instance.
     */
    public BazelBackendCredentialsTest()
    {
    }

    /**
     * Issuance asks keytool for a CA and two CA-signed leaves with the documented arguments, passes the store password
     * only through a protected file, exports each leaf's PKCS8 key from its store, and protects every credential file.
     * keytool itself is assumed to work as documented, so a fake stands in for it: it records each argument list and
     * writes, for each operation, the file that keytool documents producing.
     * @throws Exception if issuance or verification fails
     */
    @Test
    public void issuesMatchingSignedCredentials() throws Exception
    {
        try (Fixture fixture = Fixture.open())
        {
            FakeKeytool keytool = new FakeKeytool();
            BazelBackendCredentials credentials = BazelBackendCredentials.generate(
                fixture.root.resolve("credentials"), keytool, fixture.protection);
            Path root = credentials.certificateAuthority().getParent();
            String password = root.resolve("store-password").toString();
            List<String> common = List.of("-keyalg", "EC", "-groupname", "secp256r1", "-validity", "1");
            List<List<String>> expected = new ArrayList<>();
            List<String> ca = new ArrayList<>(List.of("-genkeypair", "-alias", "ca", "-dname", "CN=Invocation CA"));
            ca.addAll(common);
            ca.addAll(List.of("-ext", "BC=ca:true", "-keystore", root.resolve("ca.p12").toString(), "-storetype",
                "PKCS12", "-storepass:file", password));
            expected.add(ca);
            expected.add(List.of("-exportcert", "-rfc", "-alias", "ca", "-keystore", root.resolve("ca.p12").toString(),
                "-storepass:file", password, "-file", root.resolve("ca.pem").toString()));
            for (String name : List.of("server", "worker"))
            {
                Path store = root.resolve(name + ".p12");
                List<String> pair = new ArrayList<>(List.of("-genkeypair", "-alias", name, "-dname", "CN=" + name));
                pair.addAll(common);
                pair.addAll(List.of("-keystore", store.toString(), "-storetype", "PKCS12", "-storepass:file", password));
                expected.add(pair);
                expected.add(List.of("-certreq", "-alias", name, "-keystore", store.toString(), "-storepass:file",
                    password, "-file", root.resolve(name + ".csr").toString()));
                expected.add(List.of("-gencert", "-rfc", "-alias", "ca", "-keystore", root.resolve("ca.p12").toString(),
                    "-storepass:file", password, "-infile", root.resolve(name + ".csr").toString(), "-outfile",
                    root.resolve(name + "-cert.pem").toString(), "-validity", "1", "-ext", "KU=digitalSignature",
                    "-ext", "EKU=" + (name.equals("server") ? "serverAuth" : "clientAuth"), "-ext",
                    "SAN=DNS:localhost,IP:127.0.0.1"));
            }
            requireThat(keytool.calls, "keytoolCalls").isEqualTo(expected);
            requireThat(keytool.directories.stream().allMatch(root::equals), "keytoolWorkingDirectory").isTrue();
            requireThat(Files.readString(root.resolve("store-password")), "storePassword").matches("[0-9a-f]{64}");
            requireThat(Files.readString(credentials.serverKey()), "serverKey")
                .isEqualTo(pem(keytool.keys.get("server")));
            requireThat(Files.readString(credentials.workerKey()), "workerKey")
                .isEqualTo(pem(keytool.keys.get("worker")));
            for (Path path : credentials.paths()) fixture.protection.requireFile(path);
            try (Stream<Path> files = Files.list(root))
            {
                for (Path path : files.toList()) fixture.protection.requireFile(path);
            }
        }
    }

    /**
     * A keytool failure is reported with its log and removes the newly created credential directory.
     * @throws IOException if fixture creation or cleanup fails
     */
    @Test
    public void reportsAndCollectsFailedKeytool() throws IOException
    {
        try (Fixture fixture = Fixture.open())
        {
            Path credentials = fixture.root.resolve("credentials");
            IOException failure = requireException(IOException.class, () -> BazelBackendCredentials.generate(
                credentials, (directory, arguments, log) ->
                {
                    Files.writeString(log, "keytool error: refused");
                    return 1;
                }, fixture.protection));
            requireThat(failure.getMessage(), "message").contains("status 1").contains("keytool error: refused");
            requireThat(Files.exists(credentials, LinkOption.NOFOLLOW_LINKS), "failedCredentialRootRetained").isFalse();
        }
    }

    /**
     * Formats a PKCS8 key the way issuance documents writing it.
     * @param encoded PKCS8 key bytes
     * @return PEM text
     */
    private static String pem(byte[] encoded)
    {
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded) +
            "\n-----END PRIVATE KEY-----\n";
    }

    /**
     * Stands in for keytool: records each call and writes the file that each operation documents producing.
     * Key stores hold an in-process EC key under the requested alias and password; certificate outputs hold a fixed
     * marker, because issuance only passes them along.
     */
    private static final class FakeKeytool implements BazelBackendCredentials.Keytool
    {
        /** Argument lists in call order. */
        final List<List<String>> calls = new ArrayList<>();
        /** Working directories in call order. */
        final List<Path> directories = new ArrayList<>();
        /** PKCS8 encodings of the leaf keys placed in each store, by alias. */
        final Map<String, byte[]> keys = new HashMap<>();

        /**
         * Creates a fake with no recorded calls.
         */
        FakeKeytool()
        {
        }

        @Override
        public int run(Path directory, List<String> arguments, Path log) throws IOException
        {
            calls.add(arguments);
            directories.add(directory);
            try
            {
                switch (arguments.getFirst())
                {
                    case "-genkeypair" -> writeStore(arguments);
                    case "-exportcert" -> Files.writeString(Path.of(value(arguments, "-file")), "certificate");
                    case "-certreq" -> Files.writeString(Path.of(value(arguments, "-file")), "request");
                    case "-gencert" -> Files.writeString(Path.of(value(arguments, "-outfile")), "certificate");
                    default -> { return 2; }
                }
            }
            catch (GeneralSecurityException failure)
            {
                throw new IOException(failure);
            }
            return 0;
        }

        /**
         * Writes a PKCS12 store holding a fresh EC key under the requested alias and file password.
         * @param arguments a {@code -genkeypair} argument list
         * @throws IOException if the store cannot be written
         * @throws GeneralSecurityException if the key or store cannot be created
         */
        private void writeStore(List<String> arguments) throws IOException, GeneralSecurityException
        {
            char[] password = Files.readString(Path.of(value(arguments, "-storepass:file"))).toCharArray();
            String alias = value(arguments, "-alias");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            X509Certificate certificate = certificate(TEST_CERTIFICATE);
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, password);
            // PKCS12 requires a certificate chain beside a private key; issuance reads only the key.
            store.setKeyEntry(alias, pair.getPrivate(), password, new java.security.cert.Certificate[]{certificate});
            try (OutputStream output = Files.newOutputStream(Path.of(value(arguments, "-keystore"))))
            {
                store.store(output, password);
            }
            keys.put(alias, pair.getPrivate().getEncoded());
        }

        /**
         * Returns the element after an option.
         * @param arguments keytool arguments
         * @param option the option name
         * @return its value
         */
        private static String value(List<String> arguments, String option)
        {
            return arguments.get(arguments.indexOf(option) + 1);
        }
    }

    /**
     * A substituted root is rejected even when the replacement has the same owner and permissions.
     * @throws IOException if fixture mutation or cleanup fails
     */
    @Test
    public void rejectsSubstitutedRoot() throws IOException
    {
        try (Fixture fixture = Fixture.open())
        {
            Files.move(fixture.root, fixture.container.resolve("original"));
            privateDirectory(fixture.root);
            requireException(IOException.class, () -> fixture.protection.requireDirectory(fixture.root));
        }
    }

    /**
     * A substituted previously admitted file is rejected before its bytes are consumed.
     * @throws IOException if fixture mutation or cleanup fails
     */
    @Test
    public void rejectsSubstitutedFile() throws IOException
    {
        try (Fixture fixture = Fixture.open())
        {
            Path file = fixture.root.resolve("secret");
            fixture.protection.createFile(file);
            Files.move(file, fixture.root.resolve("original-secret"));
            Files.createFile(file, initialFileAttributes(fixture.root));
            requireException(IOException.class, () -> fixture.protection.requireFile(file));
        }
    }

    /**
     * Native creation rejects traversal outside the admitted root without creating the requested file.
     * @throws IOException if fixture creation or cleanup fails
     */
    @Test
    public void rejectsEscapingPaths() throws IOException
    {
        try (Fixture fixture = Fixture.open())
        {
            Path escaped = fixture.container.resolve("escape");
            requireException(IOException.class, () -> fixture.protection.createFile(escaped));
            requireThat(Files.exists(escaped), "escapedFileCreated").isFalse();
        }
    }

    /**
     * POSIX permission weakening is rejected by the same native authority used for credential reads.
     * @throws IOException if fixture mutation or cleanup fails
     */
    @Test
    public void rejectsBroadenedPosixPermissions() throws IOException
    {
        try (Fixture fixture = Fixture.open())
        {
            if (Files.getFileAttributeView(fixture.root, PosixFileAttributeView.class) == null)
                throw new SkipException("POSIX permissions require a POSIX filesystem");
            Path file = fixture.root.resolve("secret");
            fixture.protection.createFile(file);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
            requireException(IOException.class, () -> fixture.protection.requireFile(file));
        }
    }

    /**
     * A missing trusted keytool fails issuance and removes the newly created credential directory.
     * @throws IOException if fixture creation or cleanup fails
     */
    @Test
    public void collectsFailedIssuance() throws IOException
    {
        try (Fixture fixture = Fixture.open())
        {
            Path credentials = fixture.root.resolve("credentials");
            requireException(IOException.class, () -> BazelBackendCredentials.generate(
                credentials, fixture.root.resolve("absent-keytool"), fixture.protection));
            requireThat(Files.exists(credentials, LinkOption.NOFOLLOW_LINKS), "failedCredentialRootRetained").isFalse();
        }
    }

    /**
     * Existing directories are rejected without removing caller-owned content.
     * @throws IOException if fixture creation or cleanup fails
     */
    @Test
    public void preservesExistingRoot() throws IOException
    {
        try (Fixture fixture = Fixture.open())
        {
            Path credentials = fixture.root.resolve("credentials");
            fixture.protection.createDirectory(credentials);
            Path retained = credentials.resolve("retained");
            fixture.protection.createFile(retained);
            Files.writeString(retained, "caller-owned");
            requireException(IOException.class, () -> BazelBackendCredentials.generate(
                credentials, (directory, arguments, log) ->
                {
                    throw new AssertionError("keytool must not run for an existing root");
                }, fixture.protection));
            requireThat(Files.readString(retained), "callerOwnedBytes").isEqualTo("caller-owned");
        }
    }

    /**
     * Native identity reads cover trusted files outside the private root while Protection rejects mutation there.
     * @throws IOException if native identity reading, fixture mutation or cleanup fails
     */
    @Test
    public void readsDistributionIdentityOutsidePrivateRoot() throws IOException
    {
        try (Fixture fixture = Fixture.open())
        {
            Path outside = fixture.container.resolve("distribution-file");
            Files.writeString(outside, "installed product");
            Object original = fixture.protection.identity(outside);
            requireThat(BazelBackendCredentials.nativeIdentity(fixture.scope).identity(outside), "sameNativeIdentity")
                .isEqualTo(original);
            requireException(IOException.class, () -> fixture.protection.requireFile(outside));
            Files.move(outside, fixture.container.resolve("original-product"));
            Files.writeString(outside, "installed product");
            requireThat(fixture.protection.identity(outside).equals(original), "replacementIdentityPreserved").isFalse();
        }
    }

    /** A fixed certificate that fake key stores carry beside their key. */
    private static final String TEST_CERTIFICATE = "bazel-credentials/ca.pem";

    /**
     * Reads a PEM certificate test resource.
     * @param name resource name relative to this class
     * @return parsed X.509 certificate
     * @throws IOException if the resource is missing
     * @throws java.security.cert.CertificateException if parsing fails
     */
    private static X509Certificate certificate(String name) throws IOException, java.security.cert.CertificateException
    {
        try (InputStream input = BazelBackendCredentialsTest.class.getResourceAsStream(name))
        {
            if (input == null) throw new IOException("Missing test resource: " + name);
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    /**
     * Creates a test-owned private directory using the host filesystem's native permission model.
     * @param path absent test directory
     * @throws IOException if creation or permission assignment fails
     */
    private static void privateDirectory(Path path) throws IOException
    {
        Files.createDirectory(path);
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        else
        {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build()));
        }
    }

    /**
     * Selects initial private-file attributes; ACL fixtures inherit their private directory's owner ACL.
     * @param root protected parent directory
     * @return native initial file attributes
     */
    private static java.nio.file.attribute.FileAttribute<?>[] initialFileAttributes(Path root)
    {
        if (Files.getFileAttributeView(root, PosixFileAttributeView.class) == null)
            return new java.nio.file.attribute.FileAttribute<?>[0];
        return new java.nio.file.attribute.FileAttribute<?>[]{
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))};
    }

    /**
     * Owns the complete temporary tree of one test, including roots renamed by substitution checks.
     */
    private static final class Fixture implements AutoCloseable
    {
        /** Sole cleanup root allocated by this fixture. */
        private final Path container;
        /** Existing private root admitted by the production protection factory. */
        private final Path root;
        /** Native authority scoped to this test's private root. */
        private final BazelBackendCredentials.NativeAuthority protection;
        /** Caller-owned Pouch services retained until native authority operations finish. */
        private final DefaultToolingScope scope;

        /**
         * Retains already-created fixture state.
         * @param container sole cleanup root
         * @param root private native authority boundary
         * @param protection production authority bound to that root
         * @param scope borrowed native services owned by this fixture
         */
        private Fixture(Path container, Path root, BazelBackendCredentials.NativeAuthority protection,
            DefaultToolingScope scope)
        {
            this.container = container;
            this.root = root;
            this.protection = protection;
            this.scope = scope;
        }

        /**
         * Creates independent fixture state and collects it if setup fails.
         * @return owned fixture ready for one test
         * @throws IOException if creation or authority admission fails
         */
        private static Fixture open() throws IOException
        {
            Path container = Files.createTempDirectory("bazel-credentials-");
            try
            {
                DefaultToolingScope scope = new DefaultToolingScope();
                try
                {
                    Path root = container.resolve("root");
                    privateDirectory(root);
                    return new Fixture(container, root, BazelBackendCredentials.nativeAuthority(scope, root), scope);
                }
                catch (IOException | RuntimeException | Error failure)
                {
                    try { scope.close(); }
                    catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
            catch (IOException | RuntimeException | Error failure)
            {
                try { PathUtils.deleteDirectory(container); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        @Override
        public void close() throws IOException
        {
            try { PathUtils.deleteDirectory(container); }
            finally { scope.close(); }
        }
    }
}
