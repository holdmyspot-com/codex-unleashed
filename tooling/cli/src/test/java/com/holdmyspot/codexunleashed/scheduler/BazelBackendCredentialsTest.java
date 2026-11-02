package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.io.InputStream;
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
import java.security.KeyFactory;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import org.apache.commons.io.file.PathUtils;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
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
     * Issuing credentials creates distinct CA-signed server/client identities with matching private keys.
     * This test stays above one second because the claim needs the real JDK keytool: issuance runs eight keytool JVMs in
     * sequence (CA creation and export, then key, request and signature for each of the server and worker), at about
     * half a second each, and every assertion below reads their output. A faster stand-in would not prove that keytool
     * output is signed by the CA and matches the exported keys, and parallel leaf issuance would change production code.
     * @throws Exception if issuance or native certificate verification fails
     */
    @Test
    public void issuesMatchingSignedCredentials() throws Exception
    {
        try (Fixture fixture = Fixture.open())
        {
            BazelBackendCredentials credentials = BazelBackendCredentials.generate(
                fixture.root.resolve("credentials"), keytool(), fixture.protection);
            X509Certificate ca = certificate(credentials.certificateAuthority());
            X509Certificate server = certificate(credentials.serverCertificate());
            X509Certificate worker = certificate(credentials.workerCertificate());
            server.verify(ca.getPublicKey());
            worker.verify(ca.getPublicKey());
            server.checkValidity();
            worker.checkValidity();
            requireThat(server.getExtendedKeyUsage(), "serverUsage").isEqualTo(List.of("1.3.6.1.5.5.7.3.1"));
            requireThat(worker.getExtendedKeyUsage(), "workerUsage").isEqualTo(List.of("1.3.6.1.5.5.7.3.2"));
            requireThat(server.getPublicKey().equals(worker.getPublicKey()), "sameLeafIdentity").isFalse();
            verifyKey(credentials.serverKey(), server);
            verifyKey(credentials.workerKey(), worker);
            for (Path path : credentials.paths()) fixture.protection.requireFile(path);
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
                credentials, keytool(), fixture.protection));
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

    /**
     * Reads one generated PEM certificate through the JDK's X.509 consumer.
     * @param path issued certificate
     * @return parsed X.509 certificate
     * @throws Exception if certificate parsing fails
     */
    private static X509Certificate certificate(Path path) throws Exception
    {
        try (InputStream input = Files.newInputStream(path))
        {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    /**
     * Signs with the exported private key and verifies with the corresponding certificate.
     * @param path issued PKCS8 PEM private key
     * @param certificate corresponding leaf certificate
     * @throws Exception if decoding, signing or verification fails
     */
    private static void verifyKey(Path path, X509Certificate certificate) throws Exception
    {
        String pem = Files.readString(path).replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(KeyFactory.getInstance("EC").generatePrivate(
            new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem))));
        byte[] message = new byte[]{1, 2, 3};
        signer.update(message);
        byte[] signature = signer.sign();
        signer.initVerify(certificate);
        signer.update(message);
        requireThat(signer.verify(signature), "exportedKeyMatchesCertificate").isTrue();
    }

    /**
     * Selects the current test runtime's trusted certificate tool using native executable filenames.
     * @return the current JDK's keytool path
     */
    private static Path keytool()
    {
        Path bin = Path.of(System.getProperty("java.home")).resolve("bin");
        Path executable = bin.resolve("keytool");
        return Files.isRegularFile(executable) ? executable : bin.resolve("keytool.exe");
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
