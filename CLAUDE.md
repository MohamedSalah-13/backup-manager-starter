# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A reusable backup/restore library published as a Maven dependency for other Java, JavaFX and
Spring Boot projects — not an application. The public SPI is a contract for downstream
consumers: add new implementations rather than changing existing signatures.

The user writes in Arabic; the README is Arabic, and code/identifiers are English.

## Build and test

```bash
mvn clean install
```

Building a single module standalone fails unless the parent POM is in the local repository.
Either install it once, or use `-am`:

```bash
mvn -N install
```

```bash
mvn -pl backup-manager-db-file -am test
```

Run one test class or one method:

```bash
mvn -pl backup-manager-core test -Dtest=AesGcmEncryptorTest
```

```bash
mvn -pl backup-manager-core test -Dtest=CronExpressionTest#handlesLastDayOfMonth
```

Java 21, Maven 3.9+. There is no linter configured.

## Module layout and the dependency rule

```
backup-manager-core                 ← engine + SPI + local storage. No heavy dependencies.
backup-manager-db-mysql             ← MySQL/MariaDB source
backup-manager-db-file              ← SQLite / H2 / Derby sources
backup-manager-storage-{s3,gdrive,dropbox}
backup-manager-spring-boot-starter  ← auto-config, YAML binding, Actuator
backup-manager-javafx               ← UI panes; JavaFX is `provided` scope
```

Core depends only on SLF4J, Jackson and commons-compress. Every SDK (AWS, Google, Dropbox,
JDBC drivers) lives in its own module, and the starter declares them `<optional>true</optional>`.
Do not add a heavy dependency to core — that is the constraint the whole layout exists to
protect.

`BackupJobFactory.requireModule(...)` in the starter turns a missing optional module into a
message naming the artifact to add. New source/destination types wired from YAML should go
through it.

## The pipeline

`BackupEngine.runOnce` is the spine; reading it explains most of the system.

```
source.dump → Compressor → Encryptor → DigestOutputStream → staging file
                                        → upload to every destination
                                        → verify → catalog → retention
```

Order is load-bearing:

- **Compression before encryption** — ciphertext does not compress.
- **Digest at the bottom** — it covers exactly the bytes that reach storage, which is what
  `verifyStoredCopies` re-reads and what a restore checks.
- **Staging to a local file before upload** — the checksum is known before anything is
  published, a second destination costs one more copy rather than another database dump,
  and a failed upload retries without touching the source again.

`RestoreEngine` reverses it, and resolves incremental chains back to the anchoring full
backup (`resolveChain`).

## SPI contracts

`BackupSource` is deliberately symmetric: `dump(ctx, OutputStream)` writes a payload,
`restore(ctx, InputStream)` reads that same payload back. Compression, encryption,
checksums, fan-out and retention are the engine's job — a source only serialises its own data.

**Implementations must not close the stream they are given.** The engine owns it and has
trailers to write. `NonClosingOutputStream` guards this on the dump side, but respect the
rule anyway.

`StorageProvider` must be thread-safe (a job fans out to destinations). `BackupCatalog` must
be thread-safe. `BackupListener` exceptions are logged and swallowed by `ListenerDispatcher`
— a broken listener must never fail a backup that reached storage.

## Invariants that are easy to break

**Encryption wire format** (`AesGcmEncryptor`): magic `BMENC`, version byte, KDF id,
iteration count, salt, nonce prefix, chunk size; then length-prefixed AES-GCM chunks; then a
zero-length terminator. Changing this format orphans every existing backup. The PBKDF2
iteration count is read from the artifact, not from config, so raising the default stays
backward compatible. The header and chunk index are AAD — that is what makes header tampering
and chunk reordering detectable.

**Restore uses the record's codec, not the job's.** `Compressors.byId(record.getCompressorId())`.
A job whose compression setting changed must still read what it wrote last month. New
codecs must be registered in `Compressors` or old artifacts become unreadable.

**Retention combines as a union of what to keep**, so enabling another rule can only preserve
more, never fewer. Failed runs do not consume the keep quota — otherwise a week of failures
with `keepLast=7` evicts every good backup. `minimumKept` is a floor applied last.

**Partial success is not failure.** One unreachable destination yields `PARTIAL` and keeps the
copies that succeeded; only `requireAllDestinations(true)` escalates to `FAILED`.

**Secrets never go on a command line.** `ProcessRunner.writeSecureOptionsFile` exists because
`--password=` is visible to every user via the process list.

## Test conventions

Artifact names use whole-second timestamps (`FileNames.TIMESTAMP`), so tests that create
several backups for one job sleep ~1100 ms between them to avoid name collisions.

`FileDatabaseBackupTest` drives real SQLite and H2 databases, not mocks. Prefer that over
mocking when adding a source — the failures worth catching are in the actual engine behaviour.

There are no JavaFX tests: `javafx-*` is `provided` scope and no toolkit runs in CI.

Spring tests use `ApplicationContextRunner`, not `@SpringBootTest`.

## Before publishing

`groupId` is the placeholder `com.backupmanager` in all POMs and must change to the user's own
namespace before release to any internal repository.
