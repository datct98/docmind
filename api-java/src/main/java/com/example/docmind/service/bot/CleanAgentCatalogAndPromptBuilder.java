package com.example.docmind.service.bot;

import com.docmind.guardrail.BubbleCapHook;
import com.docmind.guardrail.ReplyContractValidator;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.service.metadata.ChunkMetadataExtractor;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;

import java.util.ArrayList;
import java.util. Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chuyên trách Đầu vào của CLEAN_AGENT_MODE:
 * Xếp hạng & Phân tầng Catalog theo phạm vi vùng chăm sóc (Scope-Grounded Tiered Catalog),
 * Lọc RAG Chunk theo gói đang hiển thị (Scope-Matched Chunk Filtering) & Dựng Prompt.
 */
public final class CleanAgentCatalogAndPromptBuilder {

    static final String CLEAN_AGENT_SYSTEM_PROMPT_TEMPLATE = """
            Bạn là {bot_name} — chuyên viên tư vấn tinh tế, khéo léo, hoạt ngôn và giàu sự sẻ chia của {page_name}.
            Phong cách thương hiệu của Page: {persona_style_context}
            Quy tắc xưng hô bất biến: Bạn LUÔN tự xưng là "{self_pronoun}" và LUÔN gọi khách hàng là "{customer_pronoun}".
            - Tuyệt đối KHÔNG BAO GIỜ tự xưng là "mình" (viết "Dạ mình hiểu ạ" hay "Dạ mình đã hiểu rồi ạ" là SAI).
            - Tuyệt đối KHÔNG BAO GIỜ nói trống không thiếu chủ ngữ (viết "Dạ hiểu rồi ạ" là SAI).

            I. NGHỆ THUẬT GIAO TIẾP CÓ "HỒN" & NHỊP ĐỘ CẢM XÚC (EMOTIONAL PACING):
            1. Đúng nhịp độ — Không biến mọi lượt chat thành văn mẫu quảng cáo:
               - Ở lượt Khám phá nhu cầu, lượt Trả lời câu hỏi vặn/góp ý của khách, lượt Khách xin tạm dừng/đang bận, và lượt Hỏi giờ đặt lịch: Hãy trò chuyện ngắn gọn, tự nhiên, nhanh nhẹn như hai người đang nhắn tin trực tiếp (không chèn các câu quảng cáo dài dòng).
               - Chỉ bung "Đòn Neo Cảm Xúc & Tôn Vinh Tay Nghề/Trải Nghiệm" ở đúng 2 thời điểm vàng: (a) Khi đề xuất gói lần đầu tiên, và (b) Khi khách băn khoăn về giá lần thứ nhất (`priceObjectionCount == 1`).
            2. Bán cảm xúc và niềm tin nhưng trung thực 100% về phân hạng dịch vụ:
               - Hãy dùng ngôn từ giàu sức gợi về chất lượng chuyên môn, sự chăm sóc tỉ mỉ và cảm giác dễ chịu/hiệu quả thực tế mà khách sẽ nhận được.
               - TUYỆT ĐỐI TRUNG THỰC VỚI HẠNG MỤC VẬT CHẤT CỦA TỪNG GÓI: Mỗi gói trong <SCOPE_FILTERED_CATALOG> có mục `Không gian/Hình thức` và `Quyền lợi đi kèm` riêng. Hãy dùng từ ngữ ấm áp để làm nổi bật ĐÚNG không gian và quyền lợi có thật của gói đang tư vấn (tuyệt đối không tự ý nâng khống không gian tiêu chuẩn thành "phòng riêng/VIP", không tự ý phóng đại tên quyền lợi vượt quá hồ sơ gói, và không lấy quyền lợi của gói cao cấp đắp sang gói tiết kiệm).

            II. PHẢN XẠ TINH TẾ THEO TỪNG TÌNH HUỐNG TÂM LÝ:
            1. Khi khách mới nêu vấn đề ngắn gọn (chưa rõ thời gian/bối cảnh & chưa hỏi khám phá lần nào):
               - Không làm "thầy bói" tự suy diễn nguyên nhân khi khách chưa kể.
               - Chia sẻ tự nhiên với sự bất tiện/khó chịu mà vấn đề đó gây ra cho sinh hoạt của khách, rồi hỏi 1 câu đơn giản, nhẹ nhàng để làm rõ thời gian hoặc bối cảnh xuất hiện. (Khi <STRUCTURED_CONVERSATION_STATE> báo đã hỏi khám phá rồi thì CẤM hỏi lặp lại).

            2. Khi khách hỏi vặn / trêu đùa ("Sao biết hay vậy?", "Đoán mò à?") hoặc góp ý cách nói chuyện:
               - Đáp lại trực diện, hóm hỉnh và chân thành ngay câu đầu (ví dụ nhận ngay là lúc nãy {self_pronoun} suy đoán hơi vội trước khi hỏi kỹ {customer_pronoun}), tuyệt đối không lảng tránh hay chép lại bảng giá.

            3. Khi đã rõ tình trạng -> Chủ động đề xuất gói & Báo giá lần đầu (`priceObjectionCount == 0`):
               - Khi khách chưa từng nhắc tên gói (`heldPkg == null`), bạn là người CHỦ ĐỘNG GỢI Ý gói số 1 trong `[NHÓM 1 — GÓI CHỦ LỰC]` (dùng cụm "{self_pronoun} gợi ý {customer_pronoun} tham khảo...", CẤM nói "{customer_pronoun} đang quan tâm đến gói...").
               - Tách 2–3 bong bóng ngắn gọn, tự nhiên:
                 + Bong bóng 1: Kết nối tự nhiên tình trạng của khách với lý do chuyên môn vì sao gói số 1 này là phù hợp nhất, nêu rõ tên gói, thời lượng và mức giá trọn gói (kết thúc bằng dấu ".").
                 + Bong bóng 2: Gợi cảm nhận hấp dẫn về quy trình chăm sóc + đúng không gian & quyền lợi đi kèm của gói đó trong <SCOPE_FILTERED_CATALOG> (kết thúc bằng dấu ".").
                 + Bong bóng 3 (hoặc câu hỏi độc lập ở cuối): Khép lại bằng 1 câu hỏi mở nhẹ nhàng mời khách trải nghiệm thử (không hỏi câu vô nghĩa "thử 1 buổi lẻ hay đặt gói trọn vẹn" khi giá niêm yết vốn là giá 1 buổi).

            4. Khi khách chê đắt / băn khoăn về giá LẦN ĐẦU TIÊN (`priceObjectionCount == 1` và khách chưa xin gói rẻ hơn / chưa nêu ngân sách thấp):
               - QUY TẮC VÀNG: Giữ nguyên gói đang tư vấn! TUYỆT ĐỐI CẤM hạ gói và CẤM tự gạ "hay để em gợi ý gói tiết kiệm hơn nhé?". CẤM mở đầu bằng câu sáo rỗng "Dạ em hiểu mình đang cân nhắc ạ".
               - Áp dụng nghệ thuật [NEO CẢM XÚC -> TRAO NIỀM TIN -> KHƠI SỰ TÒ MÒ] trong 2–3 bong bóng ngắn (20–35 từ/bong bóng):
                 + Bong bóng 1 (Trao niềm tin vào chiều sâu chuyên môn): Giải thích khéo léo rằng giá trị của gói nằm ở trọn vẹn thời lượng chuyên viên lành nghề tập trung xử lý sâu từng điểm đang có vấn đề chứ không chỉ làm qua loa bề mặt, mang lại sự thay đổi cảm nhận rõ rệt ngay từ lần đầu.
                 + Bong bóng 2 (Trải nghiệm trọn vẹn): Điểm lại đúng không gian và quyền lợi chu đáo đi kèm của gói đó (không phát sinh chi phí phụ, kết thúc bằng dấu ".").
                 + Bong bóng 3 (Khơi sự tò mò / Mời thử): Tách câu hỏi mời khách thử trải nghiệm trước 1 lần để tự mình kiểm chứng chất lượng thành bong bóng độc lập ở cuối (kết thúc bằng dấu "?").

            5. Khi khách chê đắt LẦN THỨ HAI (`priceObjectionCount >= 2`) HOẶC khách chủ động xin gói rẻ hơn / hỏi mức ngân sách thấp:
               - Nếu khách hỏi một mức giá thấp chưa tồn tại trong <SCOPE_FILTERED_CATALOG>, giải thích nhẹ nhàng rằng hiện bên {self_pronoun} chưa có gói phù hợp ở mức giá đó, đối chiếu với phương án tiết kiệm nhất phù hợp trong <SCOPE_FILTERED_CATALOG> và để khách thoải mái cân nhắc; TUYỆT ĐỐI KHÔNG tự động chuyển Handoff (`"should_handoff": false`) chỉ vì khách hỏi một mức giá thấp chưa tồn tại.
               - Chỉ đề xuất gói phù hợp với nhu cầu và vùng khách đang hỏi trong <SCOPE_FILTERED_CATALOG> (tuyệt đối không đề xuất dịch vụ lệch vùng/lệch nhu cầu chỉ vì giá rẻ hơn).
               - Nếu trong `[NHÓM 2 — PHƯƠNG ÁN TIẾT KIỆM / HẠ BẬC]` có gói phù hợp: BẮT BUỘC giới thiệu phương án tiết kiệm trong `[NHÓM 2]` kèm ĐÚNG tên gói, mức giá, không gian, quyền lợi và giải thích trung thực rằng đây là lựa chọn tiết kiệm nhất **trong nhóm dịch vụ phù hợp với tình trạng của khách** (TUYỆT ĐỐI CẤM tự khẳng định gói Nhóm 2 là "mức thấp nhất toàn hệ thống", CẤM khẳng định gói Nhóm 1 là "mức giá thấp nhất bên em có", và CẤM chê bai mức giá thấp hơn gói Nhóm 1 là "chỉ làm được thư giãn hời hợt / qua loa bề mặt").
               - Chỉ khi `[NHÓM 2]` trống hoàn toàn mới chia sẻ chân thành vì sao tình trạng của khách cần đúng gói tiêu chuẩn hiện tại để đảm bảo hiệu quả chăm sóc.

            6. Khi khách lưỡng lự / xin suy nghĩ thêm (`HESITATION_OR_DEFERRAL`) hoặc nhắn xác nhận khép lại cuộc trò chuyện (`POLITE_CLOSING_ACKNOWLEDGMENT`):
               - Trường hợp A (`STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL` — Khách lưỡng lự trong ngữ cảnh đã băn khoăn về giá và trong <SCOPE_FILTERED_CATALOG> có sẵn gói `[NHÓM 2 — PHƯƠNG ÁN TIẾT KIỆM]` chưa được giới thiệu):
                 + Trả lời trong 2 bong bóng ngắn (20–32 từ/bong bóng):
                   * Bong bóng 1 (Giải tỏa áp lực): Nhẹ nhàng trấn an khách cứ thoải mái tham khảo thêm, chưa cần vội đặt lịch ngay.
                   * Bong bóng 2 (Mở lối thoát mềm không ép buộc): Nhắc nhẹ nếu khách muốn thử một phương án nhẹ nhàng, tiết kiệm hơn thì bên Page cũng có gói Nhóm 2 (nêu đúng tên gói + giá trọn gói + nêu rõ phạm vi vùng chăm sóc của gói Nhóm 2 nếu hẹp hơn gói Nhóm 1), khi nào tiện cứ nhắn {self_pronoun} để so sánh chi tiết.
               - Trường hợp B (`STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE` — Khách đang bận việc, hẹn tối/hôm khác xem lại, `priceObjectionCount == 0`, hoặc khách nhắn `"Ok"`, `"Ừ"`, `"Vâng"` để khép lại cuộc trò chuyện sau khi đã hẹn suy nghĩ thêm):
                 + Tôn trọng hoàn toàn nhịp độ của khách — TUYỆT ĐỐI KHÔNG cố nhồi thêm gói mới và TUYỆT ĐỐI CẤM hỏi ép giờ đặt lịch!
                 + Tách lời chào tạm biệt thành 2 bong bóng ngắn gọn, giàu cảm xúc:
                   * Bong bóng 1 (Ghi nhận & Hẹn gặp lại): Xác nhận {self_pronoun} đã lưu sẵn thông tin gói và các lưu ý chăm sóc (`care_notes`) của {customer_pronoun} để bất cứ khi nào {customer_pronoun} thu xếp được thời gian cần hỗ trợ cứ nhắn {self_pronoun} ngay (kết thúc bằng dấu ".").
                   * Bong bóng 2 (Lời chúc ấm áp tách riêng): Câu chúc {customer_pronoun} một ngày/buổi tối thật nhiều niềm vui, nhẹ nhàng và thư thái (ví dụ: "Chúc {customer_pronoun} một ngày thật nhiều niềm vui và thư thái ạ!").

            7. Khi khách hỏi xác nhận chi tiết ("là 1 buổi à"), đồng thuận muốn thử (`STAGE_4_POSITIVE_TRIAL_OR_BOOKING`), hoặc xác nhận sau khi đã chốt lịch (`POST_BOOKING_CLOSING_ACK`):
               - Nếu khách hỏi xác nhận hoặc muốn nghe chi tiết gói (`SERVICE_OR_UNIT_DETAIL_QUESTION`): Trả lời trực diện, rõ ràng đúng thông tin gói trong <SCOPE_FILTERED_CATALOG>, không thúc ép chốt giờ.
               - Khi khách đồng thuận muốn thử (`STAGE_4_POSITIVE_TRIAL_OR_BOOKING`): Chốt thẳng vào gói vừa trao đổi gần nhất (nếu vừa giới thiệu gói Nhóm 2 hoặc khách chọn gói Nhóm 2 thì chốt thẳng gói Nhóm 2 đó, CẤM hỏi ngược bắt khách chọn lại giữa 2 gói!), khéo léo nhắc lại lưu ý chăm sóc trong `care_notes` của <AGENT_BRAIN_INSIGHT> theo đúng trạng thái thực tế: nếu chưa chốt giờ hẹn (`booking_funnel_status != "CONFIRMED"`) thì nói "{self_pronoun} lưu lại lưu ý này để khi {customer_pronoun} ghé, chuyên viên chuẩn bị chu đáo" (TUYỆT ĐỐI CẤM nói quá khứ "{self_pronoun} đã dặn chuyên viên" khi khách chưa chốt lịch!), và hỏi gọn gàng xem khách tiện ghé vào khung giờ nào để chuẩn bị chu đáo.
               - Khi lịch hẹn đã xác nhận (`POST_BOOKING_CLOSING_ACK` / `booking_funnel_status == "CONFIRMED"`): Cảm ơn ấm áp và hẹn đón tiếp {customer_pronoun} đúng khung giờ đã đặt, CẤM hỏi lại khung giờ đặt lịch.

            III. ĐỊNH DẠNG ĐẦU RA (BẮT BUỘC):
            - Trả về duy nhất JSON hợp lệ gồm `"reply_bubbles"` (đặt đầu tiên) và `"brain_reflection"` (đặt ngay sau):
              `{"reply_bubbles": [{"text": "...", "typing_delay_ms": 0}], "brain_reflection": {"observed_fact": "Sự thật mới khách vừa trực tiếp chia sẻ ở lượt này (để rỗng \"\" nếu không có)", "care_note_delta": "Lưu ý chăm sóc dựa trên điều khách thực sự chia sẻ (để rỗng \"\" nếu không có; CẤM tự bịa chỉ định điều trị ngoài quy trình gói)", "working_hypothesis": "Giả thuyết ngắn có căn cứ về băn khoăn hoặc mong muốn hiện tại của khách", "intended_speech_act": "RESPECTED_PAUSE_WITH_SOFT_OPTION | OFFERED_PACKAGE_DETAILS | INVITED_TRIAL_OR_ASKED_TIME | BOOKING_CONFIRMED_ACK | GENERAL_CONSULTATION", "detected_customer_intent": "PRICE_OBJECTION | PRICE_HESITATION | GENERAL_DEFER_OR_BUSY | PRICE_OR_DETAIL_INQUIRY | SYMPTOM_OR_CONTEXT_SHARING | BOOKING_OR_TRIAL_ACCEPTANCE | POLITE_CLOSING_ACK"}, "should_handoff": false}`.
            - Tối đa 1–3 bong bóng ngắn gọn, tự nhiên mỗi lượt (15–38 từ/bong bóng), không lặp lại câu chữ của lượt trước. Câu trần thuật kết thúc bằng dấu ".", chỉ câu hỏi thực sự ở cuối lượt mới dùng dấu "?".
            """;

    private CleanAgentCatalogAndPromptBuilder() {
    }

    public record TieredCatalogResult(
            List<PackageCatalogService.PackageSummary> primaryRecommendations,
            List<PackageCatalogService.PackageSummary> economicalAlternatives,
            List<PackageCatalogService.PackageSummary> excludedCatalog,
            boolean noMatchingPackageForRequestedScope
    ) {
        public List<PackageCatalogService.PackageSummary> allAllowedPackages() {
            List<PackageCatalogService.PackageSummary> combined = new ArrayList<>(
                    (primaryRecommendations != null ? primaryRecommendations.size() : 0)
                    + (economicalAlternatives != null ? economicalAlternatives.size() : 0)
            );
            if (primaryRecommendations != null) {
                combined.addAll(primaryRecommendations);
            }
            if (economicalAlternatives != null) {
                combined.addAll(economicalAlternatives);
            }
            return combined;
        }
    }

    public static TieredCatalogResult rankAndTierCatalogForCleanAgent(
            List<PackageCatalogService.PackageSummary> allCatalog,
            List<String> knownSymptoms,
            List<String> knownBodyAreas,
            Set<String> rejectedPackages,
            boolean isMismatch
    ) {
        if (allCatalog == null || allCatalog.isEmpty()) {
            return new TieredCatalogResult(List.of(), List.of(), List.of(), false);
        }
        List<String> safeAreas = knownBodyAreas != null ? knownBodyAreas : List.of();
        List<String> safeSymptoms = knownSymptoms != null ? knownSymptoms : List.of();
        Set<String> safeRejected = rejectedPackages != null ? rejectedPackages : Set.of();

        Set<String> requestedScopes = new LinkedHashSet<>();
        for (String area : safeAreas) {
            if (area != null && !area.isBlank()) {
                requestedScopes.add(area.strip().toLowerCase(Locale.ROOT));
            }
        }
        for (String sym : safeSymptoms) {
            if (sym == null || sym.isBlank()) continue;
            for (String extracted : ConversationState.extractTargetScopes(sym, List.of())) {
                if (extracted != null && !extracted.isBlank()) {
                    requestedScopes.add(extracted.strip().toLowerCase(Locale.ROOT));
                }
            }
        }

        boolean hasSpecificNeed = !requestedScopes.isEmpty() || !safeSymptoms.isEmpty();
        List<PackageCatalogService.PackageSummary> outExcluded = new ArrayList<>();
        List<PackageCatalogService.PackageSummary> candidates = new ArrayList<>();
        Map<String, Integer> directHitsByPkgKey = new LinkedHashMap<>();
        int maxDirectHits = 0;

        for (PackageCatalogService.PackageSummary pkg : allCatalog) {
            if (pkg == null || pkg.isAddon()) {
                continue;
            }
            String dispLower = pkg.displayName() != null ? pkg.displayName().toLowerCase(Locale.ROOT) : "";
            String canonCode = ConversationState.toCanonicalPackageCode(
                    dispLower,
                    pkg.durationMinutes() != null ? String.valueOf(pkg.durationMinutes()) : null
            );

            if (!safeRejected.isEmpty()) {
                boolean matchedRejected = safeRejected.stream()
                        .filter(Objects::nonNull)
                        .map(r -> r.toLowerCase(Locale.ROOT))
                        .anyMatch(r -> (!r.isBlank() && dispLower.contains(r))
                                || (canonCode != null && r.equals(canonCode.toLowerCase(Locale.ROOT)))
                                || (pkg.code() != null && r.equalsIgnoreCase(pkg.code())));
                if (matchedRejected) {
                    outExcluded.add(pkg);
                    continue;
                }
            }

            int hits = computePackageScopeDirectHits(pkg, requestedScopes, safeSymptoms);
            String pkgKey = pkgUniqueKey(pkg);
            directHitsByPkgKey.put(pkgKey, hits);
            if (hits > maxDirectHits) {
                maxDirectHits = hits;
            }
            candidates.add(pkg);
        }

        if (hasSpecificNeed && maxDirectHits == 0) {
            outExcluded.addAll(candidates);
            return new TieredCatalogResult(List.of(), List.of(), outExcluded, true);
        }

        boolean hasLimbCrampOnlyWithoutNeckShoulder = safeSymptoms.stream()
                .anyMatch(s -> s != null && s.toLowerCase(Locale.ROOT).contains("chuột rút"))
                && requestedScopes.stream().noneMatch(sc -> sc.contains("cổ") || sc.contains("vai") || sc.contains("gáy"));
        List<PackageCatalogService.PackageSummary> matchedPackages = new ArrayList<>();
        List<PackageCatalogService.PackageSummary> partialScopePackages = new ArrayList<>();
        for (PackageCatalogService.PackageSummary pkg : candidates) {
            int hits = directHitsByPkgKey.getOrDefault(pkgUniqueKey(pkg), 0);
            if (hasSpecificNeed && hits <= 0) {
                outExcluded.add(pkg);
            } else if (hasSpecificNeed && hits < maxDirectHits) {
                if (hasLimbCrampOnlyWithoutNeckShoulder) {
                    outExcluded.add(pkg);
                } else {
                    partialScopePackages.add(pkg);
                }
            } else {
                matchedPackages.add(pkg);
            }
        }

        if (matchedPackages.isEmpty()) {
            return new TieredCatalogResult(List.of(), List.of(), outExcluded, hasSpecificNeed);
        }

        int maxBreadth = matchedPackages.stream()
                .mapToInt(p -> p.bodyAreas() != null ? p.bodyAreas().size() : 0)
                .max()
                .orElse(0);
        int minBreadth = matchedPackages.stream()
                .mapToInt(p -> p.bodyAreas() != null ? p.bodyAreas().size() : 0)
                .min()
                .orElse(0);

        List<PackageCatalogService.PackageSummary> primaryGroup = new ArrayList<>();
        List<PackageCatalogService.PackageSummary> secondaryCandidates = new ArrayList<>();

        for (PackageCatalogService.PackageSummary pkg : matchedPackages) {
            if (isPrimaryRecommendationCandidate(pkg, requestedScopes, maxBreadth, minBreadth)) {
                primaryGroup.add(pkg);
            } else {
                secondaryCandidates.add(pkg);
            }
        }

        if (primaryGroup.isEmpty()) {
            primaryGroup.addAll(matchedPackages);
            secondaryCandidates.clear();
        }
        secondaryCandidates.addAll(partialScopePackages);

        primaryGroup.sort(
                Comparator
                        .<PackageCatalogService.PackageSummary>comparingInt(p -> Math.abs((p.durationMinutes() != null && p.durationMinutes() > 0 ? p.durationMinutes() : 60) - 60))
                        .thenComparingLong(PackageCatalogService.PackageSummary::priceVnd)
                        .thenComparingInt(p -> -(p.bodyAreas() != null ? p.bodyAreas().size() : 0))
        );

        long minPrimaryPrice = primaryGroup.stream()
                .mapToLong(PackageCatalogService.PackageSummary::priceVnd)
                .min()
                .orElse(Long.MAX_VALUE);

        List<PackageCatalogService.PackageSummary> economicalGroup = new ArrayList<>();
        for (PackageCatalogService.PackageSummary sec : secondaryCandidates) {
            if (sec.priceVnd() < minPrimaryPrice) {
                economicalGroup.add(sec);
            } else {
                outExcluded.add(sec);
            }
        }
        economicalGroup.sort(Comparator.comparingLong(PackageCatalogService.PackageSummary::priceVnd));

        return new TieredCatalogResult(primaryGroup, economicalGroup, outExcluded, false);
    }

    public static void filterCatalogForCleanAgent(
            List<PackageCatalogService.PackageSummary> allCatalog,
            List<String> knownSymptoms,
            List<String> knownBodyAreas,
            Set<String> rejectedPackages,
            boolean isMismatch,
            List<PackageCatalogService.PackageSummary> outFiltered,
            List<PackageCatalogService.PackageSummary> outExcluded
    ) {
        TieredCatalogResult res = rankAndTierCatalogForCleanAgent(
                allCatalog,
                knownSymptoms,
                knownBodyAreas,
                rejectedPackages,
                isMismatch
        );
        if (outFiltered != null) {
            outFiltered.addAll(res.allAllowedPackages());
        }
        if (outExcluded != null) {
            outExcluded.addAll(res.excludedCatalog());
        }
    }

    public static int computePackageScopeDirectHits(
            PackageCatalogService.PackageSummary pkg,
            Set<String> requestedScopes,
            List<String> knownSymptoms
    ) {
        List<String> pkgAreas = pkg.bodyAreas() != null
                ? pkg.bodyAreas().stream().filter(Objects::nonNull).map(s -> s.strip().toLowerCase(Locale.ROOT)).toList()
                : List.of();
        String combinedMeta = ((pkg.displayName() != null ? pkg.displayName() : "") + " "
                + (pkg.includesSummary() != null ? pkg.includesSummary() : "") + " "
                + (pkg.processSummary() != null ? pkg.processSummary() : "") + " "
                + (pkg.suitableFor() != null ? pkg.suitableFor() : "")).toLowerCase(Locale.ROOT);
        boolean isFullBodyPkg = pkgAreas.contains("toàn thân")
                || pkgAreas.contains("body")
                || "body_therapy".equalsIgnoreCase(pkg.serviceFamily());
        Set<String> generalBodyScopes = Set.of(
                "toàn thân", "body", "đầu", "cổ", "vai", "gáy", "vai gáy", "cổ vai gáy", "bả vai",
                "lưng", "thắt lưng", "eo", "hông", "mông",
                "chân", "tay", "bắp chân", "gối", "đùi", "cánh tay", "bàn chân"
        );

        if (!requestedScopes.isEmpty()) {
            int hits = 0;
            for (String reqScope : requestedScopes) {
                boolean matchedArea = pkgAreas.stream().anyMatch(pa -> pa.equals(reqScope) || pa.contains(reqScope) || reqScope.contains(pa));
                if (!matchedArea && isFullBodyPkg && generalBodyScopes.contains(reqScope)) {
                    matchedArea = true;
                }
                if (!matchedArea && pkgAreas.isEmpty() && combinedMeta.contains(reqScope)) {
                    matchedArea = true;
                }
                if (matchedArea) {
                    hits++;
                }
            }
            return hits;
        }

        if (knownSymptoms != null && !knownSymptoms.isEmpty()) {
            int symHits = 0;
            for (String sym : knownSymptoms) {
                if (sym == null || sym.isBlank()) continue;
                String symLow = sym.strip().toLowerCase(Locale.ROOT);
                if (combinedMeta.contains(symLow) || isFullBodyPkg || pkgAreas.size() >= 4) {
                    symHits++;
                }
            }
            return symHits;
        }

        return 1;
    }

    public static String buildPartialScopeLimitationNote(
            PackageCatalogService.PackageSummary pkg,
            List<String> knownBodyAreas,
            List<String> knownSymptoms
    ) {
        if (pkg == null) {
            return "";
        }
        List<String> pkgAreas = pkg.bodyAreas() != null
                ? pkg.bodyAreas().stream().filter(Objects::nonNull).map(s -> s.strip().toLowerCase(Locale.ROOT)).toList()
                : List.of();
        if (pkgAreas.contains("toàn thân") || pkgAreas.contains("body") || "body_therapy".equalsIgnoreCase(pkg.serviceFamily())) {
            return "";
        }
        Set<String> requestedScopes = new LinkedHashSet<>();
        if (knownBodyAreas != null) {
            for (String area : knownBodyAreas) {
                if (area != null && !area.isBlank()) {
                    requestedScopes.add(area.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (knownSymptoms != null) {
            for (String sym : knownSymptoms) {
                if (sym == null || sym.isBlank()) continue;
                for (String extracted : ConversationState.extractTargetScopes(sym, List.of())) {
                    if (extracted != null && !extracted.isBlank()) {
                        requestedScopes.add(extracted.strip().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        if (requestedScopes.isEmpty()) {
            return "";
        }
        List<String> uncoveredAreas = new ArrayList<>();
        for (String req : requestedScopes) {
            boolean matched = pkgAreas.stream().anyMatch(pa -> pa.equals(req) || pa.contains(req) || req.contains(pa));
            if (!matched) {
                uncoveredAreas.add(req);
            }
        }
        if (uncoveredAreas.isEmpty()) {
            return "";
        }
        boolean customerRequestedHair = requestedScopes.contains("tóc") || requestedScopes.contains("da đầu");
        List<String> coveredDisplay = pkgAreas.stream()
                .filter(pa -> customerRequestedHair || (!"tóc".equals(pa) && !"da đầu".equals(pa)))
                .toList();
        if (coveredDisplay.isEmpty()) {
            coveredDisplay = pkgAreas;
        }
        return "(Phạm vi hẹp hơn: chỉ tập trung chăm sóc vùng " + coveredDisplay
                + ", KHÔNG bao gồm vùng " + uncoveredAreas + ")";
    }

    private static boolean isPrimaryRecommendationCandidate(
            PackageCatalogService.PackageSummary pkg,
            Set<String> requestedScopes,
            int maxBreadth,
            int minBreadth
    ) {
        List<String> pkgAreas = pkg.bodyAreas() != null
                ? pkg.bodyAreas().stream().filter(Objects::nonNull).map(s -> s.strip().toLowerCase(Locale.ROOT)).toList()
                : List.of();
        if (pkgAreas.contains("toàn thân") || pkgAreas.contains("body") || "body_therapy".equalsIgnoreCase(pkg.serviceFamily())) {
            return true;
        }
        if (maxBreadth > minBreadth && pkgAreas.size() >= maxBreadth) {
            return true;
        }
        boolean customerRequestedScalpOrHair = requestedScopes.contains("tóc") || requestedScopes.contains("da đầu");
        String incLower = pkg.includesSummary() != null ? pkg.includesSummary().toLowerCase(Locale.ROOT) : "";
        String dispLower = pkg.displayName() != null ? pkg.displayName().toLowerCase(Locale.ROOT) : "";
        boolean isHairWashCombo = !customerRequestedScalpOrHair
                && (pkgAreas.contains("tóc")
                    || pkgAreas.contains("da đầu")
                    || incLower.contains("gội")
                    || (dispLower.contains("dưỡng sinh") && !dispLower.contains("massage")));
        return !isHairWashCombo;
    }

    private static String pkgUniqueKey(PackageCatalogService.PackageSummary pkg) {
        return (pkg.packageUid() != null ? pkg.packageUid() : "")
                + "::" + (pkg.code() != null ? pkg.code() : "")
                + "::" + (pkg.displayName() != null ? pkg.displayName() : "")
                + "::" + pkg.priceVnd();
    }

    public static String resolveHeldPackageDisplay(
            String rawHeldPackage,
            String rawHeldDuration,
            java.math.BigDecimal rawHeldPrice,
            List<PackageCatalogService.PackageSummary> catalog
    ) {
        if (rawHeldPackage == null || rawHeldPackage.isBlank()) {
            return "null";
        }
        if (catalog != null && !catalog.isEmpty()) {
            if (rawHeldPrice != null && rawHeldPrice.longValue() > 0L) {
                long targetVnd = rawHeldPrice.longValue();
                for (PackageCatalogService.PackageSummary p : catalog) {
                    if (p != null && p.priceVnd() == targetVnd) {
                        return p.canonicalLabel();
                    }
                }
            }
            String disp = ConversationState.toDisplayPackageName(rawHeldPackage, rawHeldDuration);
            if (disp != null && !disp.isBlank()) {
                return disp;
            }
        }
        return ConversationState.toDisplayPackageName(rawHeldPackage, rawHeldDuration);
    }

    public static String resolveHeldPriceDisplay(
            java.math.BigDecimal rawHeldPrice,
            String rawHeldPackage,
            List<PackageCatalogService.PackageSummary> catalog
    ) {
        if (rawHeldPrice != null && rawHeldPrice.longValue() > 0L) {
            long vnd = rawHeldPrice.longValue();
            return (vnd % 1000L == 0L) ? (vnd / 1000L) + "k" : vnd + "đ";
        }
        if (rawHeldPackage != null && !rawHeldPackage.isBlank() && catalog != null) {
            String disp = ConversationState.toDisplayPackageName(rawHeldPackage, null).toLowerCase(Locale.ROOT);
            for (PackageCatalogService.PackageSummary p : catalog) {
                if (p != null && p.displayName() != null && disp.contains(p.displayName().toLowerCase(Locale.ROOT))) {
                    return p.priceDisplay();
                }
            }
        }
        return "null";
    }

    public static String resolveBotDisplayName(PromptBuilder.PagePersona pagePersona) {
        if (pagePersona != null && pagePersona.resolvedConfig() != null
                && pagePersona.resolvedConfig().openingBubbles() != null) {
            Pattern namePat = Pattern.compile("(?U)\\blà\\s+([\\p{Lu}][\\p{L}]+(?:\\s+[\\p{Lu}][\\p{L}]+){0,2})\\b");
            for (String b : pagePersona.resolvedConfig().openingBubbles()) {
                if (b == null) continue;
                Matcher m = namePat.matcher(b);
                if (m.find()) {
                    return m.group(1).strip();
                }
            }
        }
        return "Chuyên viên tư vấn";
    }

    public static String resolvePersonaStyleContext(PromptBuilder.PagePersona pagePersona) {
        if (pagePersona == null) {
            return "Thân thiện, chuyên nghiệp, tận tâm và thấu hiểu khách hàng.";
        }
        List<String> parts = new ArrayList<>();
        if (pagePersona.industry() != null && !pagePersona.industry().isBlank()) {
            parts.add("Lĩnh vực: " + pagePersona.industry().strip());
        }
        if (pagePersona.targetAudience() != null && !pagePersona.targetAudience().isBlank()) {
            parts.add("Khách hàng mục tiêu: " + pagePersona.targetAudience().strip());
        }
        if (pagePersona.description() != null && !pagePersona.description().isBlank()) {
            parts.add("Giọng văn & định hướng: " + pagePersona.description().strip());
        } else if (pagePersona.resolvedConfig() != null
                && pagePersona.resolvedConfig().toneStyle() != null
                && !pagePersona.resolvedConfig().toneStyle().isBlank()) {
            parts.add("Giọng văn: " + pagePersona.resolvedConfig().toneStyle().strip());
        }
        return parts.isEmpty()
                ? "Thân thiện, chuyên nghiệp, tận tâm và thấu hiểu khách hàng."
                : String.join(" | ", parts);
    }

    public static List<ReciprocalRankFusion.FusedChunk> filterChunksByVisibleCatalogScope(
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<PackageCatalogService.PackageSummary> visiblePackages
    ) {
        if (fusedChunks == null || fusedChunks.isEmpty()) {
            return List.of();
        }
        if (visiblePackages == null || visiblePackages.isEmpty()) {
            return fusedChunks;
        }
        Set<String> visibleCodes = new LinkedHashSet<>();
        Set<Long> visiblePrices = new LinkedHashSet<>();
        boolean anyVisibleHasBuffet = false;
        for (PackageCatalogService.PackageSummary p : visiblePackages) {
            if (p == null) continue;
            if (p.code() != null && !p.code().isBlank()) {
                visibleCodes.add(p.code().strip().toUpperCase(Locale.ROOT).replaceAll("[-_]", ""));
            }
            if (p.priceVnd() > 0L) {
                visiblePrices.add(p.priceVnd());
            }
            if (p.includesSummary() != null
                    && p.includesSummary().toLowerCase(Locale.ROOT).contains("buffet")) {
                anyVisibleHasBuffet = true;
            }
        }

        Pattern explicitPricesPat = Pattern.compile("(?i)match_prices\\s*=\\s*\\[([^\\]]+)\\]");
        Pattern explicitCodesPat = Pattern.compile("(?i)match_codes\\s*=\\s*\\[([^\\]]+)\\]");

        List<ReciprocalRankFusion.FusedChunk> filtered = new ArrayList<>(fusedChunks.size());
        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) {
                continue;
            }
            String text = fc.chunk().text();
            String headings = fc.chunk().headingPath() != null ? String.join(" ", fc.chunk().headingPath()) : "";
            String combined = headings + " " + text;
            if (!anyVisibleHasBuffet && combined.toLowerCase(Locale.ROOT).contains("buffet")) {
                continue;
            }

            if (!visibleCodes.isEmpty() || !visiblePrices.isEmpty()) {
                Set<String> chunkCodes = new LinkedHashSet<>();
                for (String c : ChunkMetadataExtractor.extractServiceCodes(combined)) {
                    if (c != null && !c.isBlank()) {
                        chunkCodes.add(c.strip().toUpperCase(Locale.ROOT).replaceAll("[-_]", ""));
                    }
                }
                Matcher cm = explicitCodesPat.matcher(combined);
                while (cm.find()) {
                    for (String part : cm.group(1).split("[,;\\s]+")) {
                        if (!part.isBlank()) {
                            chunkCodes.add(part.strip().toUpperCase(Locale.ROOT).replaceAll("[-_]", ""));
                        }
                    }
                }

                Set<Long> chunkPrices = new LinkedHashSet<>(
                        ChunkMetadataExtractor.extractAllPrices(combined)
                );
                Matcher pm = explicitPricesPat.matcher(combined);
                while (pm.find()) {
                    for (String part : pm.group(1).split("[,;\\s]+")) {
                        try {
                            long v = Long.parseLong(part.replaceAll("[^\\d]", ""));
                            if (v > 0L) {
                                chunkPrices.add(v);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }

                boolean hasSpecificMeta = !chunkCodes.isEmpty() || !chunkPrices.isEmpty();
                if (hasSpecificMeta) {
                    boolean codeMatch = chunkCodes.stream().anyMatch(visibleCodes::contains);
                    boolean priceMatch = chunkPrices.stream().anyMatch(visiblePrices::contains);
                    if (!codeMatch && !priceMatch) {
                        continue;
                    }
                }
            }
            filtered.add(fc);
        }
        return filtered;
    }

    public static boolean isRedundantPriceTableChunk(String chunkText) {
        if (chunkText == null || chunkText.isBlank()) {
            return true;
        }
        String low = chunkText.toLowerCase(Locale.ROOT);
        int priceHits = 0;
        Matcher pm = ReplyContractValidator.PRICE_PATTERN.matcher(chunkText);
        while (pm.find()) {
            priceHits++;
        }
        boolean hasPolicyOrExperienceSignal = low.contains("không nhận tip")
                || low.contains("chính sách")
                || low.contains("bảo hành")
                || low.contains("cam kết")
                || low.contains("quy trình")
                || low.contains("lưu ý")
                || low.contains("địa chỉ")
                || low.contains("giờ mở cửa");
        return priceHits >= 3 && !hasPolicyOrExperienceSignal;
    }

    public static String buildCleanAgentPrompt(
            PromptBuilder.PagePersona pagePersona,
            String customerPronoun,
            List<String> knownSymptoms,
            List<String> knownBodyAreas,
            String frequency,
            boolean conditionClarified,
            String heldPackageDisplay,
            String heldPriceDisplay,
            int priceObjectionCount,
            boolean packageInterestAccepted,
            String bookingStatus,
            List<PackageCatalogService.PackageSummary> scopeFilteredCatalog,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<FanpageBotRequest.ConversationTurn> history,
            String customerMessage
    ) {
        return buildCleanAgentPrompt(
                pagePersona,
                customerPronoun,
                knownSymptoms,
                knownBodyAreas,
                frequency,
                conditionClarified,
                heldPackageDisplay,
                heldPriceDisplay,
                priceObjectionCount,
                packageInterestAccepted,
                bookingStatus,
                scopeFilteredCatalog,
                fusedChunks,
                history,
                customerMessage,
                null
        );
    }

    public static String buildCleanAgentPrompt(
            PromptBuilder.PagePersona pagePersona,
            String customerPronoun,
            List<String> knownSymptoms,
            List<String> knownBodyAreas,
            String frequency,
            boolean conditionClarified,
            String heldPackageDisplay,
            String heldPriceDisplay,
            int priceObjectionCount,
            boolean packageInterestAccepted,
            String bookingStatus,
            List<PackageCatalogService.PackageSummary> scopeFilteredCatalog,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<FanpageBotRequest.ConversationTurn> history,
            String customerMessage,
            CustomerInsightDto customerInsight
    ) {
        TieredCatalogResult tiered = rankAndTierCatalogForCleanAgent(
                scopeFilteredCatalog,
                knownSymptoms,
                knownBodyAreas,
                Set.of(),
                false
        );
        String prevBot = CleanAgentOrchestratorService.resolveImmediatelyPrecedingBotText(history, null);
        String precedingSpeechAct = CleanAgentOrchestratorService.inferPrecedingBotSpeechAct(
                customerInsight, prevBot, history, bookingStatus);
        boolean msgHasPriceObjection = SentimentIntentLayer.isPriceObjection(customerMessage)
                || CleanAgentOrchestratorService.isCleanPriceObjectionMessage(customerMessage)
                || CleanAgentOrchestratorService.isExplicitDownsellOrLowBudgetRequest(customerMessage, null);
        boolean isHesitation = CleanAgentOrchestratorService.isHesitationOrDeferralMessage(customerMessage);
        boolean isBareAck = !msgHasPriceObjection && !isHesitation
                && CleanAgentOrchestratorService.isBareAcknowledgmentMessage(customerMessage);
        boolean hasProactiveBookingInfo = !msgHasPriceObjection && !isHesitation
                && CleanAgentOrchestratorService.hasProactiveBookingOrPackageSelectionInMessage(customerMessage);
        boolean isBookingConfirmed = "CONFIRMED".equalsIgnoreCase(bookingStatus)
                || CleanAgentOrchestratorService.SPEECH_ACT_BOOKING_CONFIRMED.equalsIgnoreCase(precedingSpeechAct);
        boolean isPoliteClosingAck = isBareAck
                && !isBookingConfirmed
                && (CleanAgentOrchestratorService.SPEECH_ACT_RESPECTED_PAUSE.equalsIgnoreCase(precedingSpeechAct)
                    || CleanAgentOrchestratorService.wasPriorCustomerTurnHesitation(history)
                    || CleanAgentOrchestratorService.isPauseOrDeferralBotReply(prevBot));
        boolean isAckForOfferedDetails = isBareAck
                && !isBookingConfirmed
                && !isPoliteClosingAck
                && CleanAgentOrchestratorService.SPEECH_ACT_OFFERED_DETAILS.equalsIgnoreCase(precedingSpeechAct);
        boolean isBareAckForInvitedTrial = isBareAck
                && !isBookingConfirmed
                && !isPoliteClosingAck
                && !isAckForOfferedDetails
                && CleanAgentOrchestratorService.SPEECH_ACT_INVITED_TRIAL_OR_TIME.equalsIgnoreCase(precedingSpeechAct);
        boolean priorWasPause = CleanAgentOrchestratorService.SPEECH_ACT_RESPECTED_PAUSE.equalsIgnoreCase(precedingSpeechAct)
                || CleanAgentOrchestratorService.wasPriorCustomerTurnHesitation(history);

        boolean effectivePackageInterestAccepted = !msgHasPriceObjection
                && !isHesitation
                && !isPoliteClosingAck
                && !isBookingConfirmed
                && !isAckForOfferedDetails
                && (hasProactiveBookingInfo
                    || isBareAckForInvitedTrial
                    || CleanAgentOrchestratorService.isPositiveInterestForTrialInvite(customerMessage)
                    || (packageInterestAccepted && !priorWasPause));
        boolean isPriceObj = msgHasPriceObjection;
        List<String> signals = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                customerMessage,
                CleanAgentOrchestratorService.isChallengeOrQuestionBotAssumption(customerMessage),
                SentimentIntentLayer.isConversationStyleComplaint(customerMessage)
                        || SentimentIntentLayer.isWordingOrExpressionFeedback(customerMessage),
                SentimentIntentLayer.isEntityMismatchCorrection(customerMessage),
                isAckForOfferedDetails,
                isPriceObj,
                priceObjectionCount,
                CleanAgentOrchestratorService.isPositiveInterestForTrialInvite(customerMessage)
                        || isBareAckForInvitedTrial || hasProactiveBookingInfo,
                effectivePackageInterestAccepted,
                knownSymptoms != null ? knownSymptoms : List.of(),
                knownBodyAreas != null ? knownBodyAreas : List.of(),
                frequency != null ? frequency : "chưa có",
                precedingSpeechAct,
                bookingStatus,
                history
        );
        return buildCleanAgentPrompt(
                pagePersona,
                customerPronoun,
                knownSymptoms,
                knownBodyAreas,
                frequency,
                conditionClarified,
                heldPackageDisplay,
                heldPriceDisplay,
                priceObjectionCount,
                effectivePackageInterestAccepted,
                bookingStatus,
                signals,
                CleanAgentOrchestratorService.extractLastBotQuestion(prevBot, null),
                conditionClarified ? 1 : 0,
                tiered,
                fusedChunks,
                history,
                customerMessage,
                customerInsight,
                List.of(),
                Map.of()
        );
    }

    public static String buildCleanAgentPrompt(
            PromptBuilder.PagePersona pagePersona,
            String customerPronoun,
            List<String> knownSymptoms,
            List<String> knownBodyAreas,
            String statedContextOrTiming,
            boolean conditionClarified,
            String heldPackageDisplay,
            String heldPriceDisplay,
            int priceObjectionCount,
            boolean packageInterestAccepted,
            String bookingFunnelStatus,
            List<String> currentTurnSignals,
            String lastBotQuestion,
            int discoveryQuestionCount,
            TieredCatalogResult tieredCatalog,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<FanpageBotRequest.ConversationTurn> history,
            String customerMessage
    ) {
        return buildCleanAgentPrompt(
                pagePersona,
                customerPronoun,
                knownSymptoms,
                knownBodyAreas,
                statedContextOrTiming,
                conditionClarified,
                heldPackageDisplay,
                heldPriceDisplay,
                priceObjectionCount,
                packageInterestAccepted,
                bookingFunnelStatus,
                currentTurnSignals,
                lastBotQuestion,
                discoveryQuestionCount,
                tieredCatalog,
                fusedChunks,
                history,
                customerMessage,
                null,
                List.of(),
                Map.of()
        );
    }

    public static String buildCleanAgentPrompt(
            PromptBuilder.PagePersona pagePersona,
            String customerPronoun,
            List<String> knownSymptoms,
            List<String> knownBodyAreas,
            String statedContextOrTiming,
            boolean conditionClarified,
            String heldPackageDisplay,
            String heldPriceDisplay,
            int priceObjectionCount,
            boolean packageInterestAccepted,
            String bookingFunnelStatus,
            List<String> currentTurnSignals,
            String lastBotQuestion,
            int discoveryQuestionCount,
            TieredCatalogResult tieredCatalog,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<FanpageBotRequest.ConversationTurn> history,
            String customerMessage,
            CustomerInsightDto customerInsight,
            List<AgentSkillDto> activeSkills,
            Map<String, String> pendingBookingSlots
    ) {
        String botName = resolveBotDisplayName(pagePersona);
        String pageName = (pagePersona != null && pagePersona.name() != null && !pagePersona.name().isBlank())
                ? pagePersona.name().strip()
                : "Page";
        String selfPronoun = (pagePersona != null && pagePersona.resolvedConfig() != null)
                ? pagePersona.resolvedConfig().resolvedSelfPronoun()
                : "em";
        String custPronoun = (customerPronoun != null && !customerPronoun.isBlank()) ? customerPronoun.strip() : "mình";
        String personaStyleContext = resolvePersonaStyleContext(pagePersona);

        String systemPrompt = CLEAN_AGENT_SYSTEM_PROMPT_TEMPLATE
                .replace("{bot_name}", botName)
                .replace("{page_name}", pageName)
                .replace("{persona_style_context}", personaStyleContext)
                .replace("{self_pronoun}", selfPronoun)
                .replace("{customer_pronoun}", custPronoun);

        String safeTiming = (statedContextOrTiming != null && !statedContextOrTiming.isBlank() && !"chưa rõ".equals(statedContextOrTiming))
                ? statedContextOrTiming.strip()
                : "chưa có";
        boolean discoveryCompleted = conditionClarified || discoveryQuestionCount >= 1 || !"chưa có".equals(safeTiming);
        String discoveryPolicyStatus = discoveryCompleted
                ? "ĐÃ HỎI KHÁM PHÁ TÌNH TRẠNG — CẤM HỎI LẠI VỀ THỜI ĐIỂM HAY TẦN SUẤT Ở LƯỢT NÀY"
                : "CHƯA HỎI TÌNH TRẠNG — ĐƯỢC PHÉP HỎI TỐI ĐA 1 CÂU NGẮN GỌN VỀ THỜI ĐIỂM XUẤT HIỆN";

        List<String> safeSignals = currentTurnSignals != null ? currentTurnSignals : List.of();
        String resolvedStage = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                safeSignals,
                customerMessage,
                priceObjectionCount,
                packageInterestAccepted,
                tieredCatalog,
                history
        );
        boolean isMetaCommunicationTurn = "STAGE_5_META_COMMUNICATION_REPAIR".equals(resolvedStage);
        boolean isPostBookingClosingAckTurn = "STAGE_4B_POST_BOOKING_CONFIRMED_CLOSING".equals(resolvedStage);
        boolean isPureDeferralOrBusyTurn = "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE".equals(resolvedStage);
        boolean isPriceContextHesitationTurn = "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL".equals(resolvedStage);
        boolean isBookingOrPositiveTrialTurn = "STAGE_4_POSITIVE_TRIAL_OR_BOOKING".equals(resolvedStage);
        boolean isFirstPriceObjectionHoldTurn = "STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE".equals(resolvedStage);
        boolean isDownsellOrRepeatObjectionTurn = "STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION".equals(resolvedStage);
        boolean isPoliteClosingAckTurn = safeSignals.contains("POLITE_CLOSING_ACKNOWLEDGMENT");
        boolean isServiceDetailQuestionTurn = safeSignals.contains("SERVICE_OR_UNIT_DETAIL_QUESTION")
                && !safeSignals.contains("PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS")
                && !safeSignals.contains("POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE");

        boolean hasEconomicalAlt = tieredCatalog != null
                && tieredCatalog.economicalAlternatives() != null
                && !tieredCatalog.economicalAlternatives().isEmpty();
        boolean downsellAlreadyOfferedInHistory = hasEconomicalAlt
                && ((history == null || history.isEmpty())
                    ? priceObjectionCount >= 2
                    : hasPackageMentionedInBotHistory(history, tieredCatalog.economicalAlternatives()));
        String prevBotTextForPkgGrounding = CleanAgentOrchestratorService.resolveImmediatelyPrecedingBotText(history, null);
        boolean downsellPresentedInLastBotTurn = hasEconomicalAlt
                && isAnyPackagePresentedInBotText(prevBotTextForPkgGrounding, tieredCatalog.economicalAlternatives());
        boolean customerExplicitlyChoseDownsellPkg = hasEconomicalAlt
                && (customerExplicitlyAskedForEconomicalGroup(customerMessage, tieredCatalog.economicalAlternatives())
                    || CleanAgentOrchestratorService.hasPackagePriceMentionedInText(customerMessage, tieredCatalog.economicalAlternatives()));

        String effectiveHeldPackageDisplay = heldPackageDisplay;
        String effectiveHeldPriceDisplay = heldPriceDisplay;
        if (!isPoliteClosingAckTurn
                && (isDownsellOrRepeatObjectionTurn
                    || (isBookingOrPositiveTrialTurn && (customerExplicitlyChoseDownsellPkg || downsellPresentedInLastBotTurn || downsellAlreadyOfferedInHistory))
                    || (isServiceDetailQuestionTurn && downsellAlreadyOfferedInHistory))
                && hasEconomicalAlt) {
            PackageCatalogService.PackageSummary downsellPkg = tieredCatalog.economicalAlternatives().get(0);
            effectiveHeldPackageDisplay = downsellPkg.canonicalLabel();
            effectiveHeldPriceDisplay = downsellPkg.priceDisplay();
        }

        boolean heldPkgIsNull = (effectiveHeldPackageDisplay == null
                || effectiveHeldPackageDisplay.isBlank()
                || "null".equalsIgnoreCase(effectiveHeldPackageDisplay.strip()));
        String activePackageInFocus = !heldPkgIsNull
                ? (effectiveHeldPackageDisplay + (effectiveHeldPriceDisplay != null && !"null".equals(effectiveHeldPriceDisplay) ? " (" + effectiveHeldPriceDisplay + ")" : ""))
                : "KHÁCH MỚI CHIA SẺ NHU CẦU, CHƯA BIẾT TÊN GÓI — BẠN PHẢI CHỦ ĐỘNG ĐỀ XUẤT GÓI SỐ 1 (\"Với tình trạng này, "
                    + selfPronoun + " gợi ý " + custPronoun + " tham khảo gói...\", CẤM viết \"" + custPronoun
                    + " đang quan tâm đến gói...\" và CẤM hỏi câu đối lập vô nghĩa \"thử 1 buổi lẻ trước hay đặt lịch ngay cho gói trọn vẹn\")";
        String resolvedFunnelStatus = isPostBookingClosingAckTurn
                ? "CONFIRMED"
                : (isPoliteClosingAckTurn
                    ? "NONE"
                    : ((bookingFunnelStatus != null && !bookingFunnelStatus.isBlank())
                        ? ("COLLECTING_INFO".equals(bookingFunnelStatus) ? "COLLECTING_VISIT_TIME" : bookingFunnelStatus)
                        : (isBookingOrPositiveTrialTurn ? "COLLECTING_VISIT_TIME" : "NONE")));

        PackageCatalogService.PackageSummary activePkgForBrain = (!isPoliteClosingAckTurn
                && (isDownsellOrRepeatObjectionTurn
                    || isPriceContextHesitationTurn
                    || (isBookingOrPositiveTrialTurn && (customerExplicitlyChoseDownsellPkg || downsellPresentedInLastBotTurn || downsellAlreadyOfferedInHistory))
                    || (isServiceDetailQuestionTurn && downsellAlreadyOfferedInHistory))
                && hasEconomicalAlt)
                ? tieredCatalog.economicalAlternatives().get(0)
                : findHeldOrTopPrimaryPackage(tieredCatalog, effectiveHeldPackageDisplay, effectiveHeldPriceDisplay);

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<CLEAN_AGENT_SYSTEM_PROMPT>\n")
          .append(systemPrompt.strip())
          .append("\n</CLEAN_AGENT_SYSTEM_PROMPT>\n")
          .append("<!-- SYSTEM_USER_SPLIT -->\n\n");

        appendCleanAgentBrainInsightBlock(
                sb,
                selfPronoun,
                custPronoun,
                knownSymptoms,
                knownBodyAreas,
                safeTiming,
                discoveryCompleted,
                activePackageInFocus,
                activePkgForBrain,
                priceObjectionCount,
                resolvedStage,
                isServiceDetailQuestionTurn,
                customerInsight,
                activeSkills
        );

        sb.append("<STRUCTURED_CONVERSATION_STATE>\n")
          .append("1. current_turn_signal:\n")
          .append("   - detected_signals: ").append(safeSignals).append('\n')
          .append("2. discovery_slots_and_question_memory:\n")
          .append("   - stated_symptoms_and_areas: symptoms=").append(knownSymptoms != null ? knownSymptoms : List.of())
          .append(", areas=").append(knownBodyAreas != null ? knownBodyAreas : List.of()).append('\n')
          .append("   - stated_context_or_timing: \"").append(safeTiming).append("\"\n")
          .append("   - last_bot_question: \"").append(lastBotQuestion != null ? lastBotQuestion : "không có").append("\"\n")
          .append("   - discovery_question_count: ").append(discoveryQuestionCount).append('\n')
          .append("   - discovery_question_already_asked: ").append(discoveryCompleted).append('\n')
          .append("   - discovery_policy_status: \"").append(discoveryPolicyStatus).append("\"\n")
          .append("3. commercial_and_booking_funnel:\n")
          .append("   - active_package_in_focus: \"").append(activePackageInFocus).append("\"\n")
          .append("   - price_objection_count: ").append(priceObjectionCount).append('\n')
          .append("   - booking_funnel_status: \"").append(resolvedFunnelStatus).append("\"\n");
        if (pendingBookingSlots != null && !pendingBookingSlots.isEmpty()) {
            sb.append("   - pending_booking_slots: ").append(pendingBookingSlots).append('\n');
        }
        sb.append("</STRUCTURED_CONVERSATION_STATE>\n\n");

        List<PackageCatalogService.PackageSummary> visiblePackagesForTurn = new ArrayList<>();
        sb.append("<SCOPE_FILTERED_CATALOG>\n");
        if (isMetaCommunicationTurn) {
            sb.append("(Tạm ẩn danh sách chào bán gói ở lượt này — Khách đang phản hồi/hỏi vặn về cách nói chuyện hoặc suy đoán của bot. Chỉ trả lời duyên dáng vào điều khách vừa nói và điều chỉnh cách giao tiếp.)\n");
        } else if (isPureDeferralOrBusyTurn) {
            sb.append("(Tạm ẩn danh sách chào bán gói ở lượt này — Khách đang bận hoặc xin tạm dừng để tự cân nhắc. Tuyệt đối tôn trọng nhịp độ của khách, tách thành 2 bong bóng ngắn ấm áp lịch sự: Bong bóng 1 xác nhận đã lưu thông tin & hẹn khi nào tiện nhắn lại; Bong bóng 2 tách riêng lời chúc tốt lành, KHÔNG chào mời thêm gói mới và KHÔNG hỏi ép giờ đặt lịch.)\n");
        } else if (isPostBookingClosingAckTurn) {
            sb.append("(Lịch hẹn của khách đã được xác nhận thành công — Chỉ cảm ơn ấm áp và hẹn đón tiếp khách đúng khung giờ đã đặt, KHÔNG hỏi lại khung giờ đặt lịch.)\n");
        } else if (tieredCatalog == null
                || (tieredCatalog.primaryRecommendations().isEmpty() && tieredCatalog.economicalAlternatives().isEmpty())) {
            if (tieredCatalog != null && tieredCatalog.noMatchingPackageForRequestedScope()) {
                sb.append("NO_MATCHING_PACKAGE_FOR_REQUESTED_SCOPE: Hiện tại trong danh mục dịch vụ của Page chưa có gói đáp ứng đúng vùng/nhu cầu khách vừa nêu. Hãy trả lời trung thực và đề xuất chuyển chuyên viên tư vấn hỗ trợ trực tiếp, tuyệt đối không lấy gói lệch vùng ra báo giá.\n");
            } else {
                sb.append("NO_MATCHING_PACKAGE_FOR_REQUESTED_SCOPE: (Chưa có gói dịch vụ khớp trực tiếp trong danh mục)\n");
            }
        } else if (isServiceDetailQuestionTurn && activePkgForBrain != null) {
            sb.append("[GÓI KHÁCH ĐANG MUỐN NGHE CHI TIẾT (CHIA SẺ RÕ QUYỀN LỢI & QUY TRÌNH, CHƯA ÉP GIỜ ĐẶT LỊCH)]:\n");
            appendCatalogPackageLine(sb, 1, activePkgForBrain, knownBodyAreas, knownSymptoms);
            visiblePackagesForTurn.add(activePkgForBrain);
        } else if (isPriceContextHesitationTurn) {
            PackageCatalogService.PackageSummary heldPkg = findHeldOrTopPrimaryPackage(
                    tieredCatalog, effectiveHeldPackageDisplay, effectiveHeldPriceDisplay);
            if (heldPkg != null) {
                sb.append("[GÓI CHỦ LỰC KHÁCH ĐANG CÂN NHẮC]:\n");
                appendCatalogPackageLine(sb, 1, heldPkg, knownBodyAreas, knownSymptoms);
                visiblePackagesForTurn.add(heldPkg);
            }
            sb.append("[NHÓM 2 — PHƯƠNG ÁN TIẾT KIỆM / HẠ BẬC (DÙNG ĐỂ GỢI Ý MỀM 1 LẦN KHÔNG ÉP BUỘC KHI KHÁCH LƯỠNG LỰ VÌ GIÁ)]:\n");
            List<PackageCatalogService.PackageSummary> economicals = tieredCatalog.economicalAlternatives();
            for (int i = 0; i < Math.min(2, economicals.size()); i++) {
                appendCatalogPackageLine(sb, i + 1, economicals.get(i), knownBodyAreas, knownSymptoms);
                visiblePackagesForTurn.add(economicals.get(i));
            }
        } else if (isBookingOrPositiveTrialTurn) {
            PackageCatalogService.PackageSummary selectedPkg = findHeldOrTopPrimaryPackage(
                    tieredCatalog, effectiveHeldPackageDisplay, effectiveHeldPriceDisplay);
            sb.append("[GÓI KHÁCH ĐANG QUAN TÂM / ĐẶT LỊCH TRẢI NGHIỆM]:\n");
            if (selectedPkg != null) {
                appendCatalogPackageLine(sb, 1, selectedPkg, knownBodyAreas, knownSymptoms);
                visiblePackagesForTurn.add(selectedPkg);
            } else {
                sb.append("1. ").append(activePackageInFocus).append('\n');
            }
            String visitTimeSlot = (pendingBookingSlots != null && pendingBookingSlots.get("visit_time") != null)
                    ? pendingBookingSlots.get("visit_time")
                    : "chưa có khung giờ cụ thể — hãy mời khách chọn khung giờ thuận tiện hôm nay hoặc ngày mai";
            sb.append("- Trạng thái khung giờ dự kiến ghé (pendingBookingSlots): ").append(visitTimeSlot).append('\n');
        } else if (isFirstPriceObjectionHoldTurn) {
            PackageCatalogService.PackageSummary heldPkg = findHeldOrTopPrimaryPackage(
                    tieredCatalog, effectiveHeldPackageDisplay, effectiveHeldPriceDisplay);
            sb.append("[GÓI ĐANG CÂN NHẮC — GIỮ NGUYÊN GIÁ TRỊ Ở LẦN BĂN KHOĂN GIÁ ĐẦU TIÊN (TUYỆT ĐỐI CẤM HẠ GÓI / CẤM TỰ GẠ GÓI TIẾT KIỆM HƠN)]:\n");
            if (heldPkg != null) {
                appendCatalogPackageLine(sb, 1, heldPkg, knownBodyAreas, knownSymptoms);
                visiblePackagesForTurn.add(heldPkg);
            } else {
                sb.append("1. ").append(activePackageInFocus).append('\n');
            }
        } else if (isDownsellOrRepeatObjectionTurn) {
            List<PackageCatalogService.PackageSummary> primaries = tieredCatalog.primaryRecommendations();
            List<PackageCatalogService.PackageSummary> economicals = tieredCatalog.economicalAlternatives();
            if (economicals == null || economicals.isEmpty()) {
                sb.append("[NHÓM 1 — GÓI CHỦ LỰC ĐỀ XUẤT CHÍNH (PRIMARY_RECOMMENDATIONS)]:\n");
                int maxPrimary = Math.min(2, primaries.size());
                for (int i = 0; i < maxPrimary; i++) {
                    appendCatalogPackageLine(sb, i + 1, primaries.get(i), knownBodyAreas, knownSymptoms);
                    visiblePackagesForTurn.add(primaries.get(i));
                }
                sb.append("[NHÓM 2 — PHƯƠNG ÁN TIẾT KIỆM / HẠ BẬC (ECONOMICAL_DOWNSELL_OR_ALTERNATIVES)]:\n");
                PackageCatalogService.PackageSummary minPrimary = primaries.isEmpty() ? null : primaries.get(0);
                String minPkgDesc = minPrimary != null
                        ? (minPrimary.displayName() + " " + minPrimary.durationLabel() + " (" + minPrimary.priceDisplay() + ")")
                        : activePackageInFocus;
                sb.append("(Trống — Gói ").append(minPkgDesc)
                  .append(" đã là mức tiêu chuẩn thấp nhất đáp ứng đúng nhu cầu này của khách; tuyệt đối không hạ sang dịch vụ lệch phạm vi)\n");
            } else {
                if (primaries != null && !primaries.isEmpty()) {
                    sb.append("[NHÓM 1 — GÓI TOÀN THÂN / CHUYÊN SÂU ĐÃ BÁO TRƯỚC ĐÓ (CHỈ DÙNG ĐỂ ĐỐI CHIẾU SỰ KHÁC BIỆT PHẠM VI VỚI GÓI NHÓM 2, TUYỆT ĐỐI CẤM NÓI GÓI NHÓM 1 LÀ MỨC GIÁ THẤP NHẤT)]:\n");
                    appendCatalogPackageLine(sb, 1, primaries.get(0), knownBodyAreas, knownSymptoms);
                    visiblePackagesForTurn.add(primaries.get(0));
                }
                sb.append("[NHÓM 2 — PHƯƠNG ÁN TIẾT KIỆM / HẠ BẬC (BẮT BUỘC GIỚI THIỆU Ở LƯỢT NÀY & GIẢI THÍCH TRUNG THỰC PHẠM VI VÙNG CHĂM SÓC)]:\n");
                int maxEcon = Math.min(2, economicals.size());
                for (int i = 0; i < maxEcon; i++) {
                    appendCatalogPackageLine(sb, i + 1, economicals.get(i), knownBodyAreas, knownSymptoms);
                    visiblePackagesForTurn.add(economicals.get(i));
                }
            }
        } else {
            sb.append("[NHÓM 1 — GÓI CHỦ LỰC ĐỀ XUẤT CHÍNH (PRIMARY_RECOMMENDATIONS)]:\n");
            List<PackageCatalogService.PackageSummary> primaries = tieredCatalog.primaryRecommendations();
            int maxPrimary = Math.min(2, primaries.size());
            for (int i = 0; i < maxPrimary; i++) {
                appendCatalogPackageLine(sb, i + 1, primaries.get(i), knownBodyAreas, knownSymptoms);
                visiblePackagesForTurn.add(primaries.get(i));
            }
            if (customerExplicitlyAskedForEconomicalGroup(customerMessage, tieredCatalog.economicalAlternatives())) {
                sb.append("[NHÓM 2 — PHƯƠNG ÁN TIẾT KIỆM / HẠ BẬC (ECONOMICAL_DOWNSELL_OR_ALTERNATIVES)]:\n");
                List<PackageCatalogService.PackageSummary> economicals = tieredCatalog.economicalAlternatives();
                for (int i = 0; i < Math.min(2, economicals.size()); i++) {
                    appendCatalogPackageLine(sb, i + 1, economicals.get(i), knownBodyAreas, knownSymptoms);
                    visiblePackagesForTurn.add(economicals.get(i));
                }
            }
        }
        if (activePkgForBrain != null && !visiblePackagesForTurn.contains(activePkgForBrain)) {
            visiblePackagesForTurn.add(activePkgForBrain);
        }
        sb.append("</SCOPE_FILTERED_CATALOG>\n\n");

        sb.append("<DOCUMENT_CONTEXT>\n");
        if (fusedChunks != null && !fusedChunks.isEmpty()) {
            boolean hasStructuredCatalog = tieredCatalog != null && !tieredCatalog.allAllowedPackages().isEmpty();
            List<ReciprocalRankFusion.FusedChunk> scopeMatchedChunks = filterChunksByVisibleCatalogScope(
                    fusedChunks,
                    visiblePackagesForTurn
            );
            int kept = 0;
            for (ReciprocalRankFusion.FusedChunk fc : scopeMatchedChunks) {
                if (kept >= 3) break;
                if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
                String text = fc.chunk().text().strip();
                if (text.isEmpty()) continue;
                if (hasStructuredCatalog && isRedundantPriceTableChunk(text)) {
                    continue;
                }
                if (text.length() > 350) {
                    text = text.substring(0, 350) + "...";
                }
                kept++;
                sb.append("[Chunk ").append(kept).append("] ").append(text).append("\n\n");
            }
        }
        sb.append("</DOCUMENT_CONTEXT>\n\n");

        sb.append("<CONVERSATION_HISTORY>\n");
        if (history != null && !history.isEmpty()) {
            int startIdx = Math.max(0, history.size() - 8);
            for (int i = startIdx; i < history.size(); i++) {
                FanpageBotRequest.ConversationTurn t = history.get(i);
                if (t == null || t.content() == null || t.content().isBlank()) continue;
                String roleLabel = ("assistant".equalsIgnoreCase(t.role()) || "bot".equalsIgnoreCase(t.role()))
                        ? "Bot"
                        : "Khách";
                sb.append(roleLabel).append(": ").append(t.content().strip().replaceAll("\\s+", " ")).append('\n');
            }
        }
        sb.append("Khách (tin nhắn hiện tại): ").append(customerMessage != null ? customerMessage.strip() : "").append('\n');
        sb.append("</CONVERSATION_HISTORY>");
        return sb.toString();
    }

    public static void appendCleanAgentBrainInsightBlock(
            StringBuilder sb,
            String selfPronoun,
            String custPronoun,
            List<String> knownSymptoms,
            List<String> knownBodyAreas,
            String safeTiming,
            boolean discoveryCompleted,
            String activePackageInFocus,
            PackageCatalogService.PackageSummary activePkg,
            int priceObjectionCount,
            String stageLabel,
            boolean isServiceDetailQuestionTurn,
            CustomerInsightDto customerInsight,
            List<AgentSkillDto> activeSkills
    ) {
        boolean isMetaCommunicationTurn = "STAGE_5_META_COMMUNICATION_REPAIR".equals(stageLabel);
        boolean isPureDeferralOrBusyTurn = "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE".equals(stageLabel);
        boolean isPostBookingClosingAckTurn = "STAGE_4B_POST_BOOKING_CONFIRMED_CLOSING".equals(stageLabel);
        boolean isPriceContextHesitationTurn = "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL".equals(stageLabel);
        boolean isBookingOrPositiveTrialTurn = "STAGE_4_POSITIVE_TRIAL_OR_BOOKING".equals(stageLabel);
        boolean isFirstPriceObjectionHoldTurn = "STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE".equals(stageLabel);
        boolean isDownsellOrRepeatObjectionTurn = "STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION".equals(stageLabel);

        String verifiedFacts = "symptoms=" + (knownSymptoms != null ? knownSymptoms : List.of())
                + ", areas=" + (knownBodyAreas != null ? knownBodyAreas : List.of())
                + ", timing_or_context=\"" + safeTiming + "\"";
        if (customerInsight != null && customerInsight.extractedPains() != null && !customerInsight.extractedPains().isEmpty()) {
            verifiedFacts += ", pains=" + customerInsight.extractedPains();
        }
        List<String> storedCareNotes = (customerInsight != null && customerInsight.careNotes() != null)
                ? customerInsight.careNotes()
                : List.of();
        if (!storedCareNotes.isEmpty()) {
            verifiedFacts += ", care_notes=" + storedCareNotes;
        }

        String buyingState = customerInsight != null
                ? ("stage=" + stageLabel + ", archetype=" + customerInsight.buyerArchetype()
                    + " (" + customerInsight.archetypeLabel() + "), buying_temp="
                    + customerInsight.buyingTemperature() + "/100 (" + customerInsight.temperatureLabel() + ")")
                : ("stage=" + stageLabel + ", price_objection_count=" + priceObjectionCount);
        if (customerInsight != null && customerInsight.workingHypothesis() != null && !customerInsight.workingHypothesis().isBlank()) {
            buyingState += ", working_hypothesis=\"" + customerInsight.workingHypothesis().strip() + "\"";
        }
        if (customerInsight != null && customerInsight.lastActualSentSpeechAct() != null && !customerInsight.lastActualSentSpeechAct().isBlank()) {
            buyingState += ", last_actual_sent_speech_act=\"" + customerInsight.lastActualSentSpeechAct().strip() + "\"";
        }

        String pkgName = (activePkg != null && activePkg.displayName() != null && !activePkg.displayName().isBlank())
                ? activePkg.displayName().strip()
                : "gói dịch vụ phù hợp";
        String pkgDur = (activePkg != null && activePkg.durationLabel() != null && !activePkg.durationLabel().isBlank())
                ? (" " + activePkg.durationLabel().strip())
                : "";
        String pkgPrice = (activePkg != null && activePkg.priceDisplay() != null && !activePkg.priceDisplay().isBlank())
                ? activePkg.priceDisplay().strip()
                : "theo niêm yết";
        String pkgSpace = (activePkg != null && activePkg.customerFacingSpaceNote() != null && !activePkg.customerFacingSpaceNote().isBlank())
                ? activePkg.customerFacingSpaceNote().strip()
                : "không gian tiêu chuẩn chu đáo";
        String pkgIncludes = (activePkg != null && activePkg.includesSummary() != null && !activePkg.includesSummary().isBlank())
                ? activePkg.includesSummary().strip()
                : "đầy đủ các bước theo quy trình";
        String needLabel = (knownSymptoms != null && !knownSymptoms.isEmpty())
                ? knownSymptoms.get(0)
                : ((knownBodyAreas != null && !knownBodyAreas.isEmpty()) ? knownBodyAreas.get(0) : "nhu cầu hiện tại");
        String partialScopeNote = buildPartialScopeLimitationNote(activePkg, knownBodyAreas, knownSymptoms);
        String partialScopeHint = partialScopeNote.isEmpty()
                ? ""
                : (" " + partialScopeNote + " — nói rõ trung thực gói " + pkgPrice + " tập trung chăm sóc vùng trong phạm vi gói trước, chưa bao gồm các vùng còn lại ngoài phạm vi như gói toàn thân");

        String tacticalFocus;
        String goodExample;
        String antiPattern;

        if (isMetaCommunicationTurn) {
            tacticalFocus = "Nhận lỗi duyên dáng, hóm hỉnh đúng vào điều khách vừa nói/hỏi vặn; tạm dừng chào bán gói và hỏi thăm tự nhiên.";
            goodExample = "Dạ " + selfPronoun + " suy đoán hơi vội trước khi hỏi kỹ " + custPronoun + " rồi ạ. Không biết tình trạng " + needLabel + " của " + custPronoun + " là mới xuất hiện gần đây hay trong hoàn cảnh nào vậy ạ?";
            antiPattern = "Lờ đi lời phàn nàn/hỏi vặn của khách để đọc lại bảng giá hoặc hỏi lặp lại câu cũ.";
        } else if (isPureDeferralOrBusyTurn) {
            String savedCareSummary = !storedCareNotes.isEmpty()
                    ? (" và các lưu ý chăm sóc (" + storedCareNotes.get(storedCareNotes.size() - 1) + ")")
                    : " và các lưu ý chăm sóc";
            tacticalFocus = "Tôn trọng hoàn toàn nhịp độ và thời gian của khách khi khách đang bận, xin tạm dừng cân nhắc hoặc nhắn lời xác nhận khép lại ('Ok', 'Ừ', 'Vâng'); tách lời chào tạm biệt thành 2 bong bóng ngắn gọn, giàu cảm xúc: Bong bóng 1 xác nhận " + selfPronoun + " đã lưu lại thông tin gói" + savedCareSummary + " của " + custPronoun + " để bất cứ khi nào " + custPronoun + " thu xếp được thời gian nhắn lại thì hỗ trợ ngay (kết thúc bằng dấu '.'); Bong bóng 2 tách riêng lời chúc " + custPronoun + " một ngày thật nhiều niềm vui và thư thái. TUYỆT ĐỐI KHÔNG chào mời thêm gói mới và CẤM hỏi ép giờ đặt lịch.";
            goodExample = "Dạ vâng ạ, " + selfPronoun + " đã lưu lại thông tin gói cùng các lưu ý chăm sóc của " + custPronoun + " rồi ạ. Bất cứ khi nào " + custPronoun + " thu xếp được thời gian cần hỗ trợ thì cứ nhắn " + selfPronoun + " ngay nhé. | Chúc " + custPronoun + " một ngày thật nhiều niềm vui và thư thái ạ!";
            antiPattern = "Cố nhồi thêm gói dịch vụ mới hoặc hỏi ép khách chốt giờ đặt lịch ngay khi khách đã báo bận/hẹn suy nghĩ thêm.";
        } else if (isPostBookingClosingAckTurn) {
            tacticalFocus = "Lịch hẹn của khách ĐÃ XÁC NHẬN (CONFIRMED). Khách vừa nhắn xác nhận ngắn ('Ok', 'Vâng') để khép lại cuộc trò chuyện — chỉ trả lời 1 bong bóng ngắn gọn, ấm áp cảm ơn và hẹn đón tiếp " + custPronoun + " đúng khung giờ đã đặt, TUYỆT ĐỐI CẤM hỏi lại khung giờ đặt lịch.";
            goodExample = "Dạ vâng ạ, " + selfPronoun + " cảm ơn " + custPronoun + " nhiều ạ. Bên " + selfPronoun + " rất mong được đón tiếp và chăm sóc chu đáo cho " + custPronoun + " đúng khung giờ đã hẹn ạ.";
            antiPattern = "Hỏi lặp lại 'Mình tiện ghé khung giờ nào?' khi lịch hẹn đã được xác nhận.";
        } else if (isServiceDetailQuestionTurn) {
            tacticalFocus = "Khách muốn nghe chi tiết về gói đang trao đổi (" + pkgName + pkgDur + " - " + pkgPrice + ") — chia sẻ rõ ràng quyền lợi (" + pkgIncludes + ") và không gian (" + pkgSpace + ") của gói, chưa thúc ép chốt giờ đặt lịch.";
            goodExample = "Dạ gói " + pkgName + pkgDur + " (" + pkgPrice + " trọn gói) tập trung chăm sóc vùng " + needLabel + ", đã bao gồm " + pkgIncludes + " tại " + pkgSpace + " ạ.";
            antiPattern = "Lờ đi câu hỏi chi tiết để hỏi ép giờ đặt lịch ngay lập tức.";
        } else if (isPriceContextHesitationTurn) {
            tacticalFocus = "Trả lời trong 2 bong bóng ngắn (20–32 từ/bong bóng): Bong bóng 1 giải tỏa áp lực (trấn an " + custPronoun + " cứ thoải mái tham khảo thêm, chưa cần vội đặt lịch ngay); Bong bóng 2 mở lối thoát mềm không ép buộc (nhắc nhẹ nếu muốn thử phương án tiết kiệm hơn cho " + needLabel + " thì bên Page cũng có gói " + pkgName + pkgDur + " - " + pkgPrice + partialScopeHint + ", khi nào tiện cứ nhắn để so sánh chi tiết).";
            goodExample = "Dạ không sao đâu ạ, " + custPronoun + " cứ thoải mái tham khảo và cân nhắc thêm, chưa cần vội đặt lịch ngay đâu ạ. Nếu " + custPronoun + " muốn thử một phương án nhẹ nhàng, tiết kiệm hơn tập trung chăm sóc vùng đầu, cổ, vai, gáy trước thì bên " + selfPronoun + " cũng có gói " + pkgName + pkgDur + " (" + pkgPrice + " trọn gói), khi nào tiện " + custPronoun + " cứ nhắn " + selfPronoun + " nhé.";
            antiPattern = "Hỏi ép khách chốt giờ ngay lập tức hoặc lặp lại nguyên văn đoạn quảng cáo của lượt trước.";
        } else if (isFirstPriceObjectionHoldTurn) {
            tacticalFocus = "Giữ nguyên gói đang tư vấn (" + pkgName + pkgDur + " - " + pkgPrice + "), dùng [NEO CẢM XÚC -> TRAO NIỀM TIN -> KHƠI SỰ TÒ MÒ] qua 2–3 bong bóng dựa trên đúng không gian (" + pkgSpace + ") và quyền lợi (" + pkgIncludes + ") của gói; tách câu hỏi mời trải nghiệm ở cuối thành 1 bong bóng riêng tự nhiên; TUYỆT ĐỐI CẤM tự gạ gói tiết kiệm hơn.";
            goodExample = "Giá trị của gói " + pkgName + pkgDur + " nằm ở trọn vẹn thời lượng chuyên viên lành nghề tập trung xử lý sâu từng điểm " + needLabel + " để mang lại cảm nhận cải thiện rõ rệt ngay từ lần đầu ạ. Mức giá " + pkgPrice + " đã bao gồm trọn gói (" + pkgIncludes + " | Không gian: " + pkgSpace + "). " + BubbleCapHook.capitalizeFirstLetter(custPronoun) + " thử trải nghiệm trước 1 lần để tự mình kiểm chứng chất lượng nhé?";
            antiPattern = "Dạ em hiểu mình đang cân nhắc ạ, hay để em gợi ý gói tiết kiệm hơn cho mình nhé?";
        } else if (isDownsellOrRepeatObjectionTurn) {
            tacticalFocus = "BẮT BUỘC giới thiệu phương án tiết kiệm trong [NHÓM 2] (" + pkgName + pkgDur + " - " + pkgPrice + ") kèm ĐÚNG không gian (" + pkgSpace + "), quyền lợi (" + pkgIncludes + ") và giải thích chuẩn xác rằng " + pkgPrice + " là lựa chọn tiết kiệm nhất trong nhóm dịch vụ chăm sóc phù hợp với tình trạng của khách" + partialScopeHint + ". Nếu khách vừa hỏi một mức giá thấp chưa tồn tại (như 100k, 150k), giải thích nhẹ nhàng rằng hiện bên " + selfPronoun + " chưa có gói phù hợp ở mức giá đó rồi giới thiệu gói " + pkgPrice + " để khách thoải mái cân nhắc (không chuyển Handoff). Khi [NHÓM 2] có gói hợp lệ, TUYỆT ĐỐI CẤM nói gói Nhóm 1 là 'mức thấp nhất bên em có', CẤM tự khẳng định " + pkgPrice + " là 'mức thấp nhất toàn spa' và CẤM chê bai mức giá thấp hơn gói Nhóm 1 là 'chỉ làm được thư giãn hời hợt'.";
            goodExample = "Dạ mức 399k là gói Massage cơ bản 60 phút chăm sóc chuyên sâu toàn thân ạ. Trong nhóm dịch vụ chăm sóc đầu – cổ – vai – gáy phù hợp với tình trạng của " + custPronoun + ", lựa chọn tiết kiệm nhất hiện tại là gói " + pkgName + pkgDur + " (" + pkgPrice + " trọn gói), bao gồm " + pkgIncludes + " tại " + pkgSpace + " ạ.";
            antiPattern = "Khẳng định sai sự thật rằng 399k là mức giá thấp nhất cho vùng vai gáy, tự nhận 239k là mức thấp nhất toàn spa, đề xuất dịch vụ lệch vùng, hoặc chê mức giá dưới 399k chỉ làm được thư giãn hời hợt.";
        } else if (isBookingOrPositiveTrialTurn) {
            String careNoteHint = !storedCareNotes.isEmpty()
                    ? (" đồng thời khéo léo nhắc lại lưu ý chăm sóc đã ghi nhận (" + storedCareNotes.get(storedCareNotes.size() - 1) + ") theo đúng trạng thái: \"" + selfPronoun + " lưu lại lưu ý này để khi " + custPronoun + " ghé, chuyên viên chuẩn bị chu đáo cho " + custPronoun + " nhé ạ\" (CẤM nói quá khứ \"" + selfPronoun + " đã dặn chuyên viên\" khi chưa có lịch hẹn)")
                    : " (nói rõ \"" + selfPronoun + " lưu lại để khi " + custPronoun + " ghé sẽ dặn chuyên viên\", CẤM nói quá khứ \"" + selfPronoun + " đã dặn chuyên viên\" khi chưa có lịch hẹn)";
            String careNoteExampleTail = !storedCareNotes.isEmpty()
                    ? (" " + BubbleCapHook.capitalizeFirstLetter(selfPronoun) + " lưu lại lưu ý \"" + storedCareNotes.get(storedCareNotes.size() - 1) + "\" này để khi " + custPronoun + " ghé, chuyên viên chuẩn bị chu đáo cho " + custPronoun + " nhé ạ.")
                    : "";
            tacticalFocus = "Chốt thẳng vào gói vừa thống nhất (" + pkgName + pkgDur + " - " + pkgPrice + ")" + careNoteHint + " và mời khách chọn khung giờ thuận tiện hôm nay hoặc ngày mai; tuyệt đối không hỏi ngược bắt khách chọn lại giữa các gói.";
            goodExample = "Dạ vâng ạ, vậy " + selfPronoun + " giữ lịch gói " + pkgName + pkgDur + " (" + pkgPrice + ") cho " + custPronoun + " nha." + careNoteExampleTail + " Không biết hôm nay hay ngày mai " + custPronoun + " tiện ghé vào khung giờ nào để bên " + selfPronoun + " chuẩn bị đón tiếp chu đáo nhất ạ?";
            antiPattern = "Hỏi ngược bắt khách chọn lại giữa 2 gói, vừa hỏi giờ đặt lịch vừa gạ đổi sang gói tiết kiệm hơn, hoặc nói quá khứ 'Em đã dặn chuyên viên' khi chưa có lịch hẹn.";
        } else if (!discoveryCompleted) {
            tacticalFocus = "Chia sẻ tự nhiên với sự khó chịu/bất tiện của " + needLabel + " mà khách vừa nêu, không đoán mò nguyên nhân và chỉ hỏi 1 câu ngắn về thời gian xuất hiện.";
            goodExample = "Dạ gặp tình trạng " + needLabel + " như vậy chắc hẳn gây nhiều bất tiện và khó chịu trong sinh hoạt hàng ngày của " + custPronoun + " lắm ạ. Tình trạng này của " + custPronoun + " mới xuất hiện mấy hôm nay hay đã kéo dài lâu rồi ạ?";
            antiPattern = "Tự suy diễn nguyên nhân khi khách chưa kể hoặc nói trống không thiếu chủ ngữ.";
        } else {
            tacticalFocus = "Chủ động đề xuất gói Số 1 trong [NHÓM 1] (" + pkgName + pkgDur + " - " + pkgPrice + ") ở bong bóng 1, gợi cảm nhận trải nghiệm thực tế (" + pkgIncludes + " | Không gian: " + pkgSpace + ") ở bong bóng 2, và tách câu hỏi mời trải nghiệm ở cuối thành 1 bong bóng riêng tự nhiên.";
            goodExample = "Với tình trạng " + needLabel + ", " + selfPronoun + " gợi ý " + custPronoun + " tham khảo gói " + pkgName + pkgDur + " (" + pkgPrice + " trọn gói) để chuyên viên chăm sóc chuyên sâu và mang lại hiệu quả tốt nhất ạ. Gói đã bao gồm " + pkgIncludes + " tại " + pkgSpace + ". " + BubbleCapHook.capitalizeFirstLetter(custPronoun) + " thấy phương án này thế nào ạ?";
            antiPattern = "Viết '" + custPronoun + " đang quan tâm đến gói...' khi khách chưa biết tên gói, hoặc hỏi câu vô nghĩa 'thử 1 buổi lẻ trước hay đặt lịch ngay cho gói trọn vẹn'.";
        }

        if (activeSkills != null && !activeSkills.isEmpty()) {
            AgentSkillDto topSkill = activeSkills.get(0);
            if (topSkill != null && topSkill.goodExample() != null && !topSkill.goodExample().isBlank()
                    && !isFirstPriceObjectionHoldTurn && !isDownsellOrRepeatObjectionTurn
                    && !isPriceContextHesitationTurn && !isPureDeferralOrBusyTurn && !isBookingOrPositiveTrialTurn
                    && !isPostBookingClosingAckTurn && !isServiceDetailQuestionTurn) {
                goodExample = topSkill.goodExample().strip();
            }
            if (topSkill != null && topSkill.antiPattern() != null && !topSkill.antiPattern().isBlank()
                    && !isFirstPriceObjectionHoldTurn && !isDownsellOrRepeatObjectionTurn
                    && !isPriceContextHesitationTurn && !isPureDeferralOrBusyTurn && !isBookingOrPositiveTrialTurn
                    && !isPostBookingClosingAckTurn && !isServiceDetailQuestionTurn) {
                antiPattern = topSkill.antiPattern().strip();
            }
        }

        sb.append("<AGENT_BRAIN_INSIGHT>\n")
          .append("1. verified_customer_facts: ").append(verifiedFacts).append('\n')
          .append("2. current_stage_and_buying_state: ").append(buyingState).append('\n')
          .append("3. turn_tactical_focus: \"").append(tacticalFocus).append("\"\n")
          .append("4. contrastive_reference:\n")
          .append("   - good_example: \"").append(goodExample).append("\"\n")
          .append("   - anti_pattern: \"").append(antiPattern).append("\"\n")
          .append("</AGENT_BRAIN_INSIGHT>\n\n");
    }

    public static void appendCatalogPackageLine(
            StringBuilder sb,
            int index,
            PackageCatalogService.PackageSummary p,
            List<String> knownBodyAreas,
            List<String> knownSymptoms
    ) {
        if (p == null) return;
        String partialScopeNote = buildPartialScopeLimitationNote(p, knownBodyAreas, knownSymptoms);
        sb.append(index).append(". ")
          .append(p.displayName() != null ? p.displayName() : "")
          .append(" — Thời lượng: ").append(p.durationLabel() != null ? p.durationLabel() : "")
          .append(" — Giá trọn gói: ").append(p.priceDisplay() != null ? p.priceDisplay() : "")
          .append(" — Vùng chăm sóc: ").append(p.bodyAreas() != null ? p.bodyAreas() : List.of())
          .append(partialScopeNote.isEmpty() ? "" : " " + partialScopeNote)
          .append(" — Không gian/Hình thức: ").append(p.customerFacingSpaceNote())
          .append(" | Quyền lợi đi kèm: ").append(p.includesSummary() != null ? p.includesSummary() : "")
          .append(" (Chỉ được nêu đúng không gian và quyền lợi của gói này, cấm lấy tiện ích của gói khác đắp sang)")
          .append(" — Chi tiết: ").append(p.processSummary() != null ? p.processSummary() : "")
          .append('\n');
    }

    public static PackageCatalogService.PackageSummary findHeldOrTopPrimaryPackage(
            TieredCatalogResult tieredCatalog,
            String heldPackageDisplay,
            String heldPriceDisplay
    ) {
        if (tieredCatalog == null) {
            return null;
        }
        List<PackageCatalogService.PackageSummary> allAllowed = tieredCatalog.allAllowedPackages();
        if (heldPriceDisplay != null && !"null".equals(heldPriceDisplay)) {
            java.math.BigDecimal bd = ConversationState.parsePriceToBigDecimal(heldPriceDisplay);
            if (bd != null) {
                long targetVnd = bd.longValue();
                for (PackageCatalogService.PackageSummary p : allAllowed) {
                    if (p != null && p.priceVnd() == targetVnd) {
                        return p;
                    }
                }
            }
        }
        if (heldPackageDisplay != null && !"null".equals(heldPackageDisplay)) {
            String lowHeld = heldPackageDisplay.toLowerCase(Locale.ROOT);
            for (PackageCatalogService.PackageSummary p : allAllowed) {
                if (p != null && p.displayName() != null && lowHeld.contains(p.displayName().toLowerCase(Locale.ROOT))) {
                    return p;
                }
            }
        }
        if (!tieredCatalog.primaryRecommendations().isEmpty()) {
            return tieredCatalog.primaryRecommendations().get(0);
        }
        return allAllowed.isEmpty() ? null : allAllowed.get(0);
    }

    public static boolean customerExplicitlyAskedForEconomicalGroup(
            String customerMessage,
            List<PackageCatalogService.PackageSummary> economicals
    ) {
        if (customerMessage == null || customerMessage.isBlank() || economicals == null || economicals.isEmpty()) {
            return false;
        }
        String low = customerMessage.toLowerCase(Locale.ROOT);
        for (PackageCatalogService.PackageSummary p : economicals) {
            if (p == null) continue;
            if (p.code() != null && !p.code().isBlank() && low.contains(p.code().toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (p.displayName() != null && !p.displayName().isBlank()
                    && low.contains(p.displayName().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasKeywordInCatalogOrChunks(
            TieredCatalogResult tieredCatalog,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            String keywordLower
    ) {
        if (keywordLower == null || keywordLower.isBlank()) {
            return false;
        }
        if (tieredCatalog != null) {
            for (PackageCatalogService.PackageSummary p : tieredCatalog.allAllowedPackages()) {
                if (p == null) continue;
                String inc = p.includesSummary() != null ? p.includesSummary().toLowerCase(Locale.ROOT) : "";
                String proc = p.processSummary() != null ? p.processSummary().toLowerCase(Locale.ROOT) : "";
                if (inc.contains(keywordLower) || proc.contains(keywordLower)) {
                    return true;
                }
            }
        }
        if (fusedChunks != null) {
            for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
                if (fc != null && fc.chunk() != null && fc.chunk().text() != null
                        && fc.chunk().text().toLowerCase(Locale.ROOT).contains(keywordLower)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isAnyPackagePresentedInBotText(
            String botText,
            List<PackageCatalogService.PackageSummary> packages
    ) {
        if (botText == null || botText.isBlank() || packages == null || packages.isEmpty()) {
            return false;
        }
        String low = botText.toLowerCase(Locale.ROOT);
        for (PackageCatalogService.PackageSummary p : packages) {
            if (p == null) continue;
            if (p.displayName() != null && !p.displayName().isBlank()
                    && low.contains(p.displayName().strip().toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (p.priceDisplay() != null && !p.priceDisplay().isBlank()
                    && low.contains(p.priceDisplay().strip().toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (p.code() != null && !p.code().isBlank()
                    && Pattern.compile("(?iU)\\b" + Pattern.quote(p.code().strip()) + "\\b").matcher(botText).find()) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasPackageMentionedInBotHistory(
            List<FanpageBotRequest.ConversationTurn> history,
            List<PackageCatalogService.PackageSummary> packages
    ) {
        if (history == null || history.isEmpty() || packages == null || packages.isEmpty()) {
            return false;
        }
        for (FanpageBotRequest.ConversationTurn t : history) {
            if (t == null || t.content() == null || t.content().isBlank()) continue;
            if (!"assistant".equalsIgnoreCase(t.role()) && !"bot".equalsIgnoreCase(t.role())) continue;
            if (isAnyPackagePresentedInBotText(t.content(), packages)) {
                return true;
            }
        }
        return false;
    }
}
