package com.example.docmind.service.bot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Durable file-backed Write-Ahead Log (JSONL + fsync) for asynchronous Webhook inbound messages
 * when PostgreSQL is temporarily unreachable (Unit P0.1 / Spec Item I.1 — 8 Integrity Conditions):
 * <ol>
 *   <li>Strict write &amp; replay ordering:
 *       {@code Append JSONL + sha256Checksum -> FileChannel.force(true) (+ dir fsync) -> DB Commit -> Atomic Checkpoint (.tmp + fsync + ATOMIC_MOVE + dir fsync)}.
 *       Checkpoint NEVER advances before DB commit succeeds.</li>
 *   <li>FK Safety on replay: {@link WalRecordDbReplayer} MUST ensure {@code conversation_sessions} exists via
 *       {@code findOrCreateSession} before inserting into {@code conversation_messages}.</li>
 *   <li>Corrupted/partial JSONL recovery: incomplete lines or mismatched {@code sha256Checksum} are logged with
 *       {@code [WAL_CORRUPTED_RECORD_QUARANTINED]}, durably appended to {@code inbound_recovery_wal.quarantine}
 *       ({@code 0600} + {@code fsync}), and skipped without hanging subsequent valid records.</li>
 *   <li>POSIX permissions &amp; crash-safe 2-step compaction: directory {@code 0700}, files {@code 0600}.</li>
 *   <li>{@code 64 MB} ({@code 67,108,864} bytes) / {@code ENOSPC} / unwritable volume guard: increments
 *       {@code docmind_inbound_wal_overflow_total} and logs {@code ERROR [INBOUND_WAL_OVERFLOW_CRITICAL]}.</li>
 * </ol>
 */
@Component
public class DurableInboundWalRecoveryStore {

    private static final Logger log = LoggerFactory.getLogger(DurableInboundWalRecoveryStore.class);

    public static final long DEFAULT_MAX_WAL_BYTES = 67_108_864L; // 64 MB
    public static final String WAL_FILENAME = "inbound_recovery_wal.jsonl";
    public static final String CHECKPOINT_FILENAME = "inbound_recovery_wal.checkpoint";
    public static final String QUARANTINE_FILENAME = "inbound_recovery_wal.quarantine";
    private static final String CHECKPOINT_TMP_FILENAME = "inbound_recovery_wal.checkpoint.tmp";
    private static final String WAL_COMPACT_TMP_FILENAME = "inbound_recovery_wal.jsonl.compact.tmp";

    private static final Set<PosixFilePermission> DIR_PERMS_0700 = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
    );

    private static final Set<PosixFilePermission> FILE_PERMS_0600 = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private final Path walDir;
    private final Path walFile;
    private final Path checkpointFile;
    private final Path quarantineFile;
    private final Path checkpointTmpFile;
    private final Path walCompactTmpFile;
    private final long maxWalBytes;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object walMutex = new Object();

    private final AtomicLong seqGenerator = new AtomicLong(0L);
    private final AtomicLong walOverflowTotal = new AtomicLong(0L);
    private final AtomicLong walQuarantinedTotal = new AtomicLong(0L);
    private final AtomicLong walReplayedTotal = new AtomicLong(0L);

    private volatile WalRecordDbReplayer dbReplayer;

    @FunctionalInterface
    public interface WalRecordDbReplayer {
        /**
         * Must atomically {@code findOrCreateSession} and execute
         * {@code INSERT INTO conversation_messages ... ON CONFLICT (session_id, external_mid) WHERE external_mid IS NOT NULL DO NOTHING},
         * throwing an exception if PostgreSQL has not yet committed.
         */
        void replayToDatabase(WalRecord record) throws Exception;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WalRecord(
            long walSeq,
            String pageId,
            String senderId,
            String customerName,
            String conversationId,
            String inboundExternalMid,
            String verbatimText,
            long receivedAtEpochMs,
            String sha256Checksum
    ) {
    }

    public record WalAppendResult(
            boolean durableOnDisk,
            boolean overflowOrIoRejected,
            long walSeq,
            String failureReason
    ) {
        public static WalAppendResult success(long seq) {
            return new WalAppendResult(true, false, seq, null);
        }

        public static WalAppendResult rejected(String reason) {
            return new WalAppendResult(false, true, -1L, reason);
        }
    }

    public record WalReplayReport(
            int replayedValidRecords,
            int quarantinedCorruptRecords,
            boolean compacted,
            boolean stoppedOnDbError
    ) {
    }

    @Autowired
    public DurableInboundWalRecoveryStore(
            @Value("${docmind.inbox.wal.dir:/var/lib/docmind/inbound-wal}") String walDirPath,
            @Value("${docmind.inbox.wal.max-bytes:67108864}") long maxWalBytes
    ) {
        this(Paths.get(walDirPath), maxWalBytes);
    }

    public DurableInboundWalRecoveryStore(Path walDir, long maxWalBytes) {
        this.walDir = walDir;
        this.walFile = walDir.resolve(WAL_FILENAME);
        this.checkpointFile = walDir.resolve(CHECKPOINT_FILENAME);
        this.quarantineFile = walDir.resolve(QUARANTINE_FILENAME);
        this.checkpointTmpFile = walDir.resolve(CHECKPOINT_TMP_FILENAME);
        this.walCompactTmpFile = walDir.resolve(WAL_COMPACT_TMP_FILENAME);
        this.maxWalBytes = maxWalBytes > 0 ? maxWalBytes : DEFAULT_MAX_WAL_BYTES;
    }

    public void setDbReplayer(WalRecordDbReplayer dbReplayer) {
        this.dbReplayer = dbReplayer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            if (Files.exists(walDir)) {
                cleanupOrphanedTempFiles();
                if (hasPendingWalRecords()) {
                    WalReplayReport report = replayPendingRecords();
                    log.info("[INBOUND_WAL_STARTUP_REPLAY] replayed={} quarantined={} compacted={} stoppedOnDbError={}",
                            report.replayedValidRecords(), report.quarantinedCorruptRecords(),
                            report.compacted(), report.stoppedOnDbError());
                }
            }
        } catch (Exception ex) {
            log.warn("[INBOUND_WAL_STARTUP_REPLAY_DEFERRED] reason={}", ex.getMessage());
        }
    }

    /**
     * Appends one inbound customer message record to {@code inbound_recovery_wal.jsonl}
     * and forces data + metadata to stable storage via {@link FileChannel#force(boolean)}.
     */
    public WalAppendResult appendAndFsync(
            String pageId,
            String senderId,
            String customerName,
            String conversationId,
            String inboundExternalMid,
            String verbatimText,
            long receivedAtEpochMs
    ) {
        synchronized (walMutex) {
            try {
                ensureDirectoryWith0700();
                long seq = seqGenerator.incrementAndGet();
                String checksum = computeRecordChecksum(
                        seq, pageId, senderId, inboundExternalMid, receivedAtEpochMs, verbatimText
                );
                WalRecord rec = new WalRecord(
                        seq,
                        pageId,
                        senderId,
                        customerName,
                        conversationId,
                        inboundExternalMid,
                        verbatimText,
                        receivedAtEpochMs,
                        checksum
                );
                String serialized = objectMapper.writeValueAsString(rec) + "\n";
                byte[] recordBytes = serialized.getBytes(StandardCharsets.UTF_8);

                long currentBytes = Files.exists(walFile) ? Files.size(walFile) : 0L;
                if (currentBytes + recordBytes.length > maxWalBytes) {
                    long totalOverflow = walOverflowTotal.incrementAndGet();
                    String reason = "WAL_MAX_BYTES_EXCEEDED(current=" + currentBytes
                            + ",incoming=" + recordBytes.length + ",max=" + maxWalBytes + ")";
                    log.error("[INBOUND_WAL_OVERFLOW_CRITICAL] docmind_inbound_wal_overflow_total={} "
                                    + "pageId={} senderId={} mid={} reason={}",
                            totalOverflow, pageId, senderId, inboundExternalMid, reason);
                    return WalAppendResult.rejected(reason);
                }

                boolean newlyCreated = !Files.exists(walFile);
                try (FileChannel channel = openFileChannel0600(
                        walFile,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                )) {
                    enforcePosixPermissions(walFile, FILE_PERMS_0600);
                    writeFully(channel, recordBytes);
                    channel.force(true);
                }
                if (newlyCreated) {
                    fsyncDirectoryBestEffort(walDir);
                }
                return WalAppendResult.success(seq);
            } catch (IOException ioEx) {
                long totalOverflow = walOverflowTotal.incrementAndGet();
                String reason = "WAL_IO_OR_ENOSPC_FAILURE(" + ioEx.getClass().getSimpleName()
                        + ":" + ioEx.getMessage() + ")";
                log.error("[INBOUND_WAL_OVERFLOW_CRITICAL] docmind_inbound_wal_overflow_total={} "
                                + "pageId={} senderId={} mid={} reason={}",
                        totalOverflow, pageId, senderId, inboundExternalMid, reason);
                return WalAppendResult.rejected(reason);
            }
        }
    }

    public boolean hasPendingWalRecords() {
        synchronized (walMutex) {
            try {
                if (!Files.exists(walFile)) {
                    return false;
                }
                long fileSize = Files.size(walFile);
                if (fileSize <= 0L) {
                    return false;
                }
                long checkpointOffset = readCheckpointOffsetSafe(fileSize);
                return checkpointOffset < fileSize;
            } catch (IOException ex) {
                return false;
            }
        }
    }

    /**
     * Replays pending WAL records in strict sequential order:
     * {@code Read raw bytes from checkpoint -> Verify trailing LF + JSON + sha256Checksum -> DB Commit -> Atomic Checkpoint}.
     * Only compacts when {@code committedOffset == fileSize} using the crash-safe 2-step sequence.
     */
    public WalReplayReport replayPendingRecords() {
        return replayPendingRecords(this.dbReplayer);
    }

    public WalReplayReport replayPendingRecords(WalRecordDbReplayer activeReplayer) {
        if (activeReplayer == null) {
            return new WalReplayReport(0, 0, false, false);
        }
        synchronized (walMutex) {
            int replayedCount = 0;
            int quarantinedCount = 0;
            try {
                if (!Files.exists(walDir)) {
                    return new WalReplayReport(0, 0, false, false);
                }
                cleanupOrphanedTempFiles();
                if (!Files.exists(walFile)) {
                    return new WalReplayReport(0, 0, false, false);
                }
                byte[] allBytes = Files.readAllBytes(walFile);
                long fileSize = allBytes.length;
                if (fileSize == 0L) {
                    if (Files.exists(checkpointFile) && readCheckpointOffsetSafe(0L) != 0L) {
                        writeCheckpointAtomically(0L);
                    }
                    return new WalReplayReport(0, 0, false, false);
                }

                long offset = readCheckpointOffsetSafe(fileSize);
                while (offset < fileSize) {
                    int startIdx = (int) offset;
                    int lfIdx = -1;
                    for (int i = startIdx; i < allBytes.length; i++) {
                        if (allBytes[i] == (byte) '\n') {
                            lfIdx = i;
                            break;
                        }
                    }

                    boolean hasTrailingNewline = (lfIdx >= startIdx);
                    int endExclusive = hasTrailingNewline ? lfIdx : allBytes.length;
                    long nextOffset = hasTrailingNewline ? (lfIdx + 1L) : fileSize;

                    // Ignore pure CR/whitespace-only empty lines safely if any
                    String rawLine = new String(allBytes, startIdx, endExclusive - startIdx, StandardCharsets.UTF_8);
                    if (hasTrailingNewline && rawLine.isBlank()) {
                        writeCheckpointAtomically(nextOffset);
                        offset = nextOffset;
                        continue;
                    }

                    if (!hasTrailingNewline) {
                        quarantineCorruptedLine(offset, rawLine, "TRUNCATED_RECORD_WITHOUT_NEWLINE");
                        quarantinedCount++;
                        writeCheckpointAtomically(nextOffset);
                        offset = nextOffset;
                        continue;
                    }

                    WalRecord parsed;
                    try {
                        parsed = objectMapper.readValue(rawLine, WalRecord.class);
                    } catch (Exception parseEx) {
                        quarantineCorruptedLine(offset, rawLine, "MALFORMED_JSON:" + parseEx.getClass().getSimpleName());
                        quarantinedCount++;
                        writeCheckpointAtomically(nextOffset);
                        offset = nextOffset;
                        continue;
                    }

                    if (!isChecksumAndRequiredFieldsValid(parsed)) {
                        quarantineCorruptedLine(offset, rawLine, "SHA256_CHECKSUM_OR_REQUIRED_FIELD_MISMATCH");
                        quarantinedCount++;
                        writeCheckpointAtomically(nextOffset);
                        offset = nextOffset;
                        continue;
                    }

                    // Strict ordering: Commit to PostgreSQL BEFORE advancing checkpoint!
                    try {
                        activeReplayer.replayToDatabase(parsed);
                    } catch (Exception dbEx) {
                        log.warn("[INBOUND_WAL_REPLAY_PAUSED_ON_DB_ERROR] offset={} walSeq={} mid={} reason={}",
                                offset, parsed.walSeq(), parsed.inboundExternalMid(), dbEx.getMessage());
                        return new WalReplayReport(replayedCount, quarantinedCount, false, true);
                    }

                    // DB commit succeeded -> advance durable checkpoint atomically
                    writeCheckpointAtomically(nextOffset);
                    offset = nextOffset;
                    replayedCount++;
                    walReplayedTotal.incrementAndGet();
                }

                boolean compacted = false;
                if (offset >= fileSize && fileSize > 0L) {
                    compactCompletedWalFile();
                    compacted = true;
                }
                return new WalReplayReport(replayedCount, quarantinedCount, compacted, false);
            } catch (IOException ioEx) {
                log.error("[INBOUND_WAL_REPLAY_IO_ERROR] reason={}", ioEx.getMessage());
                return new WalReplayReport(replayedCount, quarantinedCount, false, true);
            }
        }
    }

    /**
     * Crash-safe 2-step compaction executed ONLY when 100% of records in {@code walFile}
     * have already been committed to PostgreSQL:
     * <ol>
     *   <li>Reset checkpoint to {@code 0} atomically ({@code .tmp -> fsync -> ATOMIC_MOVE -> dir fsync}).
     *       If JVM crashes immediately after Step 1, restart replays idempotently via
     *       {@code ON CONFLICT (session_id, external_mid) WHERE external_mid IS NOT NULL DO NOTHING}
     *       with {@code 0} duplicates.</li>
     *   <li>Replace {@code walFile} with an empty {@code 0600} file atomically
     *       ({@code .compact.tmp -> fsync -> ATOMIC_MOVE -> dir fsync}).</li>
     * </ol>
     */
    private void compactCompletedWalFile() throws IOException {
        writeCheckpointAtomically(0L);
        try (FileChannel tmpChannel = openFileChannel0600(
                walCompactTmpFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            enforcePosixPermissions(walCompactTmpFile, FILE_PERMS_0600);
            tmpChannel.force(true);
        }
        atomicMoveWithFallback(walCompactTmpFile, walFile);
        enforcePosixPermissions(walFile, FILE_PERMS_0600);
        fsyncDirectoryBestEffort(walDir);
    }

    void writeCheckpointAtomically(long committedByteOffset) throws IOException {
        ensureDirectoryWith0700();
        byte[] payload = (committedByteOffset + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel tmpChannel = openFileChannel0600(
                checkpointTmpFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            enforcePosixPermissions(checkpointTmpFile, FILE_PERMS_0600);
            writeFully(tmpChannel, payload);
            tmpChannel.force(true);
        }
        atomicMoveWithFallback(checkpointTmpFile, checkpointFile);
        enforcePosixPermissions(checkpointFile, FILE_PERMS_0600);
        fsyncDirectoryBestEffort(walDir);
    }

    private void quarantineCorruptedLine(long byteOffset, String rawLine, String reason) throws IOException {
        ensureDirectoryWith0700();
        long qCount = walQuarantinedTotal.incrementAndGet();
        log.error("[WAL_CORRUPTED_RECORD_QUARANTINED] offset={} reason={} quarantinedTotal={}",
                byteOffset, reason, qCount);

        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        String header = Instant.now() + " | offset=" + byteOffset + " | reason=" + reason + " | raw=";
        entry.write(header.getBytes(StandardCharsets.UTF_8));
        String singleLineRaw = rawLine != null ? rawLine.replace('\r', ' ').replace('\n', ' ') : "";
        entry.write(singleLineRaw.getBytes(StandardCharsets.UTF_8));
        entry.write('\n');

        boolean newlyCreated = !Files.exists(quarantineFile);
        try (FileChannel qChannel = openFileChannel0600(
                quarantineFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        )) {
            enforcePosixPermissions(quarantineFile, FILE_PERMS_0600);
            writeFully(qChannel, entry.toByteArray());
            qChannel.force(true);
        }
        if (newlyCreated) {
            fsyncDirectoryBestEffort(walDir);
        }
    }

    private long readCheckpointOffsetSafe(long currentWalSize) {
        if (!Files.exists(checkpointFile)) {
            return 0L;
        }
        try {
            String raw = Files.readString(checkpointFile, StandardCharsets.UTF_8).strip();
            if (raw.isEmpty()) {
                return 0L;
            }
            long parsed = Long.parseLong(raw);
            if (parsed < 0L || parsed > currentWalSize) {
                return 0L;
            }
            return parsed;
        } catch (Exception ex) {
            return 0L;
        }
    }

    private void cleanupOrphanedTempFiles() {
        try {
            Files.deleteIfExists(checkpointTmpFile);
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(walCompactTmpFile);
        } catch (IOException ignored) {
        }
    }

    private boolean isChecksumAndRequiredFieldsValid(WalRecord record) {
        if (record == null
                || record.senderId() == null || record.senderId().isBlank()
                || record.inboundExternalMid() == null || record.inboundExternalMid().isBlank()
                || record.verbatimText() == null
                || record.sha256Checksum() == null || record.sha256Checksum().isBlank()) {
            return false;
        }
        String expected = computeRecordChecksum(
                record.walSeq(),
                record.pageId(),
                record.senderId(),
                record.inboundExternalMid(),
                record.receivedAtEpochMs(),
                record.verbatimText()
        );
        return expected.equalsIgnoreCase(record.sha256Checksum().strip());
    }

    public static String computeRecordChecksum(
            long walSeq,
            String pageId,
            String senderId,
            String inboundExternalMid,
            long receivedAtEpochMs,
            String verbatimText
    ) {
        String material = walSeq + "|"
                + (pageId != null ? pageId : "") + "|"
                + (senderId != null ? senderId : "") + "|"
                + (inboundExternalMid != null ? inboundExternalMid : "") + "|"
                + receivedAtEpochMs + "|"
                + (verbatimText != null ? verbatimText : "");
        return InboundEventIdResolver.sha256Hex(material);
    }

    private void ensureDirectoryWith0700() throws IOException {
        if (!Files.exists(walDir)) {
            if (supportsPosix(walDir.getParent() != null ? walDir.getParent() : Paths.get("/"))) {
                FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(DIR_PERMS_0700);
                Files.createDirectories(walDir, attr);
            } else {
                Files.createDirectories(walDir);
            }
        }
        enforcePosixPermissions(walDir, DIR_PERMS_0700);
    }

    private static FileChannel openFileChannel0600(Path target, StandardOpenOption... options) throws IOException {
        Set<StandardOpenOption> optSet = EnumSet.noneOf(StandardOpenOption.class);
        for (StandardOpenOption opt : options) {
            optSet.add(opt);
        }
        if (supportsPosix(target.getParent())) {
            FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(FILE_PERMS_0600);
            return FileChannel.open(target, optSet, attr);
        }
        return FileChannel.open(target, optSet);
    }

    private static boolean supportsPosix(Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.getFileStore(Files.exists(path) ? path : Paths.get("/"))
                    .supportsFileAttributeView(PosixFileAttributeView.class);
        } catch (Exception ex) {
            return false;
        }
    }

    private static void enforcePosixPermissions(Path path, Set<PosixFilePermission> perms) {
        try {
            if (Files.exists(path) && Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(path, perms);
            }
        } catch (IOException ignored) {
        }
    }

    private static void atomicMoveWithFallback(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeFully(FileChannel channel, byte[] data) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void fsyncDirectoryBestEffort(Path dir) {
        try (FileChannel dirChannel = FileChannel.open(dir, StandardOpenOption.READ)) {
            dirChannel.force(true);
        } catch (Exception ignored) {
        }
    }

    public Path getWalDir() {
        return walDir;
    }

    public Path getWalFile() {
        return walFile;
    }

    public Path getCheckpointFile() {
        return checkpointFile;
    }

    public Path getQuarantineFile() {
        return quarantineFile;
    }

    public long getWalOverflowTotal() {
        return walOverflowTotal.get();
    }

    public long getWalQuarantinedTotal() {
        return walQuarantinedTotal.get();
    }

    public long getWalReplayedTotal() {
        return walReplayedTotal.get();
    }
}
