package com.example.docmind.service.sales;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.service.metadata.ChunkMetadataExtractor;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateful PriceLadderTracker across conversation history for monotonic down-sell.
 * Feature F7 in DocMind Obsidian Knowledge Graph Upgrade.
 *
 * Responsibilities:
 * 1. Extract previously quoted prices from assistant messages in <CONVERSATION_HISTORY>.
 * 2. Detect customer price objection signals ("cao thế", "đắt", "giá cao", "rẻ hơn", "chát", "mắc")
 *    and trial request signals ("muốn thử", "trải nghiệm trước", "thử xem").
 * 3. Enforce monotonic down-sell progression (e.g. 399.000 VNĐ -> 239.000 VNĐ -> 129.000 VNĐ).
 * 4. Detect catalog floor (isCatalogFloor = true) and bind applicable promotional policies (e.g. 10% discount).
 * 5. Guarantee needs_human_handoff = false as long as down-sell or promo options remain active.
 */
@Service
public class PriceLadderTracker {

    private static final Logger log = LoggerFactory.getLogger(PriceLadderTracker.class);

    // =========================================================================
    // DTO CONTRACTS
    // =========================================================================

    public record PriceLadderResult(
            boolean isPriceObjection,
            boolean isTrialRequest,
            Long previousPriceQuoted,
            Long targetPrice,
            String targetPackageName,
            String discountPolicy,
            boolean isCatalogFloor,
            String rationale
    ) {
        public boolean hasTargetPrice() {
            return targetPrice != null;
        }

        public boolean shouldSuppressHandoff() {
            return isPriceObjection || isTrialRequest || isCatalogFloor;
        }
    }

    public record PriceTier(
            long price,
            String packageName,
            String duration,
            String branch,
            String serviceCode,
            String category
    ) {
        public String code() {
            return serviceCode;
        }
    }

    // =========================================================================
    // PATTERNS
    // =========================================================================

    public static final Pattern PRICE_OBJECTION_PATTERN = Pattern.compile(
            "(?isU).*(?:(?<!\\p{L})(?:cao\\s+(?:thế|the|quá|qua|vậy|vay)|hơi\\s+cao|hoi\\s+cao|giá\\s+cao|gia\\s+cao|cao\\s+hơn|cao\\s+hon|"
            + "chi\\s+phí\\s+cao|chi\\s+phi\\s+cao|tiền\\s+cao|tien\\s+cao|"
            + "hơi\\s+đắt|đắt\\s+quá|đắt\\s+thế|đắt\\s+vậy|đắt|"
            + "hoi\\s+dat|dat\\s+(?:quá|qua|thế|the|vậy|vay|nhỉ|nhi|thế\\s+nhỉ|the\\s+nhi|hơn|hon|nhất|nhat|đó|do|đấy|day)|"
            + "giá\\s+dat|gia\\s+dat|giá\\s+hơi\\s+dat|gia\\s+hoi\\s+dat|"
            + "sao\\s+(?:mà\\s+|ma\\s+)?dat(?:\\s+(?:thế|the|quá|qua|vậy|vay|nhỉ|nhi))?|quá\\s+dat|qua\\s+dat|thấy\\s+dat|thay\\s+dat|"
            + "có\\s+dat\\s+(?:không|khong|ko|k)|co\\s+dat\\s+(?:không|khong|ko|k)|"
            + "mắc\\s+quá|mac\\s+qua|hơi\\s+mắc|hoi\\s+mac|mắc|mac|chát\\s+quá|chat\\s+qua|"
            + "rẻ\\s+hơn|re\\s+hon|rẻ\\s+nhất|re\\s+nhat|mềm\\s+hơn|mem\\s+hon|thấp\\s+hơn|thap\\s+hon|thấp\\s+nhất|thap\\s+nhat|"
            + "tiết\\s+kiệm\\s+hơn|tiet\\s+kiem\\s+hon|gói\\s+nào\\s+rẻ|goi\\s+nao\\s+re|gói\\s+rẻ|goi\\s+re|gói\\s+nhỏ|goi\\s+nho|"
            + "có\\s+gói\\s+nào\\s+rẻ\\s+hơn\\s+không|co\\s+goi\\s+nao\\s+re\\s+hon\\s+khong|"
            + "có\\s+gói\\s+nào\\s+rẻ\\s+hơn|co\\s+goi\\s+nao\\s+re\\s+hon|có\\s+gói\\s+rẻ\\s+hơn|co\\s+goi\\s+re\\s+hon|"
            + "gói\\s+nào\\s+mềm\\s+hơn|goi\\s+nao\\s+mem\\s+hon|"
            + "mặc\\s+cả|mac\\s+ca|"
            + "bớt\\s+đi|bot\\s+di|"
            + "bớt\\s+(?:chút|chut|tí|ti|xíu|xiu|thêm|them|giá|gia|tiền|tien|được|duoc|đc|dc|cho|ạ|a|nhé|nhe|nha)|"
            + "bot\\s+(?:chut|ti|xiu|them|gia|tien|duoc|dc|cho|a|nhe|nha)|"
            + "xin\\s+bớt|xin\\s+bot|"
            + "(?:có\\s+bớt|co\\s+bot)(?!\\s*(?:đau|mỏi|stress|mụn|thâm|nám|sẹo|béo|cân|mỡ|nhăn|sưng|dau|moi|mun|tham|nam|seo|beo|can|mo|nhan|sung))|"
            + "bớt\\s*(?:không|khong|ko|k)|bot\\s*(?:không|khong|ko|k)|"
            + "bớt(?!\\s*(?:đau|mỏi|stress|mụn|thâm|nám|sẹo|béo|cân|mỡ|nhăn|sưng|dau|moi|mun|tham|nam|seo|beo|can|mo|nhan|sung))|"
            + "(?<!(?:chat|chào|chao|hello|hi|con|là|la|hỏi|hoi|với|voi)\\s+)bot(?!\\s*(?:ơi|oi|ạ|a|à|chat|tự|tu|này|nay|trà|tra|thuốc|thuoc|tắm|tam|trắng|trang))|"
            + "giảm\\s+giá|giam\\s+gia|"
            + "giảm\\s+(?:không|khong|ko|k)|giam\\s+(?:không|khong|ko|k)|"
            + "giảm\\s+(?:thêm|them|bớt|bot|chút|chut|tiền|tien|chi\\s+phí|chi\\s+phi|được|duoc|đc|dc)|"
            + "giam\\s+(?:them|bot|chut|tien|chi\\s+phi|duoc|dc)|"
            + "xin\\s+giảm|xin\\s+giam|được\\s+giảm|duoc\\s+giam|"
            + "(?:có\\s+giảm|co\\s+giam)(?!\\s*(?:đau|mỏi|stress|mụn|thâm|nám|sẹo|béo|cân|mỡ|nhăn|sưng|áp|tải|dau|moi|mun|tham|nam|seo|beo|can|mo|nhan|sung|ap|tai))|"
            + "giảm(?!\\s*(?:đau|mỏi|stress|mụn|thâm|nám|sẹo|béo|cân|mỡ|nhăn|sưng|áp|tải))|"
            + "giam(?!\\s*(?:dau|moi|stress|mun|tham|nam|seo|beo|can|mo|nhan|sung|ap|tai))"
            + "))(?!\\p{L}).*"
    );

    public static final Pattern TRIAL_REQUEST_PATTERN = Pattern.compile(
            "(?isU).*(?:(?<!\\p{L})(?:thử\\s+xem(?:\\s+sao|\\s+thế\\s+nào|\\s+nhé|\\s+nha)?|"
            + "sẽ\\s+thử|mình\\s+thử|t\\s+thử|chị\\s+thử|anh\\s+thử|qua\\s+thử|khám\\s+thử|soi\\s+da\\s+thử|"
            + "muốn\\s+thử|muosn\\s+thử|thử\\s+trải\\s+nghiệm|muốn\\s+trải\\s+nghiệm|"
            + "trải\\s+nghiệm\\s+thử|trải\\s+nghiệm\\s+trước|làm\\s+thử|thử\\s+trước))(?!\\p{L}).*"
    );

    public static final Pattern EXTRACT_VND_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d{1,3}(?:\\.\\d{3})+|\\d{2,4}k)\\s*(?:vnđ|vnd|đ|đồng|/buổi)?(?![\\p{L}\\d])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    public static final Pattern BRANCH_SAIGON_PATTERN = Pattern.compile(
            "(?iU)\\b(quận\\s+1|q1|sài\\s+gòn|tphcm|hồ\\s+chí\\s+minh|hcm)\\b"
    );

    public static final Pattern BRANCH_HANOI_PATTERN = Pattern.compile(
            "(?iU)\\b(hà\\s+nội|hn|mỹ\\s+đình|bích\\s+câu|long\\s+biên)\\b"
    );

    // =========================================================================
    // AUXILIARY TOKEN FILTERING PATTERNS
    // =========================================================================

    /**
     * Matches auxiliary context preceding a price token: deposits, booking holds,
     * vouchers, discounts, tips, and surcharges.
     */
    public static final Pattern AUXILIARY_PREFIX_PATTERN = Pattern.compile(
            "(?isU)(?:đặt\\s+cọc|dat\\s+coc|tiền\\s+cọc|tien\\s+coc|cọc|coc|"
            + "giữ\\s+(?:chỗ|cho|lịch|lich|phòng|phong|suất|suat)|"
            + "voucher(?:\\s+(?:giảm|giam|trị\\s+giá|tri\\s+gia))?|phiếu\\s+giảm|phieu\\s+giam|"
            + "giảm(?:\\s+(?:ngay|trực\\s+tiếp|truc\\s+tiep|thêm|them))?|giam(?:\\s+(?:ngay|truc\\s+tiep|them))?|"
            + "chiết\\s+khấu|chiet\\s+khau|ưu\\s+đãi|uu\\s+dai|bớt|bot|"
            + "phụ\\s+thu|phu\\s+thu|phụ\\s+phí|phu\\s+phi|"
            + "tiền\\s+tip|tien\\s+tip|tip|trả\\s+trước|tra\\s+truoc)"
            + "(?:\\s+(?:trước|truoc|tầm|tam|khoảng|khoang|là|la|:))?"
            + "[^\\d\\p{L}]{0,10}$"
    );

    /**
     * Matches auxiliary context immediately following a price token:
     * e.g. "50k tiền cọc", "50k giữ chỗ", "50k tiền tip".
     * Excludes sentence delimiters (commas, periods, semicolons) to avoid matching across clauses.
     */
    public static final Pattern AUXILIARY_SUFFIX_PATTERN = Pattern.compile(
            "^[ \\t\\-]{0,5}(?:giữ\\s+(?:chỗ|cho|lịch|lich|phòng|phong|suất|suat)|"
            + "tiền\\s+cọc|tien\\s+coc|đặt\\s+cọc|dat\\s+coc|cọc|coc|"
            + "tiền\\s+tip|tien\\s+tip|tip|voucher|phụ\\s+thu|phu\\s+thu)"
    );

    // =========================================================================
    // AUTHORITATIVE CATALOG TIERS (PAGE 'NÀNG SEN')
    // =========================================================================

    private static final List<PriceTier> HANOI_LADDER = List.of(
            new PriceTier(399_000L, "Gói Massage cơ bản (NS01)", "60 phút", "hanoi", "NS01", "MASSAGE"),
            new PriceTier(239_000L, "Gói Dưỡng sinh cơ bản (DS02)", "60 phút", "hanoi", "DS02", "DUONG_SINH"),
            new PriceTier(129_000L, "Gói Lấy ráy tai cơ bản (DS01)", "30 phút", "hanoi", "DS01", "DUONG_SINH")
    );

    private static final List<PriceTier> SAIGON_LADDER = List.of(
            new PriceTier(570_000L, "Gói Massage cơ bản (NS01)", "70 phút", "saigon", "NS01", "MASSAGE"),
            new PriceTier(260_000L, "Gói Dưỡng sinh cơ bản (DS02)", "60 phút", "saigon", "DS02", "DUONG_SINH"),
            new PriceTier(129_000L, "Gói Lấy ráy tai cơ bản (DS01)", "30 phút", "saigon", "DS01", "DUONG_SINH")
    );

    public static final long CATALOG_FLOOR_PRICE = 129_000L;
    public static final String DEFAULT_PROMO_POLICY = "Giảm 10% khi mua liệu trình từ 5 buổi hoặc từ 5 voucher";

    // =========================================================================
    // CORE EVALUATION PIPELINE
    // =========================================================================

    /**
     * Evaluates conversation state and determines down-sell step, catalog floor, and promo attachment.
     */
    public static PriceLadderResult evaluate(
            String customerMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            List<ReciprocalRankFusion.FusedChunk> chunks,
            String explicitBranch
    ) {
        String msg = customerMessage != null ? customerMessage.strip() : "";
        boolean isObjection = isPriceObjection(msg);
        boolean isTrial = isTrialRequest(msg);

        // Precedence: If customer message combines trial and price objection ("muosn thử nhưng cao the"),
        // objection takes precedence to step down to cheaper entry packages.
        if (isObjection) {
            isTrial = false;
        }

        // 1. Extract previous price quoted by assistant across conversation history
        Long previousPriceQuoted = extractPreviousQuotedPrice(history);

        // 2. Resolve branch context (Hanoi vs Saigon)
        String branch = resolveBranch(msg, history, explicitBranch);

        // 3. Resolve catalog ladder for active branch
        List<PriceTier> ladder = getLadderForBranch(branch);

        // 4. Case: Trial Request ("tôi muosn thử trải nghiệm trước")
        if (isTrial) {
            long targetPrice = previousPriceQuoted != null ? previousPriceQuoted : ladder.get(0).price();
            PriceTier tier = findTierByPrice(ladder, targetPrice);
            String pkgName = tier != null ? tier.packageName() : "Gói Massage cơ bản (399.000 VNĐ)";
            String rationale = "Khách muốn thử trải nghiệm: duy trì nhất quán mức giá gói " + formatPrice(targetPrice)
                    + " đã tư vấn ở lượt trước, không nhảy giá, điều hướng chốt lịch hẹn.";
            return new PriceLadderResult(false, true, previousPriceQuoted, targetPrice, pkgName, null, false, rationale);
        }

        // 5. Case: Price Objection ("cao thế", "nhưng hơi đắt", "có gói nào rẻ hơn không")
        if (isObjection) {
            // If already at or below catalog floor (129.000 VNĐ) and customer still objects
            if (previousPriceQuoted != null && previousPriceQuoted <= CATALOG_FLOOR_PRICE) {
                String pkgName = "Gói Lấy ráy tai cơ bản (DS01 - 30 phút)";
                String promo = resolvePromoPolicy(chunks);
                String rationale = "Khách đã ở mức giá sàn danh mục (" + formatPrice(CATALOG_FLOOR_PRICE)
                        + ") và tiếp tục hỏi rẻ hơn: Khẳng định đây là gói mềm nhất bên em, áp dụng chính sách ưu đãi " + promo + ".";
                return new PriceLadderResult(true, false, previousPriceQuoted, CATALOG_FLOOR_PRICE, pkgName, promo, true, rationale);
            }

            // Monotonically step down to the next lower price tier
            PriceTier nextTier = findNextLowerTier(ladder, previousPriceQuoted);
            boolean isFloor = nextTier.price() <= CATALOG_FLOOR_PRICE;
            String promo = isFloor ? resolvePromoPolicy(chunks) : null;
            String rationale = "Khách chê giá " + (previousPriceQuoted != null ? formatPrice(previousPriceQuoted) : "hiện tại")
                    + " cao: Hạ bậc (down-sell) xuống " + nextTier.packageName() + " giá " + formatPrice(nextTier.price())
                    + (nextTier.duration() != null ? " (" + nextTier.duration() + ")" : "")
                    + " tiết kiệm hơn.";

            return new PriceLadderResult(true, false, previousPriceQuoted, nextTier.price(), nextTier.packageName(), promo, isFloor, rationale);
        }

        // 6. Neutral / Non-objection turn
        return new PriceLadderResult(false, false, previousPriceQuoted, previousPriceQuoted, null, null, false, "Không có phản hồi giá hoặc yêu cầu trải nghiệm.");
    }

    // =========================================================================
    // INTENT DETECTION HELPERS
    // =========================================================================

    public static boolean isPriceObjection(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return PRICE_OBJECTION_PATTERN.matcher(text).matches();
    }

    public static boolean isTrialRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return TRIAL_REQUEST_PATTERN.matcher(text).matches();
    }

    // =========================================================================
    // HISTORY PRICE EXTRACTION & AUXILIARY PRUNING
    // =========================================================================

    /**
     * Extracts the most recently quoted package price by the assistant in conversation history.
     * Prunes auxiliary deposit and voucher tokens to guarantee monotonic down-sell ($P_{t+1} \le P_t$).
     */
    public static Long extractPreviousQuotedPrice(List<FanpageBotRequest.ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }

        // Iterate backwards from the most recent assistant turn
        for (int i = history.size() - 1; i >= 0; i--) {
            FanpageBotRequest.ConversationTurn turn = history.get(i);
            if (turn != null && "assistant".equalsIgnoreCase(turn.role()) && turn.content() != null) {
                List<Long> prices = extractPricesFromText(turn.content());
                if (!prices.isEmpty()) {
                    // Secondary defense: prioritize prices meeting or exceeding catalog floor
                    List<Long> packagePrices = prices.stream()
                            .filter(p -> p >= CATALOG_FLOOR_PRICE)
                            .toList();

                    if (!packagePrices.isEmpty()) {
                        // In consultative sales, when an assistant introduces an entry package (e.g. "khởi điểm từ 399.000VNĐ"),
                        // the lowest entry package price quoted in that turn is the anchor price presented to the customer.
                        return Collections.min(packagePrices);
                    }
                    // If all prices in this turn were sub-floor, do not treat as package quote; continue backwards
                }
            }
        }
        return null;
    }

    /**
     * Extracts all valid VND package prices from text, normalizing '399.000', '239k', etc.,
     * while filtering out auxiliary deposit, voucher, and surcharge tokens.
     */
    public static List<Long> extractPricesFromText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<Long> prices = new LinkedHashSet<>();
        Matcher m = EXTRACT_VND_PATTERN.matcher(text);
        while (m.find()) {
            String token = m.group(1).toLowerCase(Locale.ROOT);
            try {
                long val;
                if (token.endsWith("k")) {
                    val = Long.parseLong(token.substring(0, token.length() - 1)) * 1000L;
                } else {
                    val = Long.parseLong(token.replace(".", "").replace(",", ""));
                }
                if (val >= 50_000L && val <= 50_000_000L) {
                    if (!isAuxiliaryPriceToken(val, text, m.start(), m.end())) {
                        prices.add(val);
                    }
                }
            } catch (NumberFormatException ignored) {}
        }
        return new ArrayList<>(prices);
    }

    /**
     * Determines whether an extracted price token is an auxiliary amount (deposit, voucher, surcharge)
     * rather than an authoritative service package price.
     */
    public static boolean isAuxiliaryPriceToken(long val, String text, int start, int end) {
        if (text == null || text.isBlank()) {
            return false;
        }

        // 1. Contextual prefix check (preceded by deposit, hold, voucher, discount, tip keywords)
        int prefixStart = Math.max(0, start - 40);
        String prefix = text.substring(prefixStart, start);
        if (AUXILIARY_PREFIX_PATTERN.matcher(prefix).find()) {
            return true;
        }

        // 2. Contextual suffix check (followed by hold, deposit, tip keywords without clause punctuation)
        int suffixEnd = Math.min(text.length(), end + 25);
        String suffix = text.substring(end, suffixEnd);
        if (AUXILIARY_SUFFIX_PATTERN.matcher(suffix).find()) {
            return true;
        }

        // 3. Sub-floor pruning: in Page 'Nàng sen', any amount strictly below CATALOG_FLOOR_PRICE (129.000 VNĐ)
        // is not a standalone catalog package and must be treated as an auxiliary amount.
        return val < CATALOG_FLOOR_PRICE;
    }

    // =========================================================================
    // LADDER & BRANCH RESOLUTION
    // =========================================================================

    public static String resolveBranch(
            String customerMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            String explicitBranch
    ) {
        // 1. Explicit branch parameter takes highest priority
        if (explicitBranch != null && !explicitBranch.isBlank()) {
            String b = explicitBranch.toLowerCase(Locale.ROOT);
            if (b.contains("saigon") || b.contains("quan_1") || b.contains("hcm")) {
                return "saigon";
            }
            return "hanoi";
        }

        // 2. Active customer message takes precedence over conversation history
        String branchFromMsg = resolveBranchFromText(customerMessage);
        if (branchFromMsg != null) {
            return branchFromMsg;
        }

        // 3. Symmetrical & customer-focused conversation history scan
        if (history != null) {
            // 3a. Prioritize customer ("user") turns backwards (customer preference is authoritative)
            for (int i = history.size() - 1; i >= 0; i--) {
                FanpageBotRequest.ConversationTurn turn = history.get(i);
                if (turn != null && "user".equalsIgnoreCase(turn.role()) && turn.content() != null) {
                    String branchFromHistory = resolveBranchFromText(turn.content());
                    if (branchFromHistory != null) {
                        return branchFromHistory;
                    }
                }
            }
            // 3b. Scan assistant messages backwards, but strictly IGNORE assistant turns that mention
            // BOTH branches (such as the standard multi-branch discovery template: "Hà Nội hoặc Quận 1 Sài Gòn")
            for (int i = history.size() - 1; i >= 0; i--) {
                FanpageBotRequest.ConversationTurn turn = history.get(i);
                if (turn != null && turn.content() != null) {
                    int lastSaigon = findLastMatchIndex(BRANCH_SAIGON_PATTERN, turn.content());
                    int lastHanoi = findLastMatchIndex(BRANCH_HANOI_PATTERN, turn.content());
                    if (lastSaigon >= 0 && lastHanoi >= 0) {
                        // Mentions both branches -> comparative overview, not a customer branch decision
                        continue;
                    }
                    if (lastSaigon >= 0) {
                        return "saigon";
                    }
                    if (lastHanoi >= 0) {
                        return "hanoi";
                    }
                }
            }
        }

        // 4. Default branch fallback
        return "hanoi";
    }

    /**
     * Symmetrically resolves branch ("saigon" or "hanoi") from text.
     * If both branches are mentioned, the latest mention (highest match start index) wins.
     * Returns null if neither branch is detected.
     */
    public static String resolveBranchFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        int lastSaigon = findLastMatchIndex(BRANCH_SAIGON_PATTERN, text);
        int lastHanoi = findLastMatchIndex(BRANCH_HANOI_PATTERN, text);

        if (lastSaigon >= 0 && lastHanoi >= 0) {
            return lastSaigon > lastHanoi ? "saigon" : "hanoi";
        }
        if (lastSaigon >= 0) {
            return "saigon";
        }
        if (lastHanoi >= 0) {
            return "hanoi";
        }
        return null;
    }

    private static int findLastMatchIndex(Pattern pattern, String text) {
        if (text == null) {
            return -1;
        }
        Matcher matcher = pattern.matcher(text);
        int lastIndex = -1;
        while (matcher.find()) {
            lastIndex = matcher.start();
        }
        return lastIndex;
    }

    public static List<PriceTier> getLadderForBranch(String branch) {
        if ("saigon".equalsIgnoreCase(branch)) {
            return SAIGON_LADDER;
        }
        return HANOI_LADDER;
    }

    public static PriceTier findTierByPrice(List<PriceTier> ladder, long price) {
        if (ladder == null) return null;
        for (PriceTier tier : ladder) {
            if (tier.price() == price) {
                return tier;
            }
        }
        return null;
    }

    /**
     * Finds the next lower price tier strictly below previousPriceQuoted.
     * Guarantees monotonic down-sell progression (never returns a higher price).
     */
    public static PriceTier findNextLowerTier(List<PriceTier> ladder, Long previousPriceQuoted) {
        if (ladder == null || ladder.isEmpty()) {
            return new PriceTier(CATALOG_FLOOR_PRICE, "Gói Lấy ráy tai cơ bản (DS01)", "30 phút", "all", "DS01", "DUONG_SINH");
        }

        List<PriceTier> sorted = new ArrayList<>(ladder);
        sorted.sort(Comparator.comparingLong(PriceTier::price).reversed()); // Descending order

        if (previousPriceQuoted == null) {
            // If customer asks for cheaper without previous price, recommend the second tier (entry down-sell)
            return sorted.size() > 1 ? sorted.get(1) : sorted.get(0);
        }

        for (PriceTier tier : sorted) {
            if (tier.price() < previousPriceQuoted) {
                return tier;
            }
        }

        // If no strictly lower tier found, return the catalog floor
        return sorted.get(sorted.size() - 1);
    }

    public static String resolvePromoPolicy(List<ReciprocalRankFusion.FusedChunk> chunks) {
        if (chunks != null) {
            for (ReciprocalRankFusion.FusedChunk fc : chunks) {
                if (fc != null && fc.chunk() != null && fc.chunk().text() != null) {
                    String t = fc.chunk().text().toLowerCase(Locale.ROOT);
                    if (t.contains("giảm 10%") && (t.contains("5 buổi") || t.contains("5 voucher"))) {
                        return "Giảm 10% khi mua liệu trình từ 5 buổi hoặc từ 5 voucher";
                    }
                }
            }
        }
        return DEFAULT_PROMO_POLICY;
    }

    public static String formatPrice(long price) {
        return String.format(Locale.ROOT, "%,d VNĐ", price).replace(',', '.');
    }
}
