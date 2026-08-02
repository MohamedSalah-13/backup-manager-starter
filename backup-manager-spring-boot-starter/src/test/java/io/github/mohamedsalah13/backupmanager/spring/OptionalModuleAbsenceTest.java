package io.github.mohamedsalah13.backupmanager.spring;

import io.github.mohamedsalah13.backupmanager.core.BackupManager;
import io.github.mohamedsalah13.backupmanager.core.model.BackupRecord;
import io.github.mohamedsalah13.backupmanager.core.model.BackupStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The optional modules are genuinely optional.
 *
 * <p>An earlier version of {@link BackupJobFactory} referenced {@code MySqlConfig} and the
 * cloud provider classes directly in its method bodies. Spring introspects every bean class
 * with {@code getDeclaredMethods()}, the JVM resolves referenced types while doing so, and
 * the whole application context then failed to start with a {@code NoClassDefFoundError}
 * whenever a module was absent — even for an application backing up nothing but files. The
 * guard inside the method never got the chance to run.
 *
 * <p>{@link FilteredClassLoader} hides the modules the way a real consumer's classpath
 * would, which is the only way to catch this: with every module on the test classpath the
 * bug is invisible.
 */
class OptionalModuleAbsenceTest {

    @TempDir
    Path temp;

    private ApplicationContextRunner runner;
    private Path sourceDir;

    @BeforeEach
    void setUp() throws IOException {
        sourceDir = Files.createDirectories(temp.resolve("data"));
        Files.writeString(sourceDir.resolve("report.txt"), "quarterly figures");

        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BackupAutoConfiguration.class))
                .withPropertyValues(
                        "backup.storage-root=" + temp.resolve("state").toString().replace('\\', '/'),
                        "backup.jobs.docs.source.type=files",
                        "backup.jobs.docs.source.paths[0]=" + sourceDir.toString().replace('\\', '/'),
                        "backup.jobs.docs.destinations[0].type=local",
                        "backup.jobs.docs.destinations[0].path="
                                + temp.resolve("backups").toString().replace('\\', '/'));
    }

    /** Packages that disappear when each optional module is left out of a build. */
    private static final String MYSQL_PACKAGE =
            "io.github.mohamedsalah13.backupmanager.db.mysql";
    private static final String FILE_DB_PACKAGE =
            "io.github.mohamedsalah13.backupmanager.db.file";
    private static final String S3_PACKAGE =
            "io.github.mohamedsalah13.backupmanager.storage.s3";
    private static final String GDRIVE_PACKAGE =
            "io.github.mohamedsalah13.backupmanager.storage.gdrive";
    private static final String DROPBOX_PACKAGE =
            "io.github.mohamedsalah13.backupmanager.storage.dropbox";

    private static FilteredClassLoader withoutOptionalModules() {
        return new FilteredClassLoader(
                MYSQL_PACKAGE, FILE_DB_PACKAGE, S3_PACKAGE, GDRIVE_PACKAGE, DROPBOX_PACKAGE);
    }

    /**
     * The real regression test, and it deliberately inspects bytecode rather than loading
     * classes.
     *
     * <p>Two classloader-based attempts at this were vacuous and worth recording so nobody
     * repeats them. {@code ApplicationContextRunner.withClassLoader} only swaps the thread
     * context loader — the auto-configuration classes are already loaded by the test's own
     * loader, which still has every module. And {@link FilteredClassLoader} delegates
     * unfiltered names to its parent, so asking it for {@code BackupJobFactory} hands back
     * the very class object the app loader already resolved. Both variants passed happily
     * with the bug reintroduced.
     *
     * <p>What actually matters is a property of the class file: the outer class must not
     * name an optional type at all. Checking the constant pool for those names is exact,
     * instant, and cannot be defeated by delegation.
     */
    @Test
    void outerFactoryClassFileNamesNoOptionalType() throws Exception {
        byte[] bytecode = classFileOf(BackupJobFactory.class);

        // Nested support classes legitimately reference these; the outer class must not.
        assertThat(referencedOptionalPackages(bytecode))
                .as("%s must not reference optional-module types directly — Spring introspects "
                                + "it on every startup and the JVM resolves what it names, which "
                                + "breaks any application that omits the module. Move the code "
                                + "into a nested support class instead.",
                        BackupJobFactory.class.getSimpleName())
                .isEmpty();
    }

    @Test
    void autoConfigurationClassFileNamesNoOptionalType() throws Exception {
        assertThat(referencedOptionalPackages(classFileOf(BackupAutoConfiguration.class))).isEmpty();
    }

    /** Proves the check has teeth: the nested support classes do name their module. */
    @Test
    void nestedSupportClassesAreWhereTheReferencesLive() throws Exception {
        byte[] bytecode = classFileOf(BackupJobFactory.MySqlSupport.class);

        assertThat(referencedOptionalPackages(bytecode)).contains(MYSQL_PACKAGE);
    }

    private static byte[] classFileOf(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (var in = type.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("class file for %s", type.getName()).isNotNull();
            return in.readAllBytes();
        }
    }

    /**
     * Optional-module packages named anywhere in a class file. Type names live in the
     * constant pool in internal form ({@code io/github/.../MySqlConfig}), so a byte-level
     * search over the whole file finds every reference without needing a bytecode library.
     */
    private static List<String> referencedOptionalPackages(byte[] bytecode) {
        String content = new String(bytecode, StandardCharsets.ISO_8859_1);
        return Stream.of(MYSQL_PACKAGE, FILE_DB_PACKAGE, S3_PACKAGE, GDRIVE_PACKAGE, DROPBOX_PACKAGE)
                .filter(pkg -> content.contains(pkg.replace('.', '/')))
                .toList();
    }

    @Test
    void contextStartsAndBacksUpFilesWithNoDatabaseModules() {
        runner.withClassLoader(withoutOptionalModules()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BackupManager.class);

            BackupManager manager = context.getBean(BackupManager.class);
            assertThat(manager.getJobs()).hasSize(1);
            assertThat(manager.backupNow("docs").getStatus()).isEqualTo(BackupStatus.SUCCESS);
        });
    }

    @Test
    void asksForTheMissingModuleByNameWhenAJobNeedsIt() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BackupAutoConfiguration.class))
                .withClassLoader(withoutOptionalModules())
                .withPropertyValues(
                        "backup.storage-root=" + temp.resolve("state2").toString().replace('\\', '/'),
                        "backup.jobs.db.source.type=mysql",
                        "backup.jobs.db.source.database=sales",
                        "backup.jobs.db.source.username=root",
                        "backup.jobs.db.destinations[0].type=local",
                        "backup.jobs.db.destinations[0].path="
                                + temp.resolve("b").toString().replace('\\', '/'))
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        // The point: a message naming the artifact, not NoClassDefFoundError.
                        .hasMessageContaining("backup-manager-db-mysql")
                        .hasMessageContaining("optional module"));
    }

    @Test
    void asksForTheMissingModuleWhenADestinationNeedsIt() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BackupAutoConfiguration.class))
                .withClassLoader(withoutOptionalModules())
                .withPropertyValues(
                        "backup.storage-root=" + temp.resolve("state3").toString().replace('\\', '/'),
                        "backup.jobs.docs.source.type=files",
                        "backup.jobs.docs.source.paths[0]=" + sourceDir.toString().replace('\\', '/'),
                        "backup.jobs.docs.destinations[0].type=s3",
                        "backup.jobs.docs.destinations[0].bucket=some-bucket")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .hasMessageContaining("backup-manager-storage-s3"));
    }
}
