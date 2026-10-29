package com.example.docmind.service.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Quản lý vòng đời đồng thời theo từng cặp {@code (pageId, senderPsid)} bằng {@code generationEpoch}
 * và trạng thái pha xử lý ({@link Phase}) để kiểm soát gom tin nhắn (Debounce Burst) và hủy luồng cũ (Superseded Turn)
 * tại 3 chốt chặn bắt buộc:
 * <ol>
 *   <li>{@link Checkpoint#AFTER_LLM_BEFORE_REFEREE}: Ngay sau khi {@code llmClient} trả kết quả, trước khi chạy Referee.</li>
 *   <li>{@link Checkpoint#BEFORE_STATE_AND_LEDGER_COMMIT}: Ngay trước khi ghi {@code ConversationState}, {@code SentBubbleLedger}, {@code AgentBrain}.</li>
 *   <li>{@link Checkpoint#BEFORE_OUTBOUND_BUBBLE_SEND}: Ngay trước khi gửi từng bong bóng lên Facebook Graph API.</li>
 * </ol>
 */
public final class SenderEpochRegistry {

    private static final Logger log = LoggerFactory.getLogger(SenderEpochRegistry.class);

    public enum Phase {
        IDLE,
        DEBOUNCING,
        RUNNING_LLM,
        SENDING_BUBBLES
    }

    public enum Checkpoint {
        AFTER_LLM_BEFORE_REFEREE,
        BEFORE_STATE_AND_LEDGER_COMMIT,
        BEFORE_OUTBOUND_BUBBLE_SEND
    }

    public record EpochTurnContext(
            String pageId,
            String senderPsid,
            String batchId,
            long epoch
    ) {
    }

    private static final class SenderSlot {
        final AtomicLong epochCounter = new AtomicLong(0L);
        final Object waitLock = new Object();
        volatile Phase phase = Phase.IDLE;
        volatile String activeBatchId = "";
        volatile Checkpoint lastAbortedCheckpoint = null;
        volatile long lastAbortedEpoch = 0L;
    }

    private static final Map<String, SenderSlot> SLOTS = new ConcurrentHashMap<>();
    private static final ThreadLocal<EpochTurnContext> THREAD_CONTEXT = new ThreadLocal<>();

    private SenderEpochRegistry() {
    }

    public static String slotKey(String pageId, String senderPsid) {
        String p = (pageId != null && !pageId.isBlank()) ? pageId.strip() : "-";
        String s = (senderPsid != null && !senderPsid.isBlank()) ? senderPsid.strip() : "-";
        return p + "|" + s;
    }

    private static SenderSlot slot(String pageId, String senderPsid) {
        return SLOTS.computeIfAbsent(slotKey(pageId, senderPsid), k -> new SenderSlot());
    }

    /**
     * Tăng nguyên tử {@code generationEpoch} cho {@code (pageId, senderPsid)}, đánh thức mọi luồng đang chờ
     * {@code bubbleDelayMs} của sender đó và trả về giá trị epoch mới.
     */
    public static long nextEpoch(String pageId, String senderPsid) {
        SenderSlot s = slot(pageId, senderPsid);
        long next = s.epochCounter.incrementAndGet();
        synchronized (s.waitLock) {
            s.waitLock.notifyAll();
        }
        return next;
    }

    public static long supersedeCurrentEpoch(String pageId, String senderPsid) {
        SenderSlot s = slot(pageId, senderPsid);
        long next = s.epochCounter.incrementAndGet();
        synchronized (s.waitLock) {
            s.waitLock.notifyAll();
        }
        return next;
    }

    public static void markDebouncing(String pageId, String senderPsid) {
        SenderSlot s = slot(pageId, senderPsid);
        s.phase = Phase.DEBOUNCING;
    }

    public static EpochTurnContext startNewBatchEpoch(String pageId, String senderPsid) {
        SenderSlot s = slot(pageId, senderPsid);
        long epoch = s.epochCounter.incrementAndGet();
        synchronized (s.waitLock) {
            s.waitLock.notifyAll();
        }
        String batchId = "batch-" + epoch;
        s.phase = Phase.RUNNING_LLM;
        s.activeBatchId = batchId;
        EpochTurnContext ctx = new EpochTurnContext(pageId, senderPsid, batchId, epoch);
        THREAD_CONTEXT.set(ctx);
        return ctx;
    }

    /**
     * Chờ có thể đánh thức (interruptible wait) trong khoảng {@code delayMs} giữa các bong bóng.
     * Nếu {@code generationEpoch} của {@code (pageId, senderPsid)} bị tăng lên bởi tin nhắn mới chen ngang
     * trong lúc đang nghỉ, luồng chờ được đánh thức NGAY LẬP TỨC và trả về {@code false} để dừng gửi các bong bóng còn lại.
     *
     * @return {@code true} nếu hết thời gian nghỉ mà epoch vẫn hợp lệ; {@code false} nếu bị hủy do epoch tăng hoặc bị interrupt.
     */
    public static boolean awaitBubbleDelayOrSupersede(
            String pageId,
            String senderPsid,
            long expectedEpoch,
            long delayMs
    ) {
        SenderSlot s = slot(pageId, senderPsid);
        if (expectedEpoch > 0L && s.epochCounter.get() != expectedEpoch) {
            return false;
        }
        if (delayMs <= 0L) {
            return expectedEpoch <= 0L || s.epochCounter.get() == expectedEpoch;
        }
        long deadline = System.currentTimeMillis() + delayMs;
        synchronized (s.waitLock) {
            while (true) {
                if (expectedEpoch > 0L && s.epochCounter.get() != expectedEpoch) {
                    return false;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    s.waitLock.wait(remaining);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return expectedEpoch <= 0L || s.epochCounter.get() == expectedEpoch;
    }

    public static boolean isEpochCurrent(String pageId, String senderPsid, long expectedEpoch) {
        if (expectedEpoch <= 0L) {
            return true;
        }
        return slot(pageId, senderPsid).epochCounter.get() == expectedEpoch;
    }

    public static void transitionToSendingBubbles(String pageId, String senderPsid, long expectedEpoch) {
        SenderSlot s = slot(pageId, senderPsid);
        if (s.epochCounter.get() == expectedEpoch) {
            s.phase = Phase.SENDING_BUBBLES;
        }
    }

    public static void completeTurn(String pageId, String senderPsid, long expectedEpoch) {
        SenderSlot s = slot(pageId, senderPsid);
        if (s.epochCounter.get() == expectedEpoch) {
            s.phase = Phase.IDLE;
        }
        THREAD_CONTEXT.remove();
    }

    public static long currentEpoch(String pageId, String senderPsid) {
        return slot(pageId, senderPsid).epochCounter.get();
    }

    public static Phase currentPhase(String pageId, String senderPsid) {
        return slot(pageId, senderPsid).phase;
    }

    public static void updatePhase(String pageId, String senderPsid, Phase phase, String batchId) {
        SenderSlot s = slot(pageId, senderPsid);
        s.phase = phase != null ? phase : Phase.IDLE;
        if (batchId != null) {
            s.activeBatchId = batchId;
        }
    }

    public static void bindCurrentThreadTurn(String pageId, String senderPsid, String batchId, long epoch) {
        THREAD_CONTEXT.set(new EpochTurnContext(pageId, senderPsid, batchId, epoch));
    }

    public static EpochTurnContext getCurrentThreadTurn() {
        return THREAD_CONTEXT.get();
    }

    public static void clearCurrentThreadTurn() {
        THREAD_CONTEXT.remove();
    }

    /**
     * Kiểm tra chốt chặn {@code epoch == currentEpoch} cho luồng hiện tại.
     *
     * @return {@code true} nếu epoch của luồng hiện tại vẫn hợp lệ ({@code epoch == currentEpoch});
     *         {@code false} nếu luồng đã bị thay thế bởi tin nhắn mới hơn (superseded).
     */
    public static boolean checkThreadEpochStillCurrent(String pageId, String senderPsid, Checkpoint checkpoint) {
        EpochTurnContext ctx = THREAD_CONTEXT.get();
        if (ctx == null) {
            return true;
        }
        String checkPage = (pageId != null && !pageId.isBlank()) ? pageId : ctx.pageId();
        String checkSender = (senderPsid != null && !senderPsid.isBlank()) ? senderPsid : ctx.senderPsid();
        return checkEpochStillCurrent(checkPage, checkSender, ctx.batchId(), ctx.epoch(), checkpoint);
    }

    public static boolean checkEpochStillCurrent(
            String pageId,
            String senderPsid,
            String batchId,
            long expectedEpoch,
            Checkpoint checkpoint
    ) {
        if (expectedEpoch <= 0L) {
            return true;
        }
        SenderSlot s = slot(pageId, senderPsid);
        long liveEpoch = s.epochCounter.get();
        if (expectedEpoch != liveEpoch) {
            s.lastAbortedCheckpoint = checkpoint;
            s.lastAbortedEpoch = expectedEpoch;
            log.info("[FB_EPOCH_GUARD] Superseded turn aborted at checkpoint={} page={} sender={} batchId={} expectedEpoch={} currentEpoch={}",
                    checkpoint, pageId, senderPsid, batchId, expectedEpoch, liveEpoch);
            return false;
        }
        return true;
    }

    public static Checkpoint getLastAbortedCheckpoint(String pageId, String senderPsid) {
        return slot(pageId, senderPsid).lastAbortedCheckpoint;
    }

    public static long getLastAbortedEpoch(String pageId, String senderPsid) {
        return slot(pageId, senderPsid).lastAbortedEpoch;
    }

    public static void clearAll() {
        SLOTS.clear();
        THREAD_CONTEXT.remove();
    }
}
