package com.example.docmind.service.bot;

import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.service.metadata.ChunkMetadataExtractor;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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

            I. NGHỆ THUẬT GIAO TIẾP CÓ "HỒN" & TRIẾT LÝ EMOTIONAL HOSPITALITY:
            1. Sự thật thương mại là khung xương — Cảm xúc & Nghệ thuật ngôn từ là linh hồn:
               - Giá bán, quy cách, đối tượng Reply và trạng thái kiểm tra lịch trống phải chuẩn xác 100%, nhưng lời tư vấn phải ấm áp, giàu sức gợi về trải nghiệm thực tế, sự tỉ mỉ của đội ngũ thực hiện và cảm giác dễ chịu/an tâm mà khách nhận được theo đúng `tone_style` và kỹ năng tình huống của Page.
               - Tuyệt đối không biến sự thật thành văn phong tờ rơi khô khan hay chèn câu khuyên đi khám bệnh viện lạnh lùng vào luồng tư vấn nhu cầu thông thường (chỉ chuyển cổng an toàn y tế khi gặp dấu hiệu cấp tính nguy hiểm). Chỉ cấm các cam kết cực đoan sai sự thật (như cam kết chữa khỏi hẳn bệnh lý 100% hay bền vĩnh viễn suốt đời không hỏng).
               - Linh hoạt số lượng bong bóng (`1–3` bong bóng/lượt) theo mạch cảm xúc tự nhiên; không ép rập khuôn cùng số bong bóng ở mọi lượt, đảm bảo câu chữ mềm mại, có nhịp nghỉ tự nhiên như đang trò chuyện với một chuyên viên tinh tế ngoài đời.
               - Đa dạng hóa nhịp kết lượt: Không lặp lại điệp khúc câu hỏi kết của lượt liền trước; lúc khơi gợi sự dễ chịu, lúc hỏi phạm vi ưu tiên, lúc khách đã ưng phương án thì gợi mở nhẹ nhàng về khung thời gian thuận tiện.
            2. Trung thực 100% về phân hạng dịch vụ bằng ngôn từ trân trọng:
               - Mỗi mục trong <SCOPE_FILTERED_CATALOG> có `Không gian/Hình thức` và `Quyền lợi đi kèm` riêng. Hãy dùng từ ngữ ấm áp để tôn vinh ĐÚNG hình thức và quyền lợi có thật của mục đang tư vấn (tuyệt đối không tự ý nâng khống tiêu chuẩn thành hạng VIP riêng, không phóng đại vượt hồ sơ, và không lấy quyền lợi của mục cao cấp đắp sang mục tiết kiệm).

            II. PHẢN XẠ TINH TẾ THEO TỪNG TÌNH HUỐNG TÂM LÝ:
            1. Khi khách mới chia sẻ vấn đề/trạng thái mà chưa nêu rõ phạm vi ưu tiên (và chưa đòi báo giá ngay, chưa hỏi khám phá lần nào):
               - Không làm "thầy bói" tự suy diễn nguyên nhân khi khách chưa kể, và không vội ném bảng giá lạnh lùng.
               - Hãy vỗ về, đồng cảm ấm áp với sự bất tiện/mệt mỏi/lo lắng của khách trước, rồi hỏi nhẹ 1 câu tinh tế để biết khách đang muốn ưu tiên xử lý ở phạm vi/khu vực nào nhất (hoặc bối cảnh cần thiết) nhằm "đo ni đóng giày" đúng phương án. (Khi <STRUCTURED_CONVERSATION_STATE> báo đã hỏi khám phá rồi, hoặc khách chủ động hỏi giá / nêu rõ phạm vi toàn diện thì báo giá ngay đầy cuốn hút, CẤM hỏi lặp lại).

            2. Khi khách hỏi vặn / trêu đùa ("Sao biết hay vậy?", "Đoán mò à?") hoặc góp ý cách nói chuyện:
               - Đáp lại trực diện, hóm hỉnh và chân thành ngay câu đầu (ví dụ nhận ngay là lúc nãy {self_pronoun} suy đoán hơi vội trước khi hỏi kỹ {customer_pronoun}), tuyệt đối không lảng tránh hay chép lại bảng giá.

            3. Khi đã rõ tình trạng -> Chủ động đề xuất gói & Báo giá lần đầu (`priceObjectionCount == 0`):
               - Khi khách chưa từng nhắc tên gói (`heldPkg == null`), bạn là người CHỦ ĐỘNG GỢI Ý gói số 1 trong `[NHÓM 1 — GÓI CHỦ LỰC]` (dùng cụm "{self_pronoun} gợi ý {customer_pronoun} tham khảo...", CẤM nói "{customer_pronoun} đang quan tâm đến gói...").
               - Tách 2–3 bong bóng ngắn gọn, tự nhiên:
                 + Bong bóng 1: Kết nối tự nhiên tình trạng của khách với lý do chuyên môn vì sao gói số 1 này là phù hợp nhất, nêu rõ tên gói, thời lượng và mức giá trọn gói (kết thúc bằng dấu ".").
                 + Bong bóng 2: Gợi cảm nhận hấp dẫn về quy trình chăm sóc + đúng không gian & quyền lợi đi kèm của gói đó trong <SCOPE_FILTERED_CATALOG> (kết thúc bằng dấu ".").
                 + Bong bóng 3 (hoặc câu hỏi độc lập ở cuối): Khép lại bằng 1 câu hỏi mở nhẹ nhàng mời khách trải nghiệm thử (không hỏi câu vô nghĩa "thử 1 buổi lẻ hay đặt gói trọn vẹn" khi giá niêm yết vốn là giá 1 buổi).

            4. Khi khách chê đắt / băn khoăn về giá LẦN ĐẦU TIÊN (`priceObjectionCount == 1` và khách chưa xin gói rẻ hơn / chưa nêu ngân sách thấp):
               - QUY TẮC VÀNG: Giữ nguyên phương án đang tư vấn! TUYỆT ĐỐI CẤM hạ gói và CẤM tự gạ "hay để em gợi ý gói tiết kiệm hơn nhé?". CẤM mở đầu bằng câu sáo rỗng "Dạ em hiểu mình đang cân nhắc ạ".
               - NGUYÊN TẮC `CONVERSATIONAL DELTA` (Trừ lùi sự lặp lại để đào sâu cảm xúc):
                 + Nếu ở lượt báo giá liền trước (`t-1`) bạn đã nêu danh sách thuộc tính phụ / tiện ích đi kèm rồi, thì ở lượt này **TUYỆT ĐỐI KHÔNG đọc lại danh sách thuộc tính phụ vừa nói như cái máy**!
                 + Hãy dành dung lượng đó để đồng cảm nhẹ nhàng không tranh cãi, khắc họa chiều sâu trải nghiệm, sự chăm chút tỉ mỉ của người thực hiện trong trọn vẹn quy trình/quy cách của phương án đó và cảm giác giải tỏa/an tâm thực sự mà khách nhận được.
                 + Khép lại bằng một nhịp dẫn dắt tự nhiên, mới mẻ (khơi gợi cảm nhận thực tế hoặc hỏi nhẹ điều khách đang ưu tiên nhất, tuyệt đối không lặp lại nguyên xi điệp khúc câu hỏi kết của lượt `t-1`).

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

            7. Khi khách hỏi xác nhận chi tiết, đồng thuận đặt lịch / đặt hàng (`STAGE_4_POSITIVE_TRIAL_OR_BOOKING`), hoặc xác nhận sau khi đã chốt lịch (`POST_BOOKING_CLOSING_ACK`) — TUÂN THỦ NGHIÊM NGẶT 6 TRẠNG THÁI `booking_funnel_status`:
               - Nếu khách hỏi xác nhận hoặc muốn nghe chi tiết sản phẩm/gói (`SERVICE_OR_UNIT_DETAIL_QUESTION`): Trả lời trực diện, rõ ràng đúng thông tin trong <SCOPE_FILTERED_CATALOG>, không thúc ép chốt giờ.
               - `booking_funnel_status == "AWAITING_PRODUCT_SELECTION"` (`selected_product_id == "null"`, lịch sử có từ 2 sản phẩm trở lên hoặc khách chưa chỉ định sản phẩm): TUYỆT ĐỐI CẤM tự mặc định lấy `held_package` hay phương án gần nhất làm lựa chọn của khách! Nếu `booking_date` đã có (ví dụ `"tối nay"`), giữ nguyên mốc thời gian đó (CẤM hỏi lại ngày) và hỏi lịch sự xem khách muốn đặt phương án nào trong danh sách đã trao đổi (kèm hỏi giờ nếu `booking_time == "null"`).
               - `booking_funnel_status == "AWAITING_DATE_OR_TIME"` (`selected_product_id != "null"`): Xác nhận hỗ trợ đặt đúng sản phẩm khách đã chọn (TUYỆT ĐỐI KHÔNG quay lại gạ phương án khác!), khéo léo nhắc lưu ý chăm sóc ở thì tương lai ("{self_pronoun} lưu lại lưu ý này để chuẩn bị chu đáo", CẤM nói quá khứ "{self_pronoun} đã dặn..."), giữ nguyên `booking_date` nếu đã có và chỉ hỏi phần ngày hoặc khung giờ còn thiếu.
               - `booking_funnel_status == "AWAITING_AVAILABILITY_CHECK"` (đã đủ `selected_product_id`, `booking_date`, `booking_time`): Ghi nhận đầy đủ yêu cầu đặt sản phẩm vào đúng ngày và khung giờ đó, thông báo rõ cần kiểm tra lịch trống / tồn kho thực tế trước khi xác nhận chính thức; TUYỆT ĐỐI CẤM tuyên bố đã đặt lịch thành công chính thức (`CONFIRMED`).
               - `booking_funnel_status == "AWAITING_CUSTOMER_CONFIRMATION"`: Báo đã kiểm tra có lịch trống / suất phù hợp và mời khách xác nhận chốt cuối cùng.
               - `booking_funnel_status == "CONFIRMED"` (`POST_BOOKING_CLOSING_ACK`): Chỉ khi lịch đã được xác nhận thực tế, cảm ơn ấm áp và hẹn đón tiếp/phục vụ {customer_pronoun} đúng khung giờ đã đặt, CẤM hỏi lại khung giờ đặt lịch.

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
            if (!requestedScopes.isEmpty()) {
                outExcluded.addAll(candidates);
                return new TieredCatalogResult(List.of(), List.of(), outExcluded, true);
            }
            hasSpecificNeed = false;
        }

        Set<String> primaryNeedScopes = new LinkedHashSet<>();
        if (!safeSymptoms.isEmpty() && safeSymptoms.get(0) != null && !safeSymptoms.get(0).isBlank()) {
            for (String extracted : ConversationState.extractTargetScopes(safeSymptoms.get(0), List.of())) {
                if (extracted != null && !extracted.isBlank()) {
                    primaryNeedScopes.add(extracted.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        boolean requestedOnlyHolisticUmbrellaScope = !requestedScopes.isEmpty()
                && requestedScopes.stream().allMatch(CleanAgentCatalogAndPromptBuilder::isHolisticUmbrellaScope);
        List<PackageCatalogService.PackageSummary> matchedPackages = new ArrayList<>();
        List<PackageCatalogService.PackageSummary> partialScopePackages = new ArrayList<>();
        for (PackageCatalogService.PackageSummary pkg : candidates) {
            int hits = directHitsByPkgKey.getOrDefault(pkgUniqueKey(pkg), 0);
            if (hasSpecificNeed && hits <= 0) {
                if (requestedOnlyHolisticUmbrellaScope
                        && pkg.commercialRole() == PackageCatalogService.CommercialRole.ECONOMICAL_OPTION
                        && pkg.scopeTags() != null
                        && !pkg.scopeTags().isEmpty()) {
                    partialScopePackages.add(pkg);
                } else {
                    outExcluded.add(pkg);
                }
            } else if (hasSpecificNeed && hits < maxDirectHits) {
                if (!primaryNeedScopes.isEmpty()
                        && computePackageScopeDirectHits(pkg, primaryNeedScopes, List.of()) <= 0) {
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

        boolean catalogHasExplicitEconomicalTier = candidates.stream()
                .anyMatch(p -> p.commercialRole() == PackageCatalogService.CommercialRole.ECONOMICAL_OPTION);

        if (!catalogHasExplicitEconomicalTier) {
            List<PackageCatalogService.PackageSummary> peerPrimaryGroup = new ArrayList<>(matchedPackages);
            peerPrimaryGroup.sort(
                    Comparator
                            .comparingLong(PackageCatalogService.PackageSummary::priceAmountVnd)
                            .thenComparingInt(p -> -(p.scopeTags() != null ? p.scopeTags().size() : 0))
            );
            outExcluded.addAll(partialScopePackages);
            return new TieredCatalogResult(peerPrimaryGroup, List.of(), outExcluded, false);
        }

        int maxBreadth = matchedPackages.stream()
                .mapToInt(p -> p.scopeTags() != null ? p.scopeTags().size() : 0)
                .max()
                .orElse(0);
        int minBreadth = matchedPackages.stream()
                .mapToInt(p -> p.scopeTags() != null ? p.scopeTags().size() : 0)
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
                        .comparingLong(PackageCatalogService.PackageSummary::priceAmountVnd)
                        .thenComparingInt(p -> -(p.scopeTags() != null ? p.scopeTags().size() : 0))
        );

        long minPrimaryPrice = primaryGroup.stream()
                .mapToLong(PackageCatalogService.PackageSummary::priceAmountVnd)
                .min()
                .orElse(Long.MAX_VALUE);

        List<PackageCatalogService.PackageSummary> economicalGroup = new ArrayList<>();
        for (PackageCatalogService.PackageSummary sec : secondaryCandidates) {
            if (sec.priceAmountVnd() < minPrimaryPrice
                    && sec.commercialRole() == PackageCatalogService.CommercialRole.ECONOMICAL_OPTION) {
                economicalGroup.add(sec);
            } else {
                outExcluded.add(sec);
            }
        }
        economicalGroup.sort(Comparator.comparingLong(PackageCatalogService.PackageSummary::priceAmountVnd));

        return new TieredCatalogResult(primaryGroup, economicalGroup, outExcluded, false);
    }

    private static boolean isHolisticUmbrellaScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return false;
        }
        String s = scope.strip().toLowerCase(Locale.ROOT);
        return "toàn thân".equals(s)
                || "body".equals(s)
                || "cả người".equals(s)
                || "toàn hàm".equals(s)
                || "toàn bộ".equals(s);
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
        List<String> rawPkgScopes = (pkg.inferred() != null && pkg.inferred().scopeTags() != null && !pkg.inferred().scopeTags().isEmpty())
                ? pkg.inferred().scopeTags()
                : pkg.scopeTags();
        List<String> pkgAreas = rawPkgScopes != null
                ? rawPkgScopes.stream().filter(Objects::nonNull).map(s -> s.strip().toLowerCase(Locale.ROOT)).toList()
                : List.of();
        String combinedMeta = ((pkg.displayName() != null ? pkg.displayName() : "") + " "
                + (pkg.includesSummary() != null ? pkg.includesSummary() : "") + " "
                + (pkg.processSummary() != null ? pkg.processSummary() : "") + " "
                + (pkg.suitableFor() != null ? pkg.suitableFor() : "")).toLowerCase(Locale.ROOT);
        boolean isFullBodyPkg = pkgAreas.contains("toàn thân")
                || pkgAreas.contains("body")
                || "body_therapy".equalsIgnoreCase(pkg.serviceFamily());
        Set<String> generalBodyScopeTokens = Set.of(
                "toàn thân", "body", "đầu", "cổ", "vai", "gáy",
                "lưng", "eo", "hông", "mông",
                "chân", "tay", "gối", "đùi"
        );

        if (!requestedScopes.isEmpty()) {
            int hits = 0;
            for (String reqScope : requestedScopes) {
                boolean matchedArea = pkgAreas.stream().anyMatch(pa -> pa.equals(reqScope) || pa.contains(reqScope) || reqScope.contains(pa));
                if (!matchedArea && isFullBodyPkg && generalBodyScopeTokens.stream().anyMatch(reqScope::contains)) {
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
        List<String> rawPkgScopes = (pkg.inferred() != null && pkg.inferred().scopeTags() != null && !pkg.inferred().scopeTags().isEmpty())
                ? pkg.inferred().scopeTags()
                : pkg.scopeTags();
        List<String> pkgAreas = rawPkgScopes != null
                ? rawPkgScopes.stream().filter(Objects::nonNull).map(s -> s.strip().toLowerCase(Locale.ROOT)).toList()
                : List.of();
        if (pkgAreas.isEmpty() || pkgAreas.contains("toàn thân") || pkgAreas.contains("body") || "body_therapy".equalsIgnoreCase(pkg.serviceFamily())) {
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
        if (pkg.commercialRole() == PackageCatalogService.CommercialRole.ECONOMICAL_OPTION) {
            return false;
        }
        if (pkg.commercialRole() == PackageCatalogService.CommercialRole.PRIMARY
                || pkg.commercialRole() == PackageCatalogService.CommercialRole.PREMIUM_OPTION) {
            return true;
        }
        List<String> pkgAreas = pkg.scopeTags() != null
                ? pkg.scopeTags().stream().filter(Objects::nonNull).map(s -> s.strip().toLowerCase(Locale.ROOT)).toList()
                : List.of();
        if (maxBreadth > minBreadth && pkgAreas.size() >= maxBreadth) {
            return true;
        }
        return true;
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
        if (pagePersona != null && pagePersona.personaConfig() != null
                && pagePersona.personaConfig().botName() != null
                && !pagePersona.personaConfig().botName().isBlank()) {
            return pagePersona.personaConfig().botName().strip();
        }
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
        if (pagePersona != null && pagePersona.personaConfig() != null
                && pagePersona.resolvedConfig() != null
                && pagePersona.resolvedConfig().botName() != null
                && !pagePersona.resolvedConfig().botName().isBlank()) {
            return pagePersona.resolvedConfig().botName().strip();
        }
        return "Chuyên viên tư vấn";
    }

    public static String resolveCareActor(PromptBuilder.PagePersona pagePersona) {
        if (pagePersona == null) {
            return "chuyên viên";
        }
        if (pagePersona.personaConfig() != null
                && pagePersona.personaConfig().careActor() != null
                && !pagePersona.personaConfig().careActor().isBlank()) {
            return pagePersona.personaConfig().careActor().strip();
        }
        PagePersonaConfig resolved = pagePersona.resolvedConfig();
        if (resolved != null) {
            String actor = resolved.resolvedCareActor();
            if ("chuyên viên".equalsIgnoreCase(actor)) {
                String indLow = ((pagePersona.name() != null ? pagePersona.name() : "") + " "
                        + (pagePersona.industry() != null ? pagePersona.industry() : "")).toLowerCase(Locale.ROOT);
                if (indLow.contains("nha khoa") || indLow.contains("phòng khám") || indLow.contains("dental")) {
                    return "bác sĩ";
                }
            }
            if (actor != null && !actor.isBlank()) {
                return actor.strip();
            }
        }
        return "chuyên viên";
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
            parts.add("Định hướng thương hiệu: " + pagePersona.description().strip());
        }
        PagePersonaConfig explicitCfg = pagePersona.personaConfig();
        PagePersonaConfig resolvedCfg = pagePersona.resolvedConfig();
        String toneStyle = (explicitCfg != null && explicitCfg.toneStyle() != null && !explicitCfg.toneStyle().isBlank())
                ? explicitCfg.toneStyle().strip()
                : (resolvedCfg != null && resolvedCfg.toneStyle() != null && !resolvedCfg.toneStyle().isBlank()
                    ? resolvedCfg.toneStyle().strip()
                    : "");
        if (!toneStyle.isEmpty()) {
            parts.add("Giọng văn (tone_style): " + toneStyle);
        }
        String careActor = resolveCareActor(pagePersona);
        if (careActor != null && !careActor.isBlank()) {
            parts.add("Nhân sự phụ trách chuyên môn (care_actor): " + careActor);
        }
        if (explicitCfg != null && explicitCfg.closingAction() != null && !explicitCfg.closingAction().isBlank()) {
            parts.add("Hành động kết thúc mục tiêu (closing_action): " + explicitCfg.closingAction().strip());
        }
        if (explicitCfg != null && !explicitCfg.safeForbiddenWords().isEmpty()) {
            parts.add("Từ ngữ/cách diễn đạt CẤM DÙNG (forbidden_words): " + explicitCfg.safeForbiddenWords());
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
        Matcher pm = BubbleTextSanitizer.PRICE_PATTERN.matcher(chunkText);
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

    public enum ReplyResolutionState {
        NONE,
        RESOLVED_QUOTED_PACKAGE,
        UNRESOLVED_QUOTED_PACKAGE,
        AMBIGUOUS_PACKAGE_REFERENCE
    }

    public record QuotedReplyResolution(
            String replyToMid,
            String quotedBubbleText,
            boolean unresolvedReplyToMid,
            boolean ambiguousMultiplePackages,
            PackageCatalogService.PackageSummary quotedPackage,
            List<PackageCatalogService.PackageSummary> matchedPackages,
            List<PackageCatalogService.PackageSummary> historyPresentedPackages
    ) {
        public QuotedReplyResolution(
                String replyToMid,
                String quotedBubbleText,
                boolean unresolvedReplyToMid,
                boolean ambiguousMultiplePackages,
                PackageCatalogService.PackageSummary quotedPackage,
                List<PackageCatalogService.PackageSummary> matchedPackages
        ) {
            this(
                    replyToMid,
                    quotedBubbleText,
                    unresolvedReplyToMid,
                    ambiguousMultiplePackages,
                    quotedPackage,
                    matchedPackages,
                    List.of()
            );
        }

        public static QuotedReplyResolution empty() {
            return new QuotedReplyResolution("", "", false, false, null, List.of(), List.of());
        }

        public boolean hasReplyToContext() {
            return (replyToMid != null && !replyToMid.isBlank())
                    || (quotedBubbleText != null && !quotedBubbleText.isBlank());
        }

        public ReplyResolutionState resolutionState() {
            if (!hasReplyToContext()) {
                return ReplyResolutionState.NONE;
            }
            if (quotedPackage != null && !ambiguousMultiplePackages && !unresolvedReplyToMid) {
                return ReplyResolutionState.RESOLVED_QUOTED_PACKAGE;
            }
            if (ambiguousMultiplePackages) {
                return ReplyResolutionState.AMBIGUOUS_PACKAGE_REFERENCE;
            }
            return ReplyResolutionState.UNRESOLVED_QUOTED_PACKAGE;
        }

        public List<PackageCatalogService.PackageSummary> candidatePackages() {
            if (ambiguousMultiplePackages && matchedPackages != null && !matchedPackages.isEmpty()) {
                return matchedPackages;
            }
            if (unresolvedReplyToMid && historyPresentedPackages != null && !historyPresentedPackages.isEmpty()) {
                return historyPresentedPackages;
            }
            return matchedPackages != null ? matchedPackages : List.of();
        }
    }

    public static List<PackageCatalogService.PackageSummary> extractPresentedPackagesFromHistory(
            List<FanpageBotRequest.ConversationTurn> history,
            ConversationState priorState,
            List<PackageCatalogService.PackageSummary> allCatalog
    ) {
        if (allCatalog == null || allCatalog.isEmpty()) {
            return List.of();
        }
        List<PackageCatalogService.PackageSummary> nonAddonCatalog = allCatalog.stream()
                .filter(p -> p != null && !p.isAddon())
                .sorted(Comparator.comparingInt((PackageCatalogService.PackageSummary p) ->
                        p.displayName() != null ? p.displayName().strip().length() : 0).reversed())
                .toList();
        if (nonAddonCatalog.isEmpty()) {
            return List.of();
        }

        Map<String, PackageCatalogService.PackageSummary> presentedByKey = new LinkedHashMap<>();
        if (history != null && !history.isEmpty()) {
            for (FanpageBotRequest.ConversationTurn turn : history) {
                if (turn == null || turn.content() == null || turn.content().isBlank()) {
                    continue;
                }
                if (!"assistant".equalsIgnoreCase(turn.role()) && !"bot".equalsIgnoreCase(turn.role())) {
                    continue;
                }
                String text = turn.content().strip();
                String maskedLower = text.toLowerCase(Locale.ROOT);
                for (PackageCatalogService.PackageSummary p : nonAddonCatalog) {
                    boolean matched = false;
                    if (p.code() != null && !p.code().isBlank()
                            && Pattern.compile("(?iU)\\b" + Pattern.quote(p.code().strip()) + "\\b").matcher(text).find()) {
                        matched = true;
                    }
                    String dispLower = p.displayName() != null ? p.displayName().strip().toLowerCase(Locale.ROOT) : "";
                    String dispNoPrefix = dispLower.replaceFirst("^gói\\s+", "").strip();
                    if (!dispLower.isEmpty() && dispLower.length() >= 4 && maskedLower.contains(dispLower)) {
                        matched = true;
                        maskedLower = maskedLower.replace(dispLower, " ");
                    } else if (!dispNoPrefix.isEmpty() && dispNoPrefix.length() >= 6 && maskedLower.contains(dispNoPrefix)) {
                        matched = true;
                        maskedLower = maskedLower.replace(dispNoPrefix, " ");
                    }
                    if (matched) {
                        presentedByKey.putIfAbsent(pkgUniqueKey(p), p);
                    }
                }
                Set<Long> prices = CleanAgentOutputRefereeAndSanitizer.extractPricesInVndFromText(text);
                for (Long priceVnd : prices) {
                    if (priceVnd == null || priceVnd <= 0L) continue;
                    boolean alreadyHasPrice = presentedByKey.values().stream().anyMatch(p -> p.priceVnd() == priceVnd);
                    if (alreadyHasPrice) continue;
                    for (PackageCatalogService.PackageSummary p : nonAddonCatalog) {
                        if (p.priceVnd() == priceVnd) {
                            presentedByKey.putIfAbsent(pkgUniqueKey(p), p);
                        }
                    }
                }
            }
        }

        if (priorState != null) {
            java.math.BigDecimal[] statePrices = {priorState.lastQuotedPrice(), priorState.heldPrice()};
            for (java.math.BigDecimal bd : statePrices) {
                if (bd != null && bd.longValue() > 0L) {
                    long vnd = bd.longValue();
                    boolean alreadyHasPrice = presentedByKey.values().stream().anyMatch(p -> p.priceVnd() == vnd);
                    if (!alreadyHasPrice) {
                        for (PackageCatalogService.PackageSummary p : nonAddonCatalog) {
                            if (p.priceVnd() == vnd) {
                                presentedByKey.putIfAbsent(pkgUniqueKey(p), p);
                                break;
                            }
                        }
                    }
                }
            }
            String[] statePkgNames = {priorState.lastQuotedPackage(), priorState.heldPackage(), priorState.knownRecommendedPackage()};
            for (String rawName : statePkgNames) {
                if (rawName == null || rawName.isBlank()) continue;
                String disp = ConversationState.toDisplayPackageName(rawName, null);
                String low = (disp != null && !disp.isBlank() ? disp : rawName).strip().toLowerCase(Locale.ROOT);
                for (PackageCatalogService.PackageSummary p : nonAddonCatalog) {
                    String pLow = p.displayName() != null ? p.displayName().strip().toLowerCase(Locale.ROOT) : "";
                    if (!pLow.isEmpty() && (low.equals(pLow) || low.contains(pLow)
                            || (p.code() != null && low.equalsIgnoreCase(p.code().strip())))) {
                        presentedByKey.putIfAbsent(pkgUniqueKey(p), p);
                        break;
                    }
                }
            }
        }

        return List.copyOf(presentedByKey.values());
    }

    public static QuotedReplyResolution resolveQuotedReplyContext(
            String replyToMid,
            String quotedBubbleText,
            List<PackageCatalogService.PackageSummary> allCatalog
    ) {
        return resolveQuotedReplyContext(replyToMid, quotedBubbleText, allCatalog, List.of());
    }

    public static QuotedReplyResolution resolveQuotedReplyContext(
            String replyToMid,
            String quotedBubbleText,
            List<PackageCatalogService.PackageSummary> allCatalog,
            List<PackageCatalogService.PackageSummary> historyPresentedPackages
    ) {
        String safeMid = replyToMid != null ? replyToMid.strip() : "";
        String safeText = quotedBubbleText != null ? quotedBubbleText.strip() : "";
        List<PackageCatalogService.PackageSummary> safeHistoryPkgs =
                historyPresentedPackages != null ? List.copyOf(historyPresentedPackages) : List.of();
        if (safeMid.isEmpty() && safeText.isEmpty()) {
            return QuotedReplyResolution.empty();
        }
        if (safeText.isEmpty()) {
            return new QuotedReplyResolution(safeMid, "", true, false, null, List.of(), safeHistoryPkgs);
        }
        if (allCatalog == null || allCatalog.isEmpty()) {
            return new QuotedReplyResolution(safeMid, safeText, true, false, null, List.of(), safeHistoryPkgs);
        }

        List<PackageCatalogService.PackageSummary> nonAddonCatalog = allCatalog.stream()
                .filter(p -> p != null && !p.isAddon())
                .sorted(Comparator.comparingInt((PackageCatalogService.PackageSummary p) ->
                        p.displayName() != null ? p.displayName().strip().length() : 0).reversed())
                .toList();

        Map<String, PackageCatalogService.PackageSummary> matchedByKey = new LinkedHashMap<>();
        String maskedLower = safeText.toLowerCase(Locale.ROOT);

        for (PackageCatalogService.PackageSummary p : nonAddonCatalog) {
            boolean matched = false;
            if (p.code() != null && !p.code().isBlank()
                    && Pattern.compile("(?iU)\\b" + Pattern.quote(p.code().strip()) + "\\b").matcher(safeText).find()) {
                matched = true;
            }
            String dispLower = p.displayName() != null ? p.displayName().strip().toLowerCase(Locale.ROOT) : "";
            String dispNoPrefix = dispLower.replaceFirst("^gói\\s+", "").strip();
            if (!dispLower.isEmpty() && dispLower.length() >= 4 && maskedLower.contains(dispLower)) {
                matched = true;
                maskedLower = maskedLower.replace(dispLower, " ");
            } else if (!dispNoPrefix.isEmpty() && dispNoPrefix.length() >= 6 && maskedLower.contains(dispNoPrefix)) {
                matched = true;
                maskedLower = maskedLower.replace(dispNoPrefix, " ");
            }
            if (matched) {
                matchedByKey.put(pkgUniqueKey(p), p);
            }
        }

        Set<Long> quotedPrices = CleanAgentOutputRefereeAndSanitizer.extractPricesInVndFromText(safeText);
        for (Long priceVnd : quotedPrices) {
            if (priceVnd == null || priceVnd <= 0L) continue;
            boolean alreadyHasPrice = matchedByKey.values().stream().anyMatch(p -> p.priceVnd() == priceVnd);
            if (alreadyHasPrice) {
                continue;
            }
            for (PackageCatalogService.PackageSummary p : nonAddonCatalog) {
                if (p.priceVnd() == priceVnd) {
                    matchedByKey.put(pkgUniqueKey(p), p);
                }
            }
        }

        List<PackageCatalogService.PackageSummary> matchedList = new ArrayList<>(matchedByKey.values());
        if (matchedList.size() == 1) {
            return new QuotedReplyResolution(safeMid, safeText, false, false, matchedList.get(0), List.copyOf(matchedList), safeHistoryPkgs);
        }
        if (matchedList.size() >= 2) {
            return new QuotedReplyResolution(safeMid, safeText, false, true, null, List.copyOf(matchedList), safeHistoryPkgs);
        }
        return new QuotedReplyResolution(safeMid, safeText, true, false, null, List.of(), safeHistoryPkgs);
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
        boolean msgHasPriceObjection = CleanAgentOrchestratorService.isCleanPriceObjectionMessage(customerMessage)
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
                customerInsight,
                activeSkills,
                pendingBookingSlots,
                QuotedReplyResolution.empty()
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
            Map<String, String> pendingBookingSlots,
            QuotedReplyResolution quotedReplyResolution
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
                customerInsight,
                activeSkills,
                pendingBookingSlots,
                quotedReplyResolution,
                null,
                List.of()
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
            Map<String, String> pendingBookingSlots,
            QuotedReplyResolution quotedReplyResolution,
            UUID pageId,
            List<LearnedRuleDto> appliedRules
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
                customerInsight,
                activeSkills,
                pendingBookingSlots,
                quotedReplyResolution,
                pageId,
                appliedRules,
                null
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
            Map<String, String> pendingBookingSlots,
            QuotedReplyResolution quotedReplyResolution,
            UUID pageId,
            List<LearnedRuleDto> appliedRules,
            ConversationState.StructuredBookingState structuredBookingState
    ) {
        QuotedReplyResolution safeQuote = quotedReplyResolution != null ? quotedReplyResolution : QuotedReplyResolution.empty();
        String botName = resolveBotDisplayName(pagePersona);
        String pageName = (pagePersona != null && pagePersona.name() != null && !pagePersona.name().isBlank())
                ? pagePersona.name().strip()
                : "Page";
        String selfPronoun = (pagePersona != null && pagePersona.resolvedConfig() != null)
                ? pagePersona.resolvedConfig().resolvedSelfPronoun()
                : "em";
        String custPronoun = (customerPronoun != null && !customerPronoun.isBlank()) ? customerPronoun.strip() : "mình";
        String careActor = resolveCareActor(pagePersona);
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
        boolean hasKnownScopes = knownBodyAreas != null && !knownBodyAreas.isEmpty();
        boolean discoveryCompleted = conditionClarified
                || discoveryQuestionCount >= 1
                || (hasKnownScopes && !"chưa có".equals(safeTiming));
        String discoveryPolicyStatus = discoveryCompleted
                ? "ĐÃ HOÀN TẤT BƯỚC KHÁM PHÁ BAN ĐẦU — SẴN SÀNG TƯ VẤN PHƯƠNG ÁN & CẤM HỎI LẶP LẠI CÂU KHÁM PHÁ CŨ"
                : "CHƯA RÕ PHẠM VI ƯU TIÊN — HÃY ĐỒNG CẢM ẤM ÁP VỚI CẢM GIÁC CỦA KHÁCH VÀ HỎI NHẸ 1 CÂU VỀ PHẠM VI/KHU VỰC ƯU TIÊN ĐỂ ĐO NI ĐÓNG GIÀY PHƯƠNG ÁN";

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
        boolean hasPriceObjectionSignalInTurn = safeSignals.contains("PRICE_OBJECTION")
                || safeSignals.contains("PRICE_OBJECTION_FIRST_TIME")
                || safeSignals.contains("PRICE_OBJECTION_REPEAT_OR_DOWNSELL_REQUEST");
        boolean isOperationalQuestionTurn = (safeSignals.contains("OPERATIONAL_OR_LOGISTICS_QUESTION")
                || CleanAgentOrchestratorService.isOperationalOrLogisticsQuestion(customerMessage))
                && !hasPriceObjectionSignalInTurn
                && !safeSignals.contains("PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS")
                && !safeSignals.contains("POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE");
        boolean isServiceDetailQuestionTurn = !isOperationalQuestionTurn
                && (safeSignals.contains("SERVICE_OR_UNIT_DETAIL_QUESTION")
                    || safeSignals.contains("PROMO_OR_DISCOUNT_QUESTION"))
                && !hasPriceObjectionSignalInTurn
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

        boolean hasExplicitSixStateBooking = structuredBookingState != null;
        boolean awaitingProductSelectionInBooking = hasExplicitSixStateBooking
                && structuredBookingState.status() == ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION;
        PackageCatalogService.PackageSummary explicitlySelectedBookingPkg = (hasExplicitSixStateBooking
                && structuredBookingState.selectedProductId() != null
                && !structuredBookingState.selectedProductId().isBlank())
                ? findProductByIdInTieredCatalog(tieredCatalog, structuredBookingState.selectedProductId())
                : null;

        Set<String> currentMsgScopes = new LinkedHashSet<>(ConversationState.extractTargetScopes(customerMessage, List.of()));
        boolean postObjectionScopeRefinementToEconomical = hasEconomicalAlt
                && !isFirstPriceObjectionHoldTurn
                && !isPoliteClosingAckTurn
                && !isOperationalQuestionTurn
                && !safeQuote.hasReplyToContext()
                && (customerExplicitlyChoseDownsellPkg
                    || ((priceObjectionCount >= 1 || downsellAlreadyOfferedInHistory)
                        && (!currentMsgScopes.isEmpty() || downsellPresentedInLastBotTurn)
                        && tieredCatalog.economicalAlternatives().stream()
                                .anyMatch(e -> computePackageScopeDirectHits(e, currentMsgScopes, knownSymptoms) > 0)));
        PackageCatalogService.PackageSummary preferredEconomicalPkg = hasEconomicalAlt
                ? selectPreferredEconomicalAlternative(
                        tieredCatalog.economicalAlternatives(),
                        history,
                        heldPackageDisplay,
                        heldPriceDisplay,
                        customerMessage,
                        currentMsgScopes,
                        knownSymptoms
                )
                : null;

        String effectiveHeldPackageDisplay = heldPackageDisplay;
        String effectiveHeldPriceDisplay = heldPriceDisplay;
        if (explicitlySelectedBookingPkg != null && !isPoliteClosingAckTurn && !isOperationalQuestionTurn && !safeQuote.hasReplyToContext()) {
            effectiveHeldPackageDisplay = explicitlySelectedBookingPkg.canonicalLabel();
            effectiveHeldPriceDisplay = explicitlySelectedBookingPkg.priceDisplay();
        } else if (!awaitingProductSelectionInBooking
                && !isPoliteClosingAckTurn
                && !isOperationalQuestionTurn
                && !safeQuote.hasReplyToContext()
                && (isDownsellOrRepeatObjectionTurn
                    || postObjectionScopeRefinementToEconomical
                    || (!hasExplicitSixStateBooking && isBookingOrPositiveTrialTurn
                        && (customerExplicitlyChoseDownsellPkg || downsellPresentedInLastBotTurn || downsellAlreadyOfferedInHistory))
                    || (isServiceDetailQuestionTurn && downsellAlreadyOfferedInHistory))
                && preferredEconomicalPkg != null) {
            effectiveHeldPackageDisplay = preferredEconomicalPkg.canonicalLabel();
            effectiveHeldPriceDisplay = preferredEconomicalPkg.priceDisplay();
        }

        boolean heldPkgIsNull = (effectiveHeldPackageDisplay == null
                || effectiveHeldPackageDisplay.isBlank()
                || "null".equalsIgnoreCase(effectiveHeldPackageDisplay.strip()));
        String activePackageInFocus = awaitingProductSelectionInBooking
                ? "CHƯA CHỌN SẢN PHẨM (selected_product_id=null; lịch sử có từ 2 sản phẩm trở lên — BẮT BUỘC hỏi khách muốn đặt phương án nào trong danh sách đã tư vấn, TUYỆT ĐỐI KHÔNG tự mặc định lấy held_package)"
                : (!heldPkgIsNull
                    ? (effectiveHeldPackageDisplay + (effectiveHeldPriceDisplay != null && !"null".equals(effectiveHeldPriceDisplay) ? " (" + effectiveHeldPriceDisplay + ")" : ""))
                    : (!discoveryCompleted && !isServiceDetailQuestionTurn && !isOperationalQuestionTurn && !safeQuote.hasReplyToContext()
                        ? ("ĐANG Ở BƯỚC ĐỒNG CẢM & HỎI PHẠM VI ƯU TIÊN — Vỗ về cảm giác hiện tại của " + custPronoun
                            + " bằng ngôn từ ấm áp và hỏi nhẹ 1 câu về phạm vi/khu vực " + custPronoun
                            + " muốn ưu tiên nhất để chọn phương án 'đo ni đóng giày' (chưa vội báo giá ở lượt này)")
                        : "KHÁCH MỚI CHIA SẺ NHU CẦU, CHƯA BIẾT TÊN GÓI — BẠN PHẢI CHỦ ĐỘNG ĐỀ XUẤT GÓI SỐ 1 (\"Với tình trạng này, "
                            + selfPronoun + " gợi ý " + custPronoun + " tham khảo gói...\", CẤM viết \"" + custPronoun
                            + " đang quan tâm đến gói...\" và CẤM hỏi câu đối lập vô nghĩa \"thử 1 buổi lẻ trước hay đặt lịch ngay cho gói trọn vẹn\")"));
        String resolvedFunnelStatus = isPostBookingClosingAckTurn
                ? "CONFIRMED"
                : (isPoliteClosingAckTurn
                    ? "NONE"
                    : (hasExplicitSixStateBooking && structuredBookingState.status() != null
                        ? structuredBookingState.status().name()
                        : ((bookingFunnelStatus != null && !bookingFunnelStatus.isBlank())
                            ? ("COLLECTING_INFO".equals(bookingFunnelStatus) ? "COLLECTING_VISIT_TIME" : bookingFunnelStatus)
                            : (isBookingOrPositiveTrialTurn ? "COLLECTING_VISIT_TIME" : "NONE"))));

        PackageCatalogService.PackageSummary activePkgForBrain = awaitingProductSelectionInBooking
                ? null
                : (explicitlySelectedBookingPkg != null
                    ? explicitlySelectedBookingPkg
                    : ((!isPoliteClosingAckTurn
                        && !isOperationalQuestionTurn
                        && !safeQuote.hasReplyToContext()
                        && (isDownsellOrRepeatObjectionTurn
                            || isPriceContextHesitationTurn
                            || postObjectionScopeRefinementToEconomical
                            || (!hasExplicitSixStateBooking && isBookingOrPositiveTrialTurn
                                && (customerExplicitlyChoseDownsellPkg || downsellPresentedInLastBotTurn || downsellAlreadyOfferedInHistory))
                            || (isServiceDetailQuestionTurn && downsellAlreadyOfferedInHistory))
                        && preferredEconomicalPkg != null)
                        ? preferredEconomicalPkg
                        : findHeldOrTopPrimaryPackage(tieredCatalog, effectiveHeldPackageDisplay, effectiveHeldPriceDisplay)));

        String quotedPackageStateDisplay = "null";
        if (safeQuote.quotedPackage() != null) {
            PackageCatalogService.PackageSummary qPkg = safeQuote.quotedPackage();
            quotedPackageStateDisplay = qPkg.canonicalLabel() + " (" + qPkg.priceDisplay() + ")";
            if (!isPoliteClosingAckTurn && !isPostBookingClosingAckTurn && !isPureDeferralOrBusyTurn && !isMetaCommunicationTurn) {
                activePkgForBrain = qPkg;
                activePackageInFocus = quotedPackageStateDisplay
                        + " [QUOTED_PACKAGE — Khách đang Reply hỏi trực tiếp về gói này; trả lời đúng thông tin/ưu đãi của gói này, KHÔNG tự động gán accepted_package]";
            }
        } else if (safeQuote.ambiguousMultiplePackages()) {
            String matchedNames = safeQuote.matchedPackages().stream()
                    .map(p -> p.displayName() + " (" + p.priceDisplay() + ")")
                    .reduce((a, b) -> a + " | " + b)
                    .orElse("");
            quotedPackageStateDisplay = "AMBIGUOUS_MULTIPLE_PACKAGES [" + matchedNames + "]";
            activePkgForBrain = null;
            activePackageInFocus = "CHƯA RÕ GÓI ĐƯỢC CHỈ ĐỊNH TRONG BONG BÓNG REPLY (" + matchedNames
                    + ") — BẮT BUỘC HỎI XÁC NHẬN NGẮN GỌN XEM KHÁCH ĐANG MUỐN HỎI GÓI NÀO, TUYỆT ĐỐI KHÔNG MẶC ĐỊNH LẤY GÓI CŨ (held_package) LÀM ĐÁP ÁN DUY NHẤT";
        } else if (safeQuote.unresolvedReplyToMid()) {
            List<PackageCatalogService.PackageSummary> candidates = safeQuote.candidatePackages();
            quotedPackageStateDisplay = "UNRESOLVED_REPLY_TO_MID (" + safeQuote.replyToMid() + ")";
            activePkgForBrain = null;
            if (candidates.size() >= 2) {
                String candidateNames = candidates.stream()
                        .map(p -> p.canonicalLabel() + " (" + p.priceDisplay() + ")")
                        .reduce((a, b) -> a + " | " + b)
                        .orElse("");
                activePackageInFocus = "CHƯA XÁC ĐỊNH ĐƯỢC BONG BÓNG CŨ KHÁCH VỪA REPLY (reply_to.mid=" + safeQuote.replyToMid()
                        + " found=false) — BẮT BUỘC HỎI XÁC NHẬN NGẮN GỌN GIỮA CÁC GÓI ĐÃ XUẤT HIỆN TRONG LỊCH SỬ [" + candidateNames
                        + "], TUYỆT ĐỐI KHÔNG MẶC ĐỊNH LẤY held_package LÀM ĐÁP ÁN DUY NHẤT";
            } else if (candidates.size() == 1) {
                PackageCatalogService.PackageSummary c1 = candidates.get(0);
                activePackageInFocus = "CHƯA XÁC ĐỊNH ĐƯỢC BONG BÓNG CŨ KHÁCH VỪA REPLY (reply_to.mid=" + safeQuote.replyToMid()
                        + " found=false) — BẮT BUỘC HỎI XÁC NHẬN XEM KHÁCH ĐANG HỎI GÓI " + c1.canonicalLabel() + " (" + c1.priceDisplay()
                        + ") HAY MỘT GÓI KHÁC TRONG BẢNG GIÁ, TUYỆT ĐỐI KHÔNG TỰ Ý TRẢ LỜI NHƯ THỂ ĐÃ CHẮC CHẮN";
            } else {
                activePackageInFocus = "CHƯA XÁC ĐỊNH ĐƯỢC BONG BÓNG CŨ KHÁCH VỪA REPLY VÀ LỊCH SỬ CHƯA CÓ GÓI NÀO (reply_to.mid=" + safeQuote.replyToMid()
                        + " found=false) — BẮT BUỘC HỎI MỞ LỊCH SỰ ĐỂ KHÁCH NHẮN LẠI TÊN GÓI HOẶC MỨC GIÁ ĐANG QUAN TÂM, TUYỆT ĐỐI CẤM ĐOÁN MÒ BẤT KỲ TÊN GÓI HAY MỨC GIÁ NÀO";
            }
        }

        String acceptedPackageStateDisplay = awaitingProductSelectionInBooking
                ? "null"
                : ((packageInterestAccepted && activePkgForBrain != null && (safeQuote.quotedPackage() == null || hasExplicitSixStateBooking))
                    ? (activePkgForBrain.canonicalLabel() + " (" + activePkgForBrain.priceDisplay() + ")")
                    : (packageInterestAccepted && !heldPkgIsNull && (safeQuote.quotedPackage() == null || hasExplicitSixStateBooking)
                        ? (effectiveHeldPackageDisplay + (effectiveHeldPriceDisplay != null && !"null".equals(effectiveHeldPriceDisplay) ? " (" + effectiveHeldPriceDisplay + ")" : ""))
                        : "null"));

        StringBuilder sb = new StringBuilder(5120);
        sb.append("<CLEAN_AGENT_SYSTEM_PROMPT>\n")
          .append(systemPrompt.strip())
          .append("\n</CLEAN_AGENT_SYSTEM_PROMPT>\n")
          .append("<!-- SYSTEM_USER_SPLIT -->\n\n");
        int structuredTurnContractInsertOffset = sb.length();

        List<String> burstSubMessages = extractBurstSubMessages(customerMessage);
        boolean isProductComparisonTurn = isProductComparisonQuestionText(customerMessage);
        String precedingBotTurnText = extractLatestPrecedingAssistantText(history);
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
                activeSkills,
                safeQuote,
                isOperationalQuestionTurn,
                burstSubMessages,
                careActor,
                pageId,
                structuredBookingState,
                safeSignals,
                lastBotQuestion,
                isProductComparisonTurn
        );

        String selectedProductIdDisplay = (structuredBookingState != null && structuredBookingState.selectedProductId() != null)
                ? structuredBookingState.selectedProductId()
                : (acceptedPackageStateDisplay != null && !"null".equals(acceptedPackageStateDisplay) && activePkgForBrain != null
                    ? activePkgForBrain.productId()
                    : "null");
        String bookingDateDisplay = (structuredBookingState != null && structuredBookingState.bookingDate() != null)
                ? structuredBookingState.bookingDate()
                : "null";
        String bookingTimeDisplay = (structuredBookingState != null && structuredBookingState.bookingTime() != null)
                ? structuredBookingState.bookingTime()
                : "null";

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
          .append("   - quoted_package: \"").append(quotedPackageStateDisplay).append("\"\n")
          .append("   - held_package: \"").append(!heldPkgIsNull ? effectiveHeldPackageDisplay : "null").append("\"\n")
          .append("   - accepted_package: \"").append(acceptedPackageStateDisplay).append("\"\n")
          .append("   - selected_product_id: \"").append(selectedProductIdDisplay).append("\"\n")
          .append("   - booking_date: \"").append(bookingDateDisplay).append("\"\n")
          .append("   - booking_time: \"").append(bookingTimeDisplay).append("\"\n")
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
        } else if (safeQuote.ambiguousMultiplePackages() && !safeQuote.matchedPackages().isEmpty()) {
            sb.append("[CÁC GÓI CÙNG XUẤT HIỆN TRONG BONG BÓNG KHÁCH VỪA REPLY (TRẢ LỜI RÕ HOẶC HỎI XÁC NHẬN KHÁCH ĐANG MUỐN HỎI GÓI NÀO)]:\n");
            for (int i = 0; i < safeQuote.matchedPackages().size(); i++) {
                PackageCatalogService.PackageSummary mp = safeQuote.matchedPackages().get(i);
                appendCatalogPackageLine(sb, i + 1, mp, knownBodyAreas, knownSymptoms);
                visiblePackagesForTurn.add(mp);
            }
        } else if (safeQuote.unresolvedReplyToMid()) {
            List<PackageCatalogService.PackageSummary> candidates = safeQuote.candidatePackages();
            if (candidates.isEmpty()) {
                sb.append("[KHÔNG TÌM THẤY NỘI DUNG BONG BÓNG CŨ ĐƯỢC REPLY & LỊCH SỬ TRỐNG — TUYỆT ĐỐI KHÔNG TỰ ĐOÁN MÒ TÊN GÓI HAY MỨC GIÁ CỤ THỂ; CHỈ HỎI MỞ LỊCH SỰ ĐỂ KHÁCH NHẮN LẠI TÊN GÓI HOẶC MỨC GIÁ ĐANG QUAN TÂM]\n");
            } else {
                sb.append("[KHÔNG TÌM THẤY NỘI DUNG BONG BÓNG CŨ ĐƯỢC REPLY (reply_to.mid) — CÁC GÓI ĐÃ XUẤT HIỆN TRONG LỊCH SỬ HỘI THOẠI ĐỂ HỎI XÁC NHẬN LỊCH SỰ]:\n");
                for (int i = 0; i < Math.min(3, candidates.size()); i++) {
                    appendCatalogPackageLine(sb, i + 1, candidates.get(i), knownBodyAreas, knownSymptoms);
                    visiblePackagesForTurn.add(candidates.get(i));
                }
            }
        } else if (safeQuote.quotedPackage() != null && !isBookingOrPositiveTrialTurn) {
            sb.append("[GÓI TRONG BONG BÓNG KHÁCH VỪA REPLY (QUOTED_PACKAGE — TRẢ LỜI ĐÚNG THÔNG TIN/ƯU ĐÃI CỦA GÓI NÀY, CHƯA ÉP GIỜ ĐẶT LỊCH)]:\n");
            appendCatalogPackageLine(sb, 1, safeQuote.quotedPackage(), knownBodyAreas, knownSymptoms);
            visiblePackagesForTurn.add(safeQuote.quotedPackage());
        } else if (tieredCatalog == null
                || (tieredCatalog.primaryRecommendations().isEmpty() && tieredCatalog.economicalAlternatives().isEmpty())) {
            if (tieredCatalog != null && tieredCatalog.noMatchingPackageForRequestedScope()) {
                sb.append("NO_MATCHING_PACKAGE_FOR_REQUESTED_SCOPE: Hiện tại trong danh mục dịch vụ của Page chưa có gói đáp ứng đúng vùng/nhu cầu khách vừa nêu. Hãy trả lời trung thực và đề xuất chuyển chuyên viên tư vấn hỗ trợ trực tiếp, tuyệt đối không lấy gói lệch vùng ra báo giá.\n");
            } else {
                sb.append("NO_MATCHING_PACKAGE_FOR_REQUESTED_SCOPE: (Chưa có gói dịch vụ khớp trực tiếp trong danh mục)\n");
            }
        } else if (isOperationalQuestionTurn) {
            sb.append("(Khách đang hỏi thông tin vận hành / giờ đóng mở cửa / chỗ đỗ xe / địa chỉ — Trả lời trực tiếp, rõ ràng, lịch sự câu hỏi vận hành của khách dựa trên <DOCUMENT_CONTEXT>, TUYỆT ĐỐI KHÔNG tự ý nhồi hay gạ lại sản phẩm/gói tiết kiệm khi khách không hỏi về gói.)\n");
            if (activePkgForBrain != null) {
                appendCatalogPackageLine(sb, 1, activePkgForBrain, knownBodyAreas, knownSymptoms);
                visiblePackagesForTurn.add(activePkgForBrain);
            }
        } else if (isServiceDetailQuestionTurn && activePkgForBrain != null) {
            if (isProductComparisonTurn && tieredCatalog != null) {
                sb.append("[CÁC PHƯƠNG ÁN ĐANG ĐƯỢC ĐỐI CHIẾU SO SÁNH (SO SÁNH TINH TẾ, TRUNG THỰC PHẠM VI & TRẢI NGHIỆM, KHÔNG DÌM HÀNG PHƯƠNG ÁN TIẾT KIỆM, CHƯA ÉP GIỜ ĐẶT LỊCH)]:\n");
                List<PackageCatalogService.PackageSummary> comparisonList = resolveComparisonPackagesFromHistory(
                        tieredCatalog,
                        history,
                        effectiveHeldPackageDisplay,
                        effectiveHeldPriceDisplay,
                        activePkgForBrain,
                        preferredEconomicalPkg,
                        priceObjectionCount
                );
                for (int i = 0; i < comparisonList.size(); i++) {
                    appendCatalogPackageLine(sb, i + 1, comparisonList.get(i), knownBodyAreas, knownSymptoms);
                    visiblePackagesForTurn.add(comparisonList.get(i));
                }
            } else {
                sb.append("[GÓI KHÁCH ĐANG MUỐN NGHE CHI TIẾT (CHIA SẺ RÕ QUYỀN LỢI & QUY TRÌNH, CHƯA ÉP GIỜ ĐẶT LỊCH)]:\n");
                appendCatalogPackageLine(sb, 1, activePkgForBrain, knownBodyAreas, knownSymptoms);
                visiblePackagesForTurn.add(activePkgForBrain);
            }
        } else if (isPriceContextHesitationTurn) {
            PackageCatalogService.PackageSummary heldPkg = findHeldOrTopPrimaryPackage(
                    tieredCatalog, effectiveHeldPackageDisplay, effectiveHeldPriceDisplay);
            if (heldPkg != null) {
                sb.append("[GÓI CHỦ LỰC KHÁCH ĐANG CÂN NHẮC]:\n");
                appendCatalogPackageLine(sb, 1, heldPkg, knownBodyAreas, knownSymptoms);
                visiblePackagesForTurn.add(heldPkg);
            }
            sb.append("[NHÓM 2 — PHƯƠNG ÁN TIẾT KIỆM / HẠ BẬC (DÙNG ĐỂ GỢI Ý MỀM 1 LẦN KHÔNG ÉP BUỘC KHI KHÁCH LƯỠNG LỰ VÌ GIÁ)]:\n");
            List<PackageCatalogService.PackageSummary> economicals = orderEconomicalsForPresentation(
                    tieredCatalog.economicalAlternatives(), preferredEconomicalPkg);
            for (int i = 0; i < Math.min(2, economicals.size()); i++) {
                appendCatalogPackageLine(sb, i + 1, economicals.get(i), knownBodyAreas, knownSymptoms);
                visiblePackagesForTurn.add(economicals.get(i));
            }
        } else if (isBookingOrPositiveTrialTurn) {
            if (awaitingProductSelectionInBooking) {
                List<PackageCatalogService.PackageSummary> candidates = resolveBookingCandidateProducts(
                        tieredCatalog, history, structuredBookingState
                );
                sb.append("[CÁC PHƯƠNG ÁN ĐÃ TRAO ĐỔI TRONG LỊCH SỬ — KHÁCH CHƯA CHỌN RÕ SẢN PHẨM (AWAITING_PRODUCT_SELECTION), HÃY HỎI KHÁCH MUỐN ĐẶT PHƯƠNG ÁN NÀO]:\n");
                for (int i = 0; i < candidates.size(); i++) {
                    appendCatalogPackageLine(sb, i + 1, candidates.get(i), knownBodyAreas, knownSymptoms);
                    visiblePackagesForTurn.add(candidates.get(i));
                }
                if (structuredBookingState.bookingDate() != null) {
                    sb.append("- Ngày dự kiến đã ghi nhận (GIỮ NGUYÊN, CẤM HỎI LẠI NGÀY): ").append(structuredBookingState.bookingDate()).append('\n');
                }
                if (structuredBookingState.bookingTime() != null) {
                    sb.append("- Khung giờ dự kiến đã ghi nhận: ").append(structuredBookingState.bookingTime()).append('\n');
                } else {
                    sb.append("- Khung giờ cụ thể: chưa có — hãy hỏi kèm xem khách tiện vào mấy giờ.\n");
                }
            } else {
                PackageCatalogService.PackageSummary selectedPkg = explicitlySelectedBookingPkg != null
                        ? explicitlySelectedBookingPkg
                        : findHeldOrTopPrimaryPackage(
                                tieredCatalog, effectiveHeldPackageDisplay, effectiveHeldPriceDisplay);
                sb.append("[GÓI KHÁCH ĐANG QUAN TÂM / ĐẶT LỊCH TRẢI NGHIỆM]:\n");
                if (selectedPkg != null) {
                    appendCatalogPackageLine(sb, 1, selectedPkg, knownBodyAreas, knownSymptoms);
                    visiblePackagesForTurn.add(selectedPkg);
                } else {
                    sb.append("1. ").append(activePackageInFocus).append('\n');
                }
                if (structuredBookingState != null) {
                    sb.append("- Trạng thái đặt lịch (bookingStatus): ").append(structuredBookingState.status().name())
                      .append(" | bookingDate=").append(structuredBookingState.bookingDate() != null ? structuredBookingState.bookingDate() : "chưa có")
                      .append(" | bookingTime=").append(structuredBookingState.bookingTime() != null ? structuredBookingState.bookingTime() : "chưa có")
                      .append('\n');
                } else {
                    String visitTimeSlot = (pendingBookingSlots != null && pendingBookingSlots.get("visit_time") != null)
                            ? pendingBookingSlots.get("visit_time")
                            : "chưa có khung giờ cụ thể — hãy mời khách chọn khung giờ thuận tiện hôm nay hoặc ngày mai";
                    sb.append("- Trạng thái khung giờ dự kiến ghé (pendingBookingSlots): ").append(visitTimeSlot).append('\n');
                }
            }
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
                List<PackageCatalogService.PackageSummary> orderedEcon = orderEconomicalsForPresentation(economicals, preferredEconomicalPkg);
                int maxEcon = Math.min(2, orderedEcon.size());
                for (int i = 0; i < maxEcon; i++) {
                    appendCatalogPackageLine(sb, i + 1, orderedEcon.get(i), knownBodyAreas, knownSymptoms);
                    visiblePackagesForTurn.add(orderedEcon.get(i));
                }
            }
        } else if (!discoveryCompleted
                && !isProactivePriceOrCatalogRequestText(customerMessage)
                && !customerExplicitlyAskedForEconomicalGroup(customerMessage, tieredCatalog.economicalAlternatives())) {
            sb.append("(Đang ở bước Khám phá nhu cầu & Phạm vi ưu tiên — Khách vừa chia sẻ trạng thái/băn khoăn nhưng CHƯA nêu rõ phạm vi ưu tiên và CHƯA chủ động đòi bảng giá ngay. TẠM ẨN danh sách báo giá ở lượt này: hãy vỗ về, đồng cảm ấm áp với cảm giác của khách trước và hỏi nhẹ 1 câu về phạm vi/khu vực khách muốn ưu tiên nhất để 'đo ni đóng giày' đúng giải pháp, TUYỆT ĐỐI chưa đọc tên sản phẩm hay con số giá ở lượt này.)\n");
        } else {
            boolean showEconomicalTierInStage1 = hasEconomicalAlt
                    && (customerExplicitlyAskedForEconomicalGroup(customerMessage, tieredCatalog.economicalAlternatives())
                        || postObjectionScopeRefinementToEconomical
                        || priceObjectionCount >= 1
                        || downsellAlreadyOfferedInHistory);
            List<PackageCatalogService.PackageSummary> orderedEconomicals = showEconomicalTierInStage1
                    ? orderEconomicalsForPresentation(tieredCatalog.economicalAlternatives(), preferredEconomicalPkg)
                    : List.of();
            if (postObjectionScopeRefinementToEconomical && !orderedEconomicals.isEmpty()) {
                sb.append("[PHƯƠNG ÁN TIẾT KIỆM KHỚP ĐÚNG PHẠM VI / YÊU CẦU KHÁCH VỪA NÊU (BẮT BUỘC ƯU TIÊN ĐỀ XUẤT VÌ KHÁCH ĐÃ BĂN KHOĂN GIÁ TRƯỚC ĐÓ HOẶC CHỦ ĐỘNG CHỌN DÒNG NÀY — TUYỆT ĐỐI KHÔNG ĐẨY LÊN GÓI ĐẮT HƠN)]:\n");
                for (int i = 0; i < Math.min(2, orderedEconomicals.size()); i++) {
                    appendCatalogPackageLine(sb, i + 1, orderedEconomicals.get(i), knownBodyAreas, knownSymptoms);
                    visiblePackagesForTurn.add(orderedEconomicals.get(i));
                }
                List<PackageCatalogService.PackageSummary> primaries = tieredCatalog.primaryRecommendations();
                if (!primaries.isEmpty()) {
                    sb.append("[NHÓM THAM KHẢO THÊM — GÓI CHUYÊN SÂU / PHẠM VI RỘNG HƠN (CHỈ ĐỂ ĐỐI CHIẾU NẾU CẦN, KHÔNG ÉP CHỌN)]:\n");
                    appendCatalogPackageLine(sb, 1, primaries.get(0), knownBodyAreas, knownSymptoms);
                    visiblePackagesForTurn.add(primaries.get(0));
                }
            } else {
                sb.append("[NHÓM 1 — GÓI CHỦ LỰC ĐỀ XUẤT CHÍNH (PRIMARY_RECOMMENDATIONS)]:\n");
                List<PackageCatalogService.PackageSummary> primaries = tieredCatalog.primaryRecommendations();
                int maxPrimary = Math.min(2, primaries.size());
                for (int i = 0; i < maxPrimary; i++) {
                    appendCatalogPackageLine(sb, i + 1, primaries.get(i), knownBodyAreas, knownSymptoms);
                    visiblePackagesForTurn.add(primaries.get(i));
                }
                if (showEconomicalTierInStage1 && !orderedEconomicals.isEmpty()) {
                    sb.append("[NHÓM 2 — PHƯƠNG ÁN TIẾT KIỆM / HẠ BẬC (ECONOMICAL_DOWNSELL_OR_ALTERNATIVES)]:\n");
                    for (int i = 0; i < Math.min(2, orderedEconomicals.size()); i++) {
                        appendCatalogPackageLine(sb, i + 1, orderedEconomicals.get(i), knownBodyAreas, knownSymptoms);
                        visiblePackagesForTurn.add(orderedEconomicals.get(i));
                    }
                }
            }
        }
        if (activePkgForBrain != null
                && (discoveryCompleted || isServiceDetailQuestionTurn || isOperationalQuestionTurn || safeQuote.hasReplyToContext())
                && !isPureDeferralOrBusyTurn
                && !isMetaCommunicationTurn
                && !isPostBookingClosingAckTurn
                && !visiblePackagesForTurn.contains(activePkgForBrain)) {
            visiblePackagesForTurn.add(activePkgForBrain);
        }
        sb.append("</SCOPE_FILTERED_CATALOG>\n\n");

        String resolvedPageUnitNoun = resolveEffectivePageUnitNoun(pagePersona, visiblePackagesForTurn, tieredCatalog);
        List<AgentSkillDto> governedSkillsForContract = new ArrayList<>();
        if (activeSkills != null) {
            for (AgentSkillDto s : activeSkills) {
                if (governedSkillsForContract.size() >= 1) {
                    break;
                }
                if (isSkillAllowedForPageAndTurn(
                        s,
                        pageId,
                        resolvedStage,
                        safeQuote,
                        isOperationalQuestionTurn,
                        visiblePackagesForTurn
                )) {
                    governedSkillsForContract.add(s);
                }
            }
        }
        StructuredTurnContract turnContract = buildStructuredTurnContract(
                safeSignals,
                safeQuote,
                burstSubMessages,
                resolvedStage,
                resolvedPageUnitNoun,
                discoveryCompleted,
                knownSymptoms,
                knownBodyAreas,
                safeTiming,
                selectedProductIdDisplay,
                bookingDateDisplay,
                bookingTimeDisplay,
                resolvedFunnelStatus,
                priceObjectionCount,
                customerInsight,
                governedSkillsForContract,
                isOperationalQuestionTurn,
                isServiceDetailQuestionTurn,
                visiblePackagesForTurn,
                precedingBotTurnText,
                lastBotQuestion
        );
        sb.insert(structuredTurnContractInsertOffset, renderStructuredTurnContractXml(turnContract));

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

        String governedPlaybookSection = buildGovernedTenantPlaybookSection(
                pagePersona,
                pageId,
                resolvedStage,
                safeSignals,
                safeQuote,
                isOperationalQuestionTurn,
                visiblePackagesForTurn,
                activeSkills,
                appliedRules,
                customerMessage
        );
        if (!governedPlaybookSection.isEmpty()) {
            sb.append(governedPlaybookSection);
        }

        sb.append("<CONVERSATION_HISTORY>\n");
        int turnCounter = 0;
        if (history != null && !history.isEmpty()) {
            int startIdx = Math.max(0, history.size() - 8);
            for (int i = startIdx; i < history.size(); i++) {
                FanpageBotRequest.ConversationTurn t = history.get(i);
                if (t == null || t.content() == null || t.content().isBlank()) continue;
                turnCounter++;
                String roleLabel = ("assistant".equalsIgnoreCase(t.role()) || "bot".equalsIgnoreCase(t.role()))
                        ? "Bot"
                        : "Khách";
                sb.append("[T").append(turnCounter).append("] ")
                  .append(roleLabel).append(": ").append(t.content().strip().replaceAll("\\s+", " ")).append('\n');
            }
        }
        if (safeQuote.quotedBubbleText() != null && !safeQuote.quotedBubbleText().isBlank()) {
            sb.append("Bong bóng tin nhắn cũ khách vừa Reply (reply_to.mid=")
              .append(safeQuote.replyToMid())
              .append("): \"")
              .append(safeQuote.quotedBubbleText().replaceAll("\\s+", " ").strip())
              .append("\"\n");
        } else if (safeQuote.unresolvedReplyToMid()) {
            sb.append("Khách vừa Reply vào một tin nhắn cũ (reply_to.mid=")
              .append(safeQuote.replyToMid())
              .append(") nhưng không tìm thấy nội dung bong bóng cũ trong bộ nhớ/DB.\n");
        }
        if (burstSubMessages.size() >= 2) {
            sb.append("[CHỈ DẪN GỘP TIN NHẮN LIÊN TIẾP (MULTI-MESSAGE BURST — KHÁCH VỪA GỬI ")
              .append(burstSubMessages.size())
              .append(" TIN NHẮN LIÊN TIẾP, BẮT BUỘC TRẢ LỜI ĐẦY ĐỦ TẤT CẢ CÁC Ý HỎI BÊN DƯỚI TRONG CÙNG LƯỢT NÀY, KHÔNG BỎ SÓT CÂU NÀO)]:\n");
            for (int i = 0; i < burstSubMessages.size(); i++) {
                sb.append("  + Tin nhắn ").append(i + 1).append(": \"").append(burstSubMessages.get(i)).append("\"\n");
            }
        }
        sb.append("[T_CURRENT] Khách (tin nhắn hiện tại): ").append(customerMessage != null ? customerMessage.strip() : "").append('\n');
        sb.append("</CONVERSATION_HISTORY>");
        return sb.toString();
    }

    public static List<String> extractBurstSubMessages(String customerMessage) {
        if (customerMessage == null || customerMessage.isBlank()) {
            return List.of();
        }
        String[] rawLines = customerMessage.split("\\r?\\n");
        List<String> lines = new ArrayList<>();
        for (String line : rawLines) {
            if (line != null && !line.isBlank()) {
                lines.add(line.strip());
            }
        }
        return lines;
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
        appendCleanAgentBrainInsightBlock(
                sb,
                selfPronoun,
                custPronoun,
                knownSymptoms,
                knownBodyAreas,
                safeTiming,
                discoveryCompleted,
                activePackageInFocus,
                activePkg,
                priceObjectionCount,
                stageLabel,
                isServiceDetailQuestionTurn,
                customerInsight,
                activeSkills,
                QuotedReplyResolution.empty(),
                false,
                List.of()
        );
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
            List<AgentSkillDto> activeSkills,
            QuotedReplyResolution quotedReplyResolution
    ) {
        appendCleanAgentBrainInsightBlock(
                sb,
                selfPronoun,
                custPronoun,
                knownSymptoms,
                knownBodyAreas,
                safeTiming,
                discoveryCompleted,
                activePackageInFocus,
                activePkg,
                priceObjectionCount,
                stageLabel,
                isServiceDetailQuestionTurn,
                customerInsight,
                activeSkills,
                quotedReplyResolution,
                false,
                List.of()
        );
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
            List<AgentSkillDto> activeSkills,
            QuotedReplyResolution quotedReplyResolution,
            boolean isOperationalQuestionTurn,
            List<String> burstSubMessages
    ) {
        appendCleanAgentBrainInsightBlock(
                sb,
                selfPronoun,
                custPronoun,
                knownSymptoms,
                knownBodyAreas,
                safeTiming,
                discoveryCompleted,
                activePackageInFocus,
                activePkg,
                priceObjectionCount,
                stageLabel,
                isServiceDetailQuestionTurn,
                customerInsight,
                activeSkills,
                quotedReplyResolution,
                isOperationalQuestionTurn,
                burstSubMessages,
                "chuyên viên",
                null
        );
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
            List<AgentSkillDto> activeSkills,
            QuotedReplyResolution quotedReplyResolution,
            boolean isOperationalQuestionTurn,
            List<String> burstSubMessages,
            String careActor,
            UUID pageId
    ) {
        appendCleanAgentBrainInsightBlock(
                sb,
                selfPronoun,
                custPronoun,
                knownSymptoms,
                knownBodyAreas,
                safeTiming,
                discoveryCompleted,
                activePackageInFocus,
                activePkg,
                priceObjectionCount,
                stageLabel,
                isServiceDetailQuestionTurn,
                customerInsight,
                activeSkills,
                quotedReplyResolution,
                isOperationalQuestionTurn,
                burstSubMessages,
                careActor,
                pageId,
                null
        );
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
            List<AgentSkillDto> activeSkills,
            QuotedReplyResolution quotedReplyResolution,
            boolean isOperationalQuestionTurn,
            List<String> burstSubMessages,
            String careActor,
            UUID pageId,
            ConversationState.StructuredBookingState structuredBookingState
    ) {
        appendCleanAgentBrainInsightBlock(
                sb,
                selfPronoun,
                custPronoun,
                knownSymptoms,
                knownBodyAreas,
                safeTiming,
                discoveryCompleted,
                activePackageInFocus,
                activePkg,
                priceObjectionCount,
                stageLabel,
                isServiceDetailQuestionTurn,
                customerInsight,
                activeSkills,
                quotedReplyResolution,
                isOperationalQuestionTurn,
                burstSubMessages,
                careActor,
                pageId,
                structuredBookingState,
                List.of(),
                "không có",
                false
        );
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
            List<AgentSkillDto> activeSkills,
            QuotedReplyResolution quotedReplyResolution,
            boolean isOperationalQuestionTurn,
            List<String> burstSubMessages,
            String careActor,
            UUID pageId,
            ConversationState.StructuredBookingState structuredBookingState,
            List<String> turnSignals,
            String lastBotQuestion,
            boolean isProductComparisonTurn
    ) {
        QuotedReplyResolution safeQuote = quotedReplyResolution != null ? quotedReplyResolution : QuotedReplyResolution.empty();
        List<String> safeBurst = burstSubMessages != null ? burstSubMessages : List.of();
        List<String> safeTurnSignals = turnSignals != null ? turnSignals : List.of();
        String safeCareActor = (careActor != null && !careActor.isBlank()) ? careActor.strip() : "chuyên viên";
        boolean isMetaCommunicationTurn = "STAGE_5_META_COMMUNICATION_REPAIR".equals(stageLabel);
        boolean isPureDeferralOrBusyTurn = "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE".equals(stageLabel);
        boolean isPostBookingClosingAckTurn = "STAGE_4B_POST_BOOKING_CONFIRMED_CLOSING".equals(stageLabel);
        boolean isPriceContextHesitationTurn = "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL".equals(stageLabel);
        boolean isBookingOrPositiveTrialTurn = "STAGE_4_POSITIVE_TRIAL_OR_BOOKING".equals(stageLabel);
        boolean isFirstPriceObjectionHoldTurn = "STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE".equals(stageLabel);
        boolean isDownsellOrRepeatObjectionTurn = "STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION".equals(stageLabel);
        boolean isWarmGeneralInfoAckTurn = "STAGE_1_DISCOVERY_AND_FIRST_QUOTE".equals(stageLabel)
                && (safeTurnSignals.contains("ACKNOWLEDGED_GENERAL_INFO")
                    || safeTurnSignals.contains("LUKEWARM_POST_QUOTE_CHECKIN"));

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
                : "sản phẩm/gói dịch vụ phù hợp";
        String pkgDur = (activePkg != null && activePkg.durationLabel() != null && !activePkg.durationLabel().isBlank())
                ? (" " + activePkg.durationLabel().strip())
                : "";
        String pkgPrice = (activePkg != null && activePkg.formattedPriceWithUnit() != null && !activePkg.formattedPriceWithUnit().isBlank())
                ? activePkg.formattedPriceWithUnit().strip()
                : "theo niêm yết";
        String pkgPriceLabel = (activePkg != null && activePkg.priceLabel() != null && !activePkg.priceLabel().isBlank())
                ? activePkg.priceLabel().strip().toLowerCase(Locale.ROOT)
                : "mức giá niêm yết";
        String pkgSpace = (activePkg != null && activePkg.customerFacingSpaceNote() != null && !activePkg.customerFacingSpaceNote().isBlank())
                ? activePkg.customerFacingSpaceNote().strip()
                : "tiêu chuẩn phục vụ chu đáo";
        String pkgIncludes = (activePkg != null && activePkg.includesSummary() != null && !activePkg.includesSummary().isBlank())
                ? activePkg.includesSummary().strip()
                : "đầy đủ hạng mục theo hồ sơ";
        String needLabel = (knownSymptoms != null && !knownSymptoms.isEmpty())
                ? knownSymptoms.get(0)
                : ((knownBodyAreas != null && !knownBodyAreas.isEmpty()) ? knownBodyAreas.get(0) : "nhu cầu hiện tại");
        String partialScopeNote = buildPartialScopeLimitationNote(activePkg, knownBodyAreas, knownSymptoms);
        boolean isAnatomicalScopePkg = activePkg != null
                && activePkg.display() != null
                && "Vùng chăm sóc".equals(activePkg.display().scopeLabel());
        String partialScopeHint = partialScopeNote.isEmpty()
                ? ""
                : (isAnatomicalScopePkg
                    ? (" " + partialScopeNote + " — nói rõ trung thực gói " + pkgPrice + " tập trung đúng vùng trong phạm vi của gói trước, chưa bao gồm các vùng còn lại ngoài phạm vi như gói toàn thân")
                    : (" " + partialScopeNote + " — nói rõ trung thực phương án " + pkgPrice + " tập trung đúng phạm vi của mục này trước, chưa bao gồm các phạm vi còn lại"));

        String tacticalFocus;
        String goodExample;
        String antiPattern;

        if (safeQuote.unresolvedReplyToMid()) {
            List<PackageCatalogService.PackageSummary> candidates = safeQuote.candidatePackages();
            if (candidates.size() >= 2) {
                PackageCatalogService.PackageSummary p1 = candidates.get(0);
                PackageCatalogService.PackageSummary p2 = candidates.get(1);
                String u1 = p1.unitNoun();
                String p1Full = formatClarificationCandidateWithUnit(p1, u1);
                String p2Full = formatClarificationCandidateWithUnit(p2, p2.unitNoun());
                tacticalFocus = "Khách vừa Reply vào một tin nhắn cũ (reply_to.mid=" + safeQuote.replyToMid() + " found=false) và dùng đại từ chỉ định. TUYỆT ĐỐI KHÔNG tự mặc định lấy held_package hay " + u1 + " gần nhất làm câu trả lời duy nhất, và KHÔNG tự bịa chính sách giảm giá combo. Hãy hỏi xác nhận ngắn gọn giữa đúng các " + u1 + " đã tư vấn trong lịch sử: " + p1Full + " giá " + p1.formattedPriceWithUnit() + " hay " + p2Full + " giá " + p2.formattedPriceWithUnit() + ".";
                goodExample = "Dạ " + custPronoun + " đang hỏi ưu đãi cho " + p1Full + " giá " + p1.formattedPriceWithUnit() + " hay " + p2Full + " giá " + p2.formattedPriceWithUnit() + " ạ? " + BubbleTextSanitizer.capitalizeFirstLetter(selfPronoun) + " xác nhận đúng rồi báo " + custPronoun + " chính xác nhé.";
                antiPattern = "Mặc định lấy " + u1 + " gần nhất làm câu trả lời duy nhất, tự bịa ưu đãi combo, hoặc ép chốt lịch khi chưa rõ khách Reply vào " + u1 + " nào.";
            } else if (candidates.size() == 1) {
                PackageCatalogService.PackageSummary p1 = candidates.get(0);
                String u1 = p1.unitNoun();
                String p1Full = formatClarificationCandidateWithUnit(p1, u1);
                tacticalFocus = "Khách vừa Reply vào một tin nhắn cũ (reply_to.mid=" + safeQuote.replyToMid() + " found=false). Trong lịch sử mới xuất hiện 1 " + u1 + " là " + p1Full + " (" + p1.formattedPriceWithUnit() + "). TUYỆT ĐỐI KHÔNG tự mặc định khách chắc chắn hỏi " + u1 + " này — hãy hỏi xác nhận ngắn gọn xem " + custPronoun + " đang hỏi " + p1Full + " giá " + p1.formattedPriceWithUnit() + " hay một " + u1 + " khác trong bảng giá để " + selfPronoun + " kiểm tra chính xác.";
                goodExample = "Dạ không biết " + custPronoun + " đang muốn hỏi thông tin ưu đãi của " + p1Full + " giá " + p1.formattedPriceWithUnit() + " hay một " + u1 + " khác trong bảng giá bên " + selfPronoun + " ạ? " + BubbleTextSanitizer.capitalizeFirstLetter(custPronoun) + " xác nhận giúp " + selfPronoun + " để " + selfPronoun + " báo chính xác nhất nhé ạ.";
                antiPattern = "Trả lời khẳng định một chiều cho 1 " + u1 + " mà không hỏi xác nhận xem khách hỏi " + u1 + " đó hay " + u1 + " khác.";
            } else {
                tacticalFocus = "Khách vừa Reply vào một tin nhắn ở rất xa (reply_to.mid=" + safeQuote.replyToMid() + " found=false) và hiện chưa có lịch sử sản phẩm/dịch vụ nào trong phiên. TUYỆT ĐỐI KHÔNG đoán mò bất kỳ tên sản phẩm/gói hay con số giá nào. Hãy hỏi mở lịch sự để khách nhắn lại tên sản phẩm/gói hoặc mức giá khách đang quan tâm.";
                goodExample = "Dạ do tin nhắn " + custPronoun + " trích dẫn ở khá xa nên " + selfPronoun + " chưa hiển thị lại được nội dung, " + custPronoun + " nhắn giúp " + selfPronoun + " tên sản phẩm/gói hoặc mức giá " + custPronoun + " đang quan tâm để " + selfPronoun + " kiểm tra chính xác nhất nhé ạ?";
                antiPattern = "Tự đoán mò tên gói hoặc nêu đại một con số giá bất kỳ khi lịch sử trống và chưa phân giải được tin nhắn trích dẫn.";
            }
        } else if (safeQuote.ambiguousMultiplePackages() && safeQuote.matchedPackages().size() >= 2) {
            PackageCatalogService.PackageSummary p1 = safeQuote.matchedPackages().get(0);
            PackageCatalogService.PackageSummary p2 = safeQuote.matchedPackages().get(1);
            String p1Desc = p1.displayName() + " (" + p1.formattedPriceWithUnit() + ")";
            String p2Desc = p2.displayName() + " (" + p2.formattedPriceWithUnit() + ")";
            tacticalFocus = "Bong bóng tin nhắn khách vừa Reply có nhắc tới từ 2 mục trở lên (" + p1Desc + " và " + p2Desc + "). TUYỆT ĐỐI KHÔNG tự mặc định lấy held_package làm đáp án duy nhất và KHÔNG tự bịa chính sách giảm giá combo. Hãy hỏi xác nhận ngắn gọn xem " + custPronoun + " đang hỏi mục " + p1.formattedPriceWithUnit() + " hay mục " + p2.formattedPriceWithUnit() + " để " + selfPronoun + " kiểm tra chính xác ạ.";
            goodExample = "Dạ trong tin nhắn trên có cả " + p1Desc + " và " + p2Desc + ", không biết " + custPronoun + " đang muốn hỏi mục " + p1.formattedPriceWithUnit() + " hay mục " + p2.formattedPriceWithUnit() + " để " + selfPronoun + " kiểm tra ưu đãi và chi tiết chính xác cho " + custPronoun + " ạ?";
            antiPattern = "Chỉ bốc đại 1 con số giá đơn lẻ trong bong bóng chứa nhiều mục mà bỏ qua mục còn lại.";
        } else if (isOperationalQuestionTurn) {
            tacticalFocus = "Khách đang hỏi câu hỏi vận hành / giờ mở đóng cửa / chỗ đỗ xe / địa chỉ / giao nhận. Hãy trả lời trực tiếp, rõ ràng, ấm áp đúng trọng tâm câu hỏi vận hành dựa trên <DOCUMENT_CONTEXT>, TUYỆT ĐỐI KHÔNG tự ý nhồi hay gạ lại sản phẩm/gói tiết kiệm khi khách không hỏi về gói.";
            goodExample = "Dạ bên " + selfPronoun + " mở cửa đón khách đến 21h00 mỗi tối và có khu vực đỗ xe thuận tiện ngay trước sảnh cho " + custPronoun + " ạ.";
            antiPattern = "Tự ý nhồi đoạn quảng cáo sản phẩm/gói tiết kiệm vào câu trả lời giờ đóng cửa hoặc địa chỉ.";
        } else if (isMetaCommunicationTurn) {
            tacticalFocus = "Nhận lỗi duyên dáng, hóm hỉnh đúng vào điều khách vừa nói/hỏi vặn; tạm dừng chào bán và hỏi thăm tự nhiên.";
            goodExample = "Dạ " + selfPronoun + " suy đoán hơi vội trước khi hỏi kỹ " + custPronoun + " rồi ạ. Không biết nhu cầu " + needLabel + " của " + custPronoun + " cụ thể đang trong bối cảnh nào vậy ạ?";
            antiPattern = "Lờ đi lời phàn nàn/hỏi vặn của khách để đọc lại bảng giá hoặc hỏi lặp lại câu cũ.";
        } else if (isPureDeferralOrBusyTurn) {
            String savedCareSummary = !storedCareNotes.isEmpty()
                    ? (" và các lưu ý đã ghi nhận (" + storedCareNotes.get(storedCareNotes.size() - 1) + ")")
                    : " và các lưu ý đã trao đổi";
            tacticalFocus = "Tôn trọng hoàn toàn nhịp độ và thời gian của khách khi khách đang bận, xin tạm dừng cân nhắc hoặc nhắn lời xác nhận khép lại ('Ok', 'Ừ', 'Vâng'); tách lời chào tạm biệt thành 2 bong bóng ngắn gọn, giàu cảm xúc: Bong bóng 1 xác nhận " + selfPronoun + " đã lưu lại thông tin" + savedCareSummary + " của " + custPronoun + " để bất cứ khi nào " + custPronoun + " thu xếp được thời gian nhắn lại thì hỗ trợ ngay (kết thúc bằng dấu '.'); Bong bóng 2 tách riêng lời chúc " + custPronoun + " một ngày thật nhiều niềm vui và thuận lợi. TUYỆT ĐỐI KHÔNG chào mời thêm sản phẩm/gói mới và CẤM hỏi ép chốt lịch.";
            goodExample = "Dạ vâng ạ, " + selfPronoun + " đã lưu lại thông tin cùng các lưu ý của " + custPronoun + " rồi ạ. Bất cứ khi nào " + custPronoun + " thu xếp được thời gian cần hỗ trợ thì cứ nhắn " + selfPronoun + " ngay nhé. | Chúc " + custPronoun + " một ngày thật nhiều niềm vui và thuận lợi ạ!";
            antiPattern = "Cố nhồi thêm gói dịch vụ mới hoặc hỏi ép khách chốt giờ đặt lịch ngay khi khách đã báo bận/hẹn suy nghĩ thêm.";
        } else if (isPostBookingClosingAckTurn) {
            tacticalFocus = "Lịch hẹn/yêu cầu của khách ĐÃ XÁC NHẬN (CONFIRMED). Khách vừa nhắn xác nhận ngắn ('Ok', 'Vâng') để khép lại cuộc trò chuyện — chỉ trả lời 1 bong bóng ngắn gọn, ấm áp cảm ơn và hẹn hỗ trợ " + custPronoun + " đúng lịch đã đặt, TUYỆT ĐỐI CẤM hỏi lại khung giờ đặt lịch.";
            goodExample = "Dạ vâng ạ, " + selfPronoun + " cảm ơn " + custPronoun + " nhiều ạ. Bên " + selfPronoun + " rất mong được đón tiếp và phục vụ chu đáo cho " + custPronoun + " đúng lịch hẹn ạ.";
            antiPattern = "Hỏi lặp lại 'Mình tiện ghé khung giờ nào?' khi lịch hẹn đã được xác nhận.";
        } else if (isServiceDetailQuestionTurn || safeQuote.quotedPackage() != null) {
            if (isProductComparisonTurn && safeQuote.quotedPackage() == null) {
                tacticalFocus = "Khách đang hỏi so sánh sự khác biệt giữa các phương án đã trao đổi. Hãy đối chiếu tinh tế, ấm áp và trung thực dựa trên <SCOPE_FILTERED_CATALOG> (sự khác biệt về phạm vi đáp ứng, quy trình thực hiện hoặc thời lượng/quy cách phục vụ), giúp " + custPronoun + " thấy phương án nào cũng được đội ngũ " + safeCareActor + " thực hiện chu đáo; TUYỆT ĐỐI KHÔNG dìm hàng phương án tiết kiệm và chưa thúc ép chốt lịch.";
                goodExample = "Dạ điểm khác biệt chính giữa các phương án nằm ở phạm vi và mức độ chuyên sâu để " + custPronoun + " dễ chọn theo đúng nhu cầu hiện tại ạ. Dù chọn phương án nào thì đội ngũ " + safeCareActor + " bên " + selfPronoun + " vẫn chăm chút tỉ mỉ từng bước; không biết hiện tại " + custPronoun + " đang nghiêng về phạm vi nào hơn ạ?";
                antiPattern = "Dìm hàng phương án tiết kiệm là làm qua loa, gán nhầm quyền lợi độc quyền của mục cao cấp sang mục tiêu chuẩn, hoặc ép chốt lịch ngay.";
            } else {
                tacticalFocus = "Khách đang hỏi thông tin chi tiết hoặc chương trình khuyến mãi/ưu đãi về ĐÚNG mục (" + pkgName + pkgDur + " - " + pkgPrice + ")" + partialScopeHint + " — nếu trong <SCOPE_FILTERED_CATALOG> và <DOCUMENT_CONTEXT> không có chương trình giảm giá riêng, hãy báo trung thực rằng " + pkgPrice + " hiện là " + pkgPriceLabel + " của " + pkgName + pkgDur + " (bao gồm " + pkgIncludes + " | " + pkgSpace + ", đúng quyền lợi niêm yết trong hồ sơ), TUYỆT ĐỐI KHÔNG tự bịa voucher/combo giảm giá ngoài dữ liệu, KHÔNG nhảy sang mục khác và chưa thúc ép chốt lịch.";
                goodExample = "Dạ hiện tại " + pkgName + pkgDur + " bên " + selfPronoun + " đang áp dụng " + pkgPriceLabel + " là " + pkgPrice + " (chưa có chương trình giảm giá riêng ạ), đã bao gồm đầy đủ " + pkgIncludes + " (" + pkgSpace + ") theo đúng niêm yết của Page ạ.";
                antiPattern = "Trả lời nhầm sang sản phẩm/gói khác khi khách đã Reply vào bong bóng " + pkgPrice + ", tự bịa chính sách khuyến mãi/voucher/combo ngoài dữ liệu, hoặc ép khách chốt lịch ngay.";
            }
        } else if (isPriceContextHesitationTurn) {
            tacticalFocus = "Trả lời trong 2 bong bóng ấm áp, mềm mại: Bong bóng 1 giải tỏa hoàn toàn áp lực (trấn an " + custPronoun + " cứ thoải mái cân nhắc thêm, chưa cần vội chốt ngay); Bong bóng 2 bắc một nhịp cầu mềm mại không ép buộc sang phương án tiết kiệm hơn cho " + needLabel + " là " + pkgName + pkgDur + " (" + pkgPrice + ")" + partialScopeHint + " để " + custPronoun + " thấy mình luôn có lựa chọn nhẹ nhàng mà vẫn được chăm sóc chu đáo.";
            goodExample = "Dạ không sao đâu ạ, " + custPronoun + " cứ thoải mái tham khảo và cân nhắc thêm, chưa cần vội quyết định ngay đâu ạ. Nếu " + custPronoun + " muốn bắt đầu bằng một phương án nhẹ nhàng, tiết kiệm hơn tập trung đúng phạm vi ưu tiên trước thì bên " + selfPronoun + " cũng có " + pkgName + pkgDur + " (" + pkgPrice + "), " + custPronoun + " xem thử có hợp ý mình hơn không nhé ạ.";
            antiPattern = "Hỏi ép khách chốt ngay lập tức hoặc lặp lại nguyên văn đoạn quảng cáo của lượt trước.";
        } else if (isFirstPriceObjectionHoldTurn) {
            String avoidRepeatClosing = (lastBotQuestion != null && !lastBotQuestion.isBlank() && !"không có".equals(lastBotQuestion))
                    ? (" (TUYỆT ĐỐI KHÔNG lặp lại nguyên văn câu hỏi kết lượt trước: \"" + lastBotQuestion + "\")")
                    : "";
            tacticalFocus = "Giữ nguyên mục đang tư vấn (" + pkgName + pkgDur + " - " + pkgPrice + "). Áp dụng triệt để nguyên tắc Conversational Delta: KHÔNG đọc lại danh sách thuộc tính/tiện ích phụ vừa nêu ở lượt trước như cái máy, mà hãy đồng cảm nhẹ nhàng và khắc họa chiều sâu trải nghiệm thực tế, sự tỉ mỉ của đội ngũ " + safeCareActor + " cùng cảm giác dễ chịu/an tâm thực chất mà " + custPronoun + " nhận được cho nhu cầu " + needLabel + "; kết lượt bằng một câu hỏi mở tự nhiên khác biệt" + avoidRepeatClosing + "; TUYỆT ĐỐI CẤM tự gạ phương án tiết kiệm hơn.";
            goodExample = "Dạ " + selfPronoun + " hiểu cảm giác cân nhắc của " + custPronoun + " ạ. Điều làm nên giá trị của " + pkgName + pkgDur + " (" + pkgPrice + ") nằm ở sự chăm chút tỉ mỉ của đội ngũ " + safeCareActor + " trong suốt quá trình thực hiện để giải tỏa trọn vẹn tình trạng " + needLabel + ", giúp " + custPronoun + " cảm nhận sự khác biệt rõ rệt thay vì chỉ xử lý lướt qua ạ. Không biết với nhu cầu hiện tại, điều " + custPronoun + " đang ưu tiên nhất là cảm giác thoải mái lâu dài hay yếu tố nào để " + selfPronoun + " chia sẻ kỹ hơn ạ?";
            antiPattern = "Đọc lại nguyên văn danh sách tiện ích phụ vừa liệt kê ở lượt báo giá trước, lặp lại y hệt điệp khúc 'Mình thử 1 buổi nhé?', hoặc tự ý gạ phương án tiết kiệm hơn ngay lần chê đắt đầu tiên.";
        } else if (isDownsellOrRepeatObjectionTurn) {
            tacticalFocus = "BẮT BUỘC giới thiệu phương án tiết kiệm trong [NHÓM 2] (" + pkgName + pkgDur + " - " + pkgPrice + ") kèm ĐÚNG hình thức/không gian (" + pkgSpace + "), quyền lợi (" + pkgIncludes + ") và giải thích chuẩn xác rằng " + pkgPrice + " là lựa chọn tiết kiệm nhất trong nhóm sản phẩm/dịch vụ phù hợp với nhu cầu của khách" + partialScopeHint + ". Nếu khách vừa hỏi một mức giá thấp chưa tồn tại, giải thích nhẹ nhàng rằng hiện bên " + selfPronoun + " chưa có phương án phù hợp ở mức giá đó rồi giới thiệu " + pkgPrice + " để khách thoải mái cân nhắc (không chuyển Handoff). Khi [NHÓM 2] có mục hợp lệ, TUYỆT ĐỐI CẤM nói gói Nhóm 1 là 'mức thấp nhất bên em có', CẤM tự khẳng định " + pkgPrice + " là 'mức thấp nhất toàn danh mục của Page' và CẤM chê bai mức giá thấp hơn gói Nhóm 1 là 'chỉ làm qua loa hời hợt'.";
            goodExample = "Dạ trong nhóm phương án đáp ứng nhu cầu " + needLabel + " của " + custPronoun + ", lựa chọn tiết kiệm nhất hiện tại bên " + selfPronoun + " là " + pkgName + pkgDur + " (" + pkgPrice + "), bao gồm " + pkgIncludes + " (" + pkgSpace + ") ạ.";
            antiPattern = "Khẳng định sai sự thật rằng gói chủ lực là mức giá thấp nhất, tự nhận phương án Nhóm 2 là mức thấp nhất toàn danh mục của Page, đề xuất mục lệch phạm vi nhu cầu, hoặc chê bai phương án tiết kiệm.";
        } else if (isBookingOrPositiveTrialTurn) {
            String careNoteHint = !storedCareNotes.isEmpty()
                    ? (" đồng thời khéo léo nhắc lại lưu ý chăm sóc đã ghi nhận (" + storedCareNotes.get(storedCareNotes.size() - 1) + ") theo đúng trạng thái: \"" + selfPronoun + " lưu lại lưu ý này để khi phục vụ, " + safeCareActor + " chuẩn bị chu đáo cho " + custPronoun + " nhé ạ\" (CẤM nói quá khứ \"" + selfPronoun + " đã dặn " + safeCareActor + "\" khi chưa xác nhận lịch)")
                    : " (nói rõ \"" + selfPronoun + " lưu lại để khi phục vụ sẽ trao đổi kỹ với " + safeCareActor + "\", CẤM nói quá khứ \"" + selfPronoun + " đã dặn " + safeCareActor + "\" khi chưa xác nhận lịch)";
            String careNoteExampleTail = !storedCareNotes.isEmpty()
                    ? (" " + BubbleTextSanitizer.capitalizeFirstLetter(selfPronoun) + " lưu lại lưu ý \"" + storedCareNotes.get(storedCareNotes.size() - 1) + "\" này để " + safeCareActor + " chuẩn bị chu đáo cho " + custPronoun + " nhé ạ.")
                    : "";
            ConversationState.BookingStatus bStatus = structuredBookingState != null && structuredBookingState.status() != null
                    ? structuredBookingState.status()
                    : null;
            if (bStatus == ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION) {
                String bDate = structuredBookingState.bookingDate();
                String bTime = structuredBookingState.bookingTime();
                if (bDate != null && !bDate.isBlank()) {
                    tacticalFocus = "Khách muốn đặt cho mốc '" + bDate + "' nhưng trong lịch sử đang có từ 2 phương án trở lên và khách CHƯA chọn rõ sản phẩm (selectedProductId == null). BẮT BUỘC giữ nguyên mốc '" + bDate + "' (TUYỆT ĐỐI CẤM hỏi lại ngày!), hỏi lịch sự xem '" + bDate + "' " + custPronoun + " muốn đặt phương án nào trong danh sách đã tư vấn" + (bTime == null ? " và dự kiến vào mấy giờ" : "") + "; CẤM tự mặc định gán khách đã chọn bất kỳ mục nào.";
                    goodExample = "Dạ cho lịch " + bDate + ", không biết " + custPronoun + " muốn đặt phương án nào trong các mục bên " + selfPronoun + " vừa tư vấn" + (bTime == null ? " và dự kiến vào khoảng mấy giờ" : "") + " để " + selfPronoun + " kiểm tra lịch trống chuẩn xác cho " + custPronoun + " ạ?";
                    antiPattern = "Tự ý gán khách đã chọn một phương án cụ thể trong danh sách khi khách chưa chỉ định, hoặc hỏi lại 'mình muốn đặt ngày nào' khi khách đã nói '" + bDate + "'.";
                } else {
                    tacticalFocus = "Khách vừa đồng ý đặt lịch/đặt hàng nhưng trong lịch sử đang có từ 2 phương án trở lên và khách CHƯA chọn rõ sản phẩm (selectedProductId == null). Hãy hỏi lịch sự xem " + custPronoun + " muốn đặt phương án nào trong danh sách đã tư vấn; TUYỆT ĐỐI CẤM tự mặc định lấy held_package hay phương án gần nhất làm lựa chọn của khách.";
                    goodExample = "Dạ vâng ạ, không biết " + custPronoun + " muốn đặt phương án nào trong các mục bên " + selfPronoun + " vừa tư vấn để " + selfPronoun + " hỗ trợ giữ lịch chuẩn xác cho " + custPronoun + " ạ?";
                    antiPattern = "Tự ý khẳng định khách đã chọn 1 gói cụ thể khi lịch sử có từ 2 gói trở lên và khách chưa chỉ định.";
                }
            } else if (bStatus == ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK) {
                String bDate = structuredBookingState.bookingDate() != null ? structuredBookingState.bookingDate() : "ngày hẹn";
                String bTime = structuredBookingState.bookingTime() != null ? structuredBookingState.bookingTime() : "khung giờ yêu cầu";
                tacticalFocus = "Ghi nhận đầy đủ, trân trọng yêu cầu đặt phương án (" + pkgName + pkgDur + " - " + pkgPrice + ") vào '" + bDate + "' lúc '" + bTime + "'" + careNoteHint + ", và thông báo rõ bên " + selfPronoun + " kiểm tra lịch trống / chỗ trống thực tế để xác nhận chính thức ngay cho " + custPronoun + "; TUYỆT ĐỐI CẤM tuyên bố đã đặt lịch thành công chính thức (CONFIRMED) khi chưa kiểm tra lịch trống!";
                goodExample = "Dạ " + selfPronoun + " xin ghi nhận yêu cầu đặt " + pkgName + pkgDur + " (" + pkgPrice + ") vào " + bDate + " lúc " + bTime + " của " + custPronoun + " ạ." + careNoteExampleTail + " Để " + selfPronoun + " kiểm tra chỗ trống thực tế khung giờ này rồi xác nhận chính thức ngay để chuẩn bị đón tiếp " + custPronoun + " thật chu đáo nhé ạ.";
                antiPattern = "Tuyên bố sai sự thật rằng 'đã đặt lịch thành công', 'đã chốt lịch chính thức' khi trạng thái mới là AWAITING_AVAILABILITY_CHECK, hoặc nói quá khứ 'Em đã dặn " + safeCareActor + "'.";
            } else if (bStatus == ConversationState.BookingStatus.AWAITING_DATE_OR_TIME) {
                String bDate = structuredBookingState.bookingDate();
                if (bDate != null && !bDate.isBlank()) {
                    tacticalFocus = "Đón nhận tín hiệu đồng thuận của khách bằng sự ấm áp, xác nhận hỗ trợ đặt đúng phương án khách đã chọn (" + pkgName + pkgDur + " - " + pkgPrice + ") vào '" + bDate + "'" + careNoteHint + ", giữ nguyên mốc '" + bDate + "' (CẤM hỏi lại ngày) và nhẹ nhàng hỏi thêm khung giờ thuận tiện; tuyệt đối không quay lại gạ phương án khác và không chào tạm biệt.";
                    goodExample = "Dạ vâng ạ, " + selfPronoun + " rất vui được chuẩn bị " + pkgName + pkgDur + " (" + pkgPrice + ") cho " + custPronoun + " vào " + bDate + " nha." + careNoteExampleTail + " Không biết " + bDate + " " + custPronoun + " tiện ghé khoảng mấy giờ để bên " + selfPronoun + " sắp xếp chu đáo nhất cho mình ạ?";
                } else {
                    tacticalFocus = "Đón nhận cái gật đầu đồng ý của khách bằng sự trân trọng, ấm áp, xác nhận chuẩn bị đúng phương án (" + pkgName + pkgDur + " - " + pkgPrice + ")" + careNoteHint + " và khéo léo hỏi xem hôm nay hay ngày mai khách tiện ghé khoảng mấy giờ để chuẩn bị đón tiếp chu đáo; tuyệt đối không quay lại gạ phương án khác và tuyệt đối không chúc tạm biệt đẩy trách nhiệm nhắn lại cho khách.";
                    goodExample = "Dạ vâng ạ, vậy để " + selfPronoun + " chuẩn bị " + pkgName + pkgDur + " (" + pkgPrice + ") thật chu đáo cho " + custPronoun + " nhé." + careNoteExampleTail + " Hôm nay hay ngày mai " + custPronoun + " tiện ghé khoảng mấy giờ để bên " + selfPronoun + " kiểm tra và giữ khung giờ đẹp cho mình ạ?";
                }
                antiPattern = "Chúc ngủ ngon / chào tạm biệt bỏ rơi khách khi khách vừa gật đầu 'uk' đồng ý thử, quay lại gạ phương án khác khi khách đã chọn rõ " + pkgPrice + ", hoặc nói quá khứ 'Em đã dặn " + safeCareActor + "' khi chưa xác nhận lịch.";
            } else {
                tacticalFocus = "Đón nhận tín hiệu đồng thuận của khách bằng sự ấm áp, chốt thẳng vào phương án vừa thống nhất (" + pkgName + pkgDur + " - " + pkgPrice + ")" + careNoteHint + " và nhẹ nhàng rủ khách chọn khung giờ thuận tiện hôm nay hoặc ngày mai; tuyệt đối không hỏi ngược bắt khách chọn lại giữa các mục.";
                goodExample = "Dạ vâng ạ, vậy " + selfPronoun + " giữ phương án " + pkgName + pkgDur + " (" + pkgPrice + ") cho " + custPronoun + " nha." + careNoteExampleTail + " Không biết hôm nay hay ngày mai " + custPronoun + " tiện ghé vào khoảng mấy giờ để bên " + selfPronoun + " chuẩn bị chu đáo nhất cho mình ạ?";
                antiPattern = "Hỏi ngược bắt khách chọn lại giữa 2 mục, vừa hỏi giờ vừa gạ đổi sang gói tiết kiệm hơn, hoặc nói quá khứ 'Em đã dặn " + safeCareActor + "' khi chưa chốt lịch.";
            }
        } else if (isWarmGeneralInfoAckTurn) {
            tacticalFocus = "Khách vừa đáp ngắn ('uk', 'ừ', 'ok') để tiếp nhận thông tin chung / báo giá vừa nêu (chưa phải gật đầu chốt lịch sau lời mời thử gói cụ thể). KHÔNG ép chốt giờ đặt lịch thô bạo và TUYỆT ĐỐI KHÔNG chúc tạm biệt ngắt ngang hội thoại; hãy giữ nhịp trò chuyện ấm áp, gần gũi — hỏi nhẹ cảm nhận của " + custPronoun + " hoặc điều " + custPronoun + " còn đang băn khoăn để dẫn dắt tự nhiên.";
            goodExample = "Dạ nghe qua thông tin phương án " + pkgName + pkgDur + " (" + pkgPrice + ") này thì " + custPronoun + " thấy có hợp với mong muốn hiện tại của mình không, hay " + custPronoun + " còn băn khoăn điểm nào để " + selfPronoun + " chia sẻ chi tiết hơn cho mình ạ?";
            antiPattern = "Ép khách chốt ngay khung giờ đặt lịch khi khách mới 'uk' tiếp nhận thông tin chung, hoặc chúc tạm biệt cắt đứt mạch hội thoại.";
        } else if (!discoveryCompleted) {
            tacticalFocus = "Khách vừa chia sẻ cảm giác/tình trạng (" + needLabel + ") nhưng chưa nêu rõ phạm vi ưu tiên và chưa đòi bảng giá ngay. Hãy vỗ về, đồng cảm ấm áp với trạng thái của " + custPronoun + " trước bằng ngôn từ giàu sự thấu hiểu, không đoán mò nguyên nhân, không khuyên đi khám lạnh lùng, và khéo léo hỏi nhẹ 1 câu xem " + custPronoun + " đang muốn ưu tiên chăm sóc/xử lý ở vùng hay phạm vi nào nhất để 'đo ni đóng giày' đúng phương án (chưa báo tên sản phẩm hay giá ở lượt này).";
            goodExample = "Dạ nghe " + custPronoun + " chia sẻ tình trạng " + needLabel + " chắc hẳn đang mệt và khó chịu lắm ạ. Để bên " + selfPronoun + " chọn đúng phương án 'đo ni đóng giày' vừa vặn nhất, hiện tại " + custPronoun + " đang muốn ưu tiên tập trung vào khu vực hay phạm vi nào nhất ạ?";
            antiPattern = "Xổ ngay bảng giá khi khách chưa nêu phạm vi ưu tiên, tự suy diễn nguyên nhân khi khách chưa kể, hoặc chèn câu miễn trừ y tế lạnh lùng.";
        } else {
            boolean activeIsEconomical = activePkg != null
                    && activePkg.commercialRole() == PackageCatalogService.CommercialRole.ECONOMICAL_OPTION;
            String recommendedSlotLabel = activeIsEconomical
                    ? "phương án vừa vặn nhất khớp đúng phạm vi/yêu cầu khách vừa chọn (" + pkgName + pkgDur + " - " + pkgPrice + ")"
                    : "mục Số 1 trong [NHÓM 1] (" + pkgName + pkgDur + " - " + pkgPrice + ")";
            tacticalFocus = "Đồng cảm ấm áp với nhu cầu " + needLabel + ", chủ động đề xuất cuốn hút " + recommendedSlotLabel + ", gợi mở giá trị trải nghiệm thực tế cùng quyền lợi đi kèm (" + pkgIncludes + " | " + pkgSpace + "), và kết lượt bằng một câu hỏi mở mềm mại mời trải nghiệm hoặc hỏi cảm nhận phù hợp của " + custPronoun + ".";
            goodExample = "Dạ với tình trạng " + needLabel + " của " + custPronoun + ", " + selfPronoun + " gợi ý " + custPronoun + " tham khảo " + pkgName + pkgDur + " (" + pkgPrice + ") do đội ngũ " + safeCareActor + " trực tiếp thực hiện tỉ mỉ để mang lại cảm giác thoải mái trọn vẹn nhất ạ. Phương án này đã bao gồm " + pkgIncludes + " (" + pkgSpace + "). Không biết " + custPronoun + " thấy hướng chăm sóc này có vừa vặn với mong muốn của mình không ạ?";
            antiPattern = "Viết '" + custPronoun + " đang quan tâm đến...' khi khách chưa biết tên sản phẩm/gói, đẩy lên gói đắt hơn khi khách đã băn khoăn giá và đã có phương án tiết kiệm khớp vùng, đọc thông số khô khan như tờ rơi, hoặc hỏi câu đối lập vô nghĩa.";
        }

        if (safeBurst.size() >= 2) {
            tacticalFocus = tacticalFocus + " [MULTI-MESSAGE BURST: Khách vừa nhắn liên tiếp " + safeBurst.size()
                    + " câu (" + String.join(" | ", safeBurst)
                    + ") — BẮT BUỘC trả lời đầy đủ tất cả các câu hỏi của khách trong lượt này, tuyệt đối không bỏ sót ý nào.]";
        }

        List<PackageCatalogService.PackageSummary> activePkgList = activePkg != null ? List.of(activePkg) : List.of();
        if (activeSkills != null && !activeSkills.isEmpty()) {
            for (AgentSkillDto candSkill : activeSkills) {
                if (!isSkillAllowedForPageAndTurn(
                        candSkill,
                        pageId,
                        stageLabel,
                        safeQuote,
                        isOperationalQuestionTurn,
                        activePkgList
                )) {
                    continue;
                }
                boolean skipDiscoveryExampleWhenAlreadyClarified = discoveryCompleted
                        && candSkill.code() != null
                        && candSkill.code().toLowerCase(Locale.ROOT).contains("discovery");
                if (candSkill.goodExample() != null && !candSkill.goodExample().isBlank()
                        && !skipDiscoveryExampleWhenAlreadyClarified && !isWarmGeneralInfoAckTurn
                        && !isFirstPriceObjectionHoldTurn && !isDownsellOrRepeatObjectionTurn
                        && !isPriceContextHesitationTurn && !isPureDeferralOrBusyTurn && !isBookingOrPositiveTrialTurn
                        && !isPostBookingClosingAckTurn && !isServiceDetailQuestionTurn && !isOperationalQuestionTurn
                        && !safeQuote.unresolvedReplyToMid() && !safeQuote.ambiguousMultiplePackages()
                        && !hasUnverifiedPriceInDirective(candSkill.goodExample(), activePkgList)) {
                    goodExample = candSkill.goodExample().strip();
                }
                if (candSkill.antiPattern() != null && !candSkill.antiPattern().isBlank()
                        && !skipDiscoveryExampleWhenAlreadyClarified && !isWarmGeneralInfoAckTurn
                        && !isFirstPriceObjectionHoldTurn && !isDownsellOrRepeatObjectionTurn
                        && !isPriceContextHesitationTurn && !isPureDeferralOrBusyTurn && !isBookingOrPositiveTrialTurn
                        && !isPostBookingClosingAckTurn && !isServiceDetailQuestionTurn && !isOperationalQuestionTurn
                        && !safeQuote.unresolvedReplyToMid() && !safeQuote.ambiguousMultiplePackages()) {
                    antiPattern = candSkill.antiPattern().strip();
                }
                break;
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

    public record StructuredProductConstraint(
            String productId,
            String rawSkuCode,
            String canonicalName,
            String unitNoun,
            String commercialRole,
            String priceLabel,
            String formattedPriceWithUnit,
            long priceAmountVnd,
            String billingUnit,
            String formattedSpecsSummary,
            List<String> scopeTags
    ) {
        public static StructuredProductConstraint fromPackageSummary(PackageCatalogService.PackageSummary p, String fallbackUnitNoun) {
            PackageCatalogService.CatalogSourceData src = p.source();
            PackageCatalogService.CatalogNormalizedData norm = p.normalized();
            PackageCatalogService.CatalogInferredData inf = p.inferred();
            PackageCatalogService.CatalogDisplayData disp = p.display();
            String uNoun = (disp != null && disp.unitNoun() != null && !disp.unitNoun().isBlank())
                    ? disp.unitNoun()
                    : ((fallbackUnitNoun != null && !fallbackUnitNoun.isBlank()) ? fallbackUnitNoun : "sản phẩm");
            return new StructuredProductConstraint(
                    norm != null && norm.productId() != null ? norm.productId() : p.productId(),
                    src != null && src.rawSkuCode() != null ? src.rawSkuCode() : (p.code() != null ? p.code() : ""),
                    norm != null && norm.canonicalName() != null ? norm.canonicalName() : p.canonicalName(),
                    uNoun,
                    inf != null && inf.commercialRole() != null ? inf.commercialRole().name() : p.commercialRole().name(),
                    disp != null && disp.priceLabel() != null ? disp.priceLabel() : p.priceLabel(),
                    disp != null && disp.formattedPriceWithUnit() != null ? disp.formattedPriceWithUnit() : p.formattedPriceWithUnit(),
                    norm != null ? norm.priceAmountVnd() : p.priceAmountVnd(),
                    norm != null && norm.billingUnit() != null ? norm.billingUnit() : p.billingUnit(),
                    disp != null && disp.formattedSpecsSummary() != null ? disp.formattedSpecsSummary() : p.formattedSpecsSummary(),
                    inf != null && inf.scopeTags() != null ? inf.scopeTags() : p.scopeTags()
            );
        }
    }

    public static final String EMOTIONAL_HOSPITALITY_CONTRACT_DIRECTIVE =
            "Sử dụng ngôn từ ấm áp, gợi cảm giác trải nghiệm thực tế và giải tỏa đúng nỗi băn khoăn của khách theo tone_style và agent_skills của Page; không lặp lại danh sách thuộc tính phụ đã nêu ở lượt liền trước (Conversational Delta)";

    public record StructuredTurnContract(
            String primaryIntent,
            List<String> detectedSignals,
            String replyResolutionState,
            String replyToMid,
            int burstMessageCount,
            String stage,
            String unitNoun,
            boolean discoveryCompleted,
            List<String> verifiedScopes,
            String verifiedContextOrTiming,
            String selectedProductId,
            String bookingDate,
            String bookingTime,
            String bookingStatus,
            int priceObjectionCount,
            String buyerArchetype,
            String workingHypothesis,
            int buyingTemperature,
            String lastActualSentSpeechAct,
            List<String> activeSkillCodes,
            String primaryGoal,
            List<String> allowedActions,
            List<String> forbiddenActions,
            List<StructuredProductConstraint> commercialProducts,
            boolean allowUngroundedPromo,
            boolean allowCrossTierAttributeLeak,
            boolean allowGhostProductOnClosing,
            String emotionalHospitalityDirective,
            boolean conversationalDeltaRequired,
            String conversationalDeltaRule,
            String closingCadenceRule,
            String bubbleRhythmGuide
    ) {
    }

    private static String formatClarificationCandidateWithUnit(
            PackageCatalogService.PackageSummary pkg,
            String unitNoun
    ) {
        String effectiveUnit = (unitNoun != null && !unitNoun.isBlank()) ? unitNoun.strip() : "sản phẩm";
        if (pkg == null) {
            return effectiveUnit;
        }
        String label = pkg.canonicalLabel();
        if (label.isEmpty()) {
            return effectiveUnit;
        }
        String lowUnit = effectiveUnit.toLowerCase(Locale.ROOT);
        String lowLabel = label.toLowerCase(Locale.ROOT);
        if (lowLabel.startsWith(lowUnit + " ")) {
            String rest = label.substring(effectiveUnit.length()).strip();
            return lowUnit + " " + BubbleTextSanitizer.capitalizeFirstLetter(rest);
        }
        return lowUnit + " " + BubbleTextSanitizer.capitalizeFirstLetter(label);
    }

    public static String resolveEffectivePageUnitNoun(
            PromptBuilder.PagePersona pagePersona,
            List<PackageCatalogService.PackageSummary> visiblePackages,
            TieredCatalogResult tieredCatalog
    ) {
        if (pagePersona != null && pagePersona.personaConfig() != null) {
            com.example.docmind.dto.PagePersonaConfig cfg = pagePersona.personaConfig();
            if (cfg.unitNoun() != null && !cfg.unitNoun().isBlank()) {
                return cfg.unitNoun().strip();
            }
            if ((cfg.industryCode() != null && !cfg.industryCode().isBlank())
                    || !cfg.safeDomainKeywords().isEmpty()) {
                String resolved = cfg.resolvedUnitNoun();
                if (!"sản phẩm".equals(resolved)) {
                    return resolved;
                }
            }
        }
        if (visiblePackages != null) {
            for (PackageCatalogService.PackageSummary p : visiblePackages) {
                if (p != null && p.display() != null && p.display().unitNoun() != null && !p.display().unitNoun().isBlank()) {
                    return p.display().unitNoun().strip();
                }
            }
        }
        if (tieredCatalog != null) {
            for (PackageCatalogService.PackageSummary p : tieredCatalog.allAllowedPackages()) {
                if (p != null && p.display() != null && p.display().unitNoun() != null && !p.display().unitNoun().isBlank()) {
                    return p.display().unitNoun().strip();
                }
            }
        }
        return (pagePersona != null && pagePersona.personaConfig() != null)
                ? pagePersona.personaConfig().resolvedUnitNoun()
                : "sản phẩm";
    }

    public static StructuredTurnContract buildStructuredTurnContract(
            List<String> signals,
            QuotedReplyResolution quoteResolution,
            List<String> burstSubMessages,
            String resolvedStage,
            String unitNoun,
            boolean discoveryCompleted,
            List<String> knownSymptoms,
            List<String> knownScopes,
            String safeTiming,
            String selectedProductId,
            String bookingDate,
            String bookingTime,
            String bookingStatus,
            int priceObjectionCount,
            CustomerInsightDto customerInsight,
            List<AgentSkillDto> activeSkills,
            boolean isOperationalQuestionTurn,
            boolean isServiceDetailQuestionTurn,
            List<PackageCatalogService.PackageSummary> visiblePackages
    ) {
        return buildStructuredTurnContract(
                signals,
                quoteResolution,
                burstSubMessages,
                resolvedStage,
                unitNoun,
                discoveryCompleted,
                knownSymptoms,
                knownScopes,
                safeTiming,
                selectedProductId,
                bookingDate,
                bookingTime,
                bookingStatus,
                priceObjectionCount,
                customerInsight,
                activeSkills,
                isOperationalQuestionTurn,
                isServiceDetailQuestionTurn,
                visiblePackages,
                "",
                ""
        );
    }

    public static StructuredTurnContract buildStructuredTurnContract(
            List<String> signals,
            QuotedReplyResolution quoteResolution,
            List<String> burstSubMessages,
            String resolvedStage,
            String unitNoun,
            boolean discoveryCompleted,
            List<String> knownSymptoms,
            List<String> knownScopes,
            String safeTiming,
            String selectedProductId,
            String bookingDate,
            String bookingTime,
            String bookingStatus,
            int priceObjectionCount,
            CustomerInsightDto customerInsight,
            List<AgentSkillDto> activeSkills,
            boolean isOperationalQuestionTurn,
            boolean isServiceDetailQuestionTurn,
            List<PackageCatalogService.PackageSummary> visiblePackages,
            String precedingBotTurnText,
            String lastBotQuestion
    ) {
        List<String> safeSignals = signals != null ? List.copyOf(signals) : List.of();
        QuotedReplyResolution safeQuote = quoteResolution != null ? quoteResolution : QuotedReplyResolution.empty();
        String primaryIntent = isOperationalQuestionTurn
                ? "OPERATIONAL_INFO_INQUIRY"
                : (!safeSignals.isEmpty() ? safeSignals.get(0) : (isServiceDetailQuestionTurn ? "PRODUCT_DETAIL_INQUIRY" : "GENERAL_CONSULTATION"));

        Set<String> mergedScopes = new LinkedHashSet<>();
        if (knownScopes != null) mergedScopes.addAll(knownScopes);
        if (knownSymptoms != null) mergedScopes.addAll(knownSymptoms);

        String archetype = (customerInsight != null && customerInsight.buyerArchetype() != null && !customerInsight.buyerArchetype().isBlank())
                ? customerInsight.buyerArchetype().strip()
                : "INSUFFICIENT_EVIDENCE";
        String hypothesis = (customerInsight != null && customerInsight.workingHypothesis() != null && !customerInsight.workingHypothesis().isBlank())
                ? customerInsight.workingHypothesis().strip()
                : "INSUFFICIENT_EVIDENCE";
        int buyingTemp = customerInsight != null ? customerInsight.buyingTemperature() : 40;
        String lastSpeechAct = (customerInsight != null && customerInsight.lastActualSentSpeechAct() != null && !customerInsight.lastActualSentSpeechAct().isBlank())
                ? customerInsight.lastActualSentSpeechAct().strip()
                : "NONE";

        List<String> skillCodes = new ArrayList<>();
        if (activeSkills != null) {
            for (AgentSkillDto s : activeSkills) {
                if (s != null && s.code() != null && !s.code().isBlank()) {
                    skillCodes.add(s.code().strip());
                    break;
                }
            }
        }

        String primaryGoal;
        List<String> allowedActions = new ArrayList<>();
        List<String> forbiddenActions = new ArrayList<>();
        forbiddenActions.add("FABRICATE_UNGROUNDED_PRICE_OR_PROMO");
        forbiddenActions.add("CROSS_TIER_EXCLUSIVE_ATTRIBUTE_LEAK");

        boolean isWarmGeneralInfoAck = "STAGE_1_DISCOVERY_AND_FIRST_QUOTE".equals(resolvedStage)
                && (safeSignals.contains("ACKNOWLEDGED_GENERAL_INFO") || safeSignals.contains("LUKEWARM_POST_QUOTE_CHECKIN"));
        boolean hasPriorBotTurn = precedingBotTurnText != null && !precedingBotTurnText.isBlank();
        boolean conversationalDeltaRequired = "STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE".equals(resolvedStage) || hasPriorBotTurn;
        String conversationalDeltaRule;
        String closingCadenceRule;
        String bubbleRhythmGuide;
        String safeLastQuestion = (lastBotQuestion != null && !lastBotQuestion.isBlank() && !"không có".equals(lastBotQuestion))
                ? lastBotQuestion.strip()
                : "";
        String noRepeatClosingSuffix = !safeLastQuestion.isEmpty()
                ? (" Tuyệt đối không lặp lại điệp khúc câu hỏi của lượt liền trước (\"" + safeLastQuestion + "\").")
                : " Mỗi lượt dẫn dắt bằng một câu kết tự nhiên khác nhau, tránh lặp điệp khúc cứng nhắc.";

        if (safeQuote.unresolvedReplyToMid() || safeQuote.ambiguousMultiplePackages()) {
            primaryGoal = "CLARIFY_QUOTED_TARGET_WITHOUT_GUESSING";
            allowedActions.add("ASK_TARGET_CLARIFICATION_BY_UNIT_NOUN");
            forbiddenActions.add("DEFAULT_TO_HELD_PACKAGE");
            forbiddenActions.add("PUSH_BOOKING_TIME");
            conversationalDeltaRule = "Hỏi xác nhận đúng đối tượng tin nhắn được trích dẫn, không đọc lại mô tả dài dòng.";
            closingCadenceRule = "Kết nhẹ bằng câu hỏi xác nhận đúng mục khách đang quan tâm." + noRepeatClosingSuffix;
            bubbleRhythmGuide = "1–2 bong bóng ngắn gọn, lịch sự, dễ trả lời.";
        } else if (isOperationalQuestionTurn) {
            primaryGoal = "ANSWER_OPERATIONAL_QUESTION_DIRECTLY";
            allowedActions.add("PROVIDE_GROUNDED_OPERATIONAL_INFO");
            forbiddenActions.add("UNSOLICITED_DOWNSELL_PITCH");
            conversationalDeltaRule = "Đi thẳng vào thông tin vận hành khách hỏi, không nhai lại bài giới thiệu sản phẩm.";
            closingCadenceRule = "Kết nhẹ nhàng, thân thiện sau khi cung cấp thông tin vận hành." + noRepeatClosingSuffix;
            bubbleRhythmGuide = "1–2 bong bóng rõ ràng, ấm áp.";
        } else if ("STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE".equals(resolvedStage)) {
            primaryGoal = "RESPECT_CUSTOMER_PAUSE_AND_WARM_FAREWELL";
            allowedActions.add("ACKNOWLEDGE_PAUSE");
            allowedActions.add("WARM_CLOSING_WISH");
            forbiddenActions.add("MENTION_UNPRESENTED_GHOST_PRODUCT");
            forbiddenActions.add("PUSH_BOOKING_TIME");
            conversationalDeltaRule = "Tôn trọng khoảng nghỉ của khách, không liệt kê lại thuộc tính hay chào thêm phương án lạ.";
            closingCadenceRule = "Khép lại bằng lời chúc ấm áp, không đặt thêm câu hỏi chèo kéo.";
            bubbleRhythmGuide = "2 bong bóng ngắn mềm mại: ghi nhận lưu thông tin & lời chúc tốt lành.";
        } else if ("STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE".equals(resolvedStage)) {
            primaryGoal = "HOLD_VALUE_AND_DEEPEN_EXPERIENTIAL_WORTH_WITHOUT_DOWNSELL";
            allowedActions.add("EMPATHIZE_PRICE_CONCERN_WARMLY");
            allowedActions.add("DEEPEN_EXPERIENTIAL_AND_EXECUTION_VALUE");
            forbiddenActions.add("PREMATURE_DOWNSELL_ON_FIRST_OBJECTION");
            forbiddenActions.add("PARROT_PREVIOUS_TURN_AUXILIARY_AMENITY_LIST");
            conversationalDeltaRule = "CONVERSATIONAL_DELTA_ACTIVE: Lượt liền trước đã nêu danh sách thuộc tính/quyền lợi phụ đi kèm — KHÔNG đọc lại danh sách thuộc tính phụ vừa nói như cái máy; hãy dành dung lượng khắc họa chiều sâu trải nghiệm, sự tỉ mỉ của người thực hiện và cảm giác dễ chịu/an tâm thực chất mà khách nhận được.";
            closingCadenceRule = "Khơi gợi cảm nhận hoặc hỏi nhẹ điều khách đang ưu tiên nhất ở trải nghiệm này." + noRepeatClosingSuffix;
            bubbleRhythmGuide = "Linh hoạt 2–3 bong bóng giàu cảm xúc, có nhịp nghỉ tự nhiên (Đồng cảm -> Chiều sâu trải nghiệm -> Câu hỏi mở tinh tế).";
        } else if ("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL".equals(resolvedStage)
                || "STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION".equals(resolvedStage)) {
            primaryGoal = "OFFER_GROUNDED_ECONOMICAL_OPTION_WITH_HONEST_SCOPE";
            allowedActions.add("PRESENT_ECONOMICAL_OPTION");
            allowedActions.add("CONTRAST_SCOPE_HONESTLY");
            forbiddenActions.add("DISPARAGE_LOWER_TIER");
            conversationalDeltaRule = "Bắc nhịp cầu mềm mại sang phương án tiết kiệm hơn mà không lặp lại đoạn quảng cáo cũ và không dìm hàng phương án nhỏ.";
            closingCadenceRule = "Hỏi mở nhẹ nhàng xem hướng lựa chọn nhẹ nhàng này có hợp ý khách hơn không." + noRepeatClosingSuffix;
            bubbleRhythmGuide = "2–3 bong bóng ấm áp, không tạo áp lực.";
        } else if ("STAGE_4_POSITIVE_TRIAL_OR_BOOKING".equals(resolvedStage)) {
            if ("AWAITING_PRODUCT_SELECTION".equals(bookingStatus)) {
                primaryGoal = "CLARIFY_PRODUCT_CHOICE_WHILE_PRESERVING_CAPTURED_SLOTS";
                allowedActions.add("ASK_WHICH_PRESENTED_PRODUCT_TO_BOOK");
                allowedActions.add("KEEP_CAPTURED_DATE_OR_TIME");
                forbiddenActions.add("FALSE_PRODUCT_SELECTION_CLAIM");
                forbiddenActions.add("UNVERIFIED_BOOKING_CONFIRMATION_CLAIM");
                conversationalDeltaRule = "Giữ nguyên mốc ngày/giờ khách đã nêu, chỉ hỏi làm rõ phương án khách muốn chọn.";
                closingCadenceRule = "Hỏi lịch sự phương án khách chọn (và giờ nếu chưa có) để kiểm tra lịch trống." + noRepeatClosingSuffix;
                bubbleRhythmGuide = "1–2 bong bóng rõ ràng, trân trọng.";
            } else if ("AWAITING_AVAILABILITY_CHECK".equals(bookingStatus)) {
                primaryGoal = "ACKNOWLEDGE_REQUEST_AND_INFORM_AVAILABILITY_CHECK";
                allowedActions.add("CONFIRM_CAPTURED_PRODUCT_DATE_TIME");
                allowedActions.add("NOTIFY_PENDING_AVAILABILITY_CHECK");
                forbiddenActions.add("UNVERIFIED_BOOKING_CONFIRMATION_CLAIM");
                conversationalDeltaRule = "Ghi nhận chu đáo thông tin đã chốt và hẹn kiểm tra chỗ trống thực tế, không đọc lại thông số quảng cáo.";
                closingCadenceRule = "Hẹn kiểm tra lịch trống thực tế và phản hồi xác nhận ngay cho khách.";
                bubbleRhythmGuide = "1–2 bong bóng chu đáo, tin cậy.";
            } else if ("CONFIRMED".equals(bookingStatus)) {
                primaryGoal = "CONFIRM_VERIFIED_BOOKING_WARMLY";
                allowedActions.add("CONFIRM_BOOKING_DETAILS");
                conversationalDeltaRule = "Xác nhận lịch hẹn ấm áp, không lặp lại thông tin chào bán.";
                closingCadenceRule = "Hẹn đón tiếp khách chu đáo đúng lịch hẹn.";
                bubbleRhythmGuide = "1–2 bong bóng ấm áp.";
            } else {
                primaryGoal = "CONFIRM_SELECTED_PRODUCT_AND_COLLECT_MISSING_DATE_OR_TIME";
                allowedActions.add("LOCK_SELECTED_PRODUCT");
                allowedActions.add("ASK_MISSING_DATE_OR_TIME");
                forbiddenActions.add("RE_PITCH_UNSELECTED_PRODUCT");
                forbiddenActions.add("UNVERIFIED_BOOKING_CONFIRMATION_CLAIM");
                forbiddenActions.add("ABRUPT_FAREWELL_ON_CUSTOMER_NOD");
                conversationalDeltaRule = "Đón nhận cái gật đầu đồng thuận bằng sự trân trọng, không đọc lại bảng thông số sản phẩm vừa thống nhất.";
                closingCadenceRule = "Gợi mở nhẹ nhàng hỏi khung giờ thuận tiện hôm nay hoặc ngày mai để chuẩn bị đón tiếp chu đáo; tuyệt đối không chúc tạm biệt." + noRepeatClosingSuffix;
                bubbleRhythmGuide = "1–2 bong bóng hào hứng vừa đủ, ấm áp và hiếu khách.";
            }
        } else if (isWarmGeneralInfoAck) {
            primaryGoal = "KEEP_WARM_CONVERSATIONAL_BRIDGE_WITHOUT_PRESSURE";
            allowedActions.add("ACKNOWLEDGE_WARMLY");
            allowedActions.add("ASK_GENTLE_IMPRESSION_OR_REMAINING_CONCERN");
            forbiddenActions.add("AGGRESSIVE_BOOKING_TIME_CLOSE");
            forbiddenActions.add("ABRUPT_CONVERSATION_CUTOFF");
            conversationalDeltaRule = "Không đọc lại thông tin/giá vừa báo ở lượt liền trước; mở nhịp cầu trò chuyện gần gũi.";
            closingCadenceRule = "Hỏi nhẹ cảm nhận của khách hoặc điều khách còn băn khoăn để dẫn dắt tiếp." + noRepeatClosingSuffix;
            bubbleRhythmGuide = "1–2 bong bóng tự nhiên, gần gũi.";
        } else if (!discoveryCompleted) {
            primaryGoal = "SOOTHE_EMOTION_AND_DISCOVER_PRIORITY_SCOPE";
            allowedActions.add("EMPATHIZE_WITH_SENSORY_WARMTH");
            allowedActions.add("ASK_PRIORITY_SCOPE_TO_TAILOR_SOLUTION");
            forbiddenActions.add("GUESS_UNSTATED_CAUSE");
            forbiddenActions.add("PREMATURE_PRICE_DUMP_BEFORE_PRIORITY_SCOPE");
            forbiddenActions.add("COLD_HOSPITAL_DISCLAIMER_WITHOUT_RED_FLAG");
            conversationalDeltaRule = "Lắng nghe và vỗ về cảm giác của khách trước khi đề cập giải pháp thương mại.";
            closingCadenceRule = "Hỏi nhẹ 1 câu về phạm vi/khu vực ưu tiên nhất của khách để 'đo ni đóng giày' đúng giải pháp." + noRepeatClosingSuffix;
            bubbleRhythmGuide = "1–2 bong bóng mềm mại: Vỗ về đồng cảm -> Hỏi nhẹ phạm vi ưu tiên.";
        } else if (isServiceDetailQuestionTurn) {
            primaryGoal = "EXPLAIN_OR_COMPARE_OPTIONS_WITH_WARM_HONESTY";
            allowedActions.add("EXPLAIN_GROUNDED_DIFFERENCES_AND_FIT");
            allowedActions.add("RESPECT_ALL_TIERS_EQUALLY");
            forbiddenActions.add("DISPARAGE_LOWER_TIER");
            forbiddenActions.add("PUSH_BOOKING_TIME");
            conversationalDeltaRule = "Tập trung làm rõ đúng điểm khác biệt hoặc chi tiết khách vừa hỏi, không lặp lại máy móc câu chữ cũ.";
            closingCadenceRule = "Hỏi mở nhẹ nhàng xem khách đang nghiêng về trải nghiệm/phạm vi nào hơn." + noRepeatClosingSuffix;
            bubbleRhythmGuide = "Linh hoạt 2–3 bong bóng mạch lạc, tinh tế.";
        } else {
            primaryGoal = "RECOMMEND_TOP_MATCHING_PRIMARY_PRODUCT";
            allowedActions.add("PRESENT_PRIMARY_PRODUCT_FROM_4_LAYER_CATALOG");
            allowedActions.add("EVOKE_EXPERIENTIAL_COMFORT_AND_ASK_OPEN_FEEDBACK");
            conversationalDeltaRule = hasPriorBotTurn
                    ? "Kết nối mượt với chia sẻ vừa rồi của khách, không lặp lại lời chào hay câu hỏi đã hỏi."
                    : "Giới thiệu cuốn hút giải pháp phù hợp nhất gắn liền với cảm giác dễ chịu/an tâm của khách.";
            closingCadenceRule = "Kết lượt bằng câu hỏi mở tự nhiên về cảm nhận hoặc mức độ phù hợp với mong muốn của khách." + noRepeatClosingSuffix;
            bubbleRhythmGuide = "Linh hoạt 2–3 bong bóng có nhịp nghỉ tự nhiên như chuyên viên tư vấn tinh tế ngoài đời.";
        }

        List<StructuredProductConstraint> productConstraints = new ArrayList<>();
        if (visiblePackages != null) {
            for (PackageCatalogService.PackageSummary p : visiblePackages) {
                if (p != null) {
                    productConstraints.add(StructuredProductConstraint.fromPackageSummary(p, unitNoun));
                }
            }
        }

        return new StructuredTurnContract(
                primaryIntent,
                safeSignals,
                safeQuote.resolutionState().name(),
                safeQuote.replyToMid() != null ? safeQuote.replyToMid() : "null",
                burstSubMessages != null ? burstSubMessages.size() : 1,
                resolvedStage != null ? resolvedStage : "STAGE_1_DISCOVERY_AND_FIRST_QUOTE",
                unitNoun != null && !unitNoun.isBlank() ? unitNoun : "sản phẩm",
                discoveryCompleted,
                List.copyOf(mergedScopes),
                safeTiming != null ? safeTiming : "chưa đề cập",
                selectedProductId != null ? selectedProductId : "null",
                bookingDate != null ? bookingDate : "null",
                bookingTime != null ? bookingTime : "null",
                bookingStatus != null ? bookingStatus : "NONE",
                priceObjectionCount,
                archetype,
                hypothesis,
                buyingTemp,
                lastSpeechAct,
                List.copyOf(skillCodes),
                primaryGoal,
                List.copyOf(allowedActions),
                List.copyOf(forbiddenActions),
                List.copyOf(productConstraints),
                false,
                false,
                false,
                EMOTIONAL_HOSPITALITY_CONTRACT_DIRECTIVE,
                conversationalDeltaRequired,
                conversationalDeltaRule,
                closingCadenceRule,
                bubbleRhythmGuide
        );
    }

    public static String renderStructuredTurnContractXml(StructuredTurnContract c) {
        if (c == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(1400);
        sb.append("<STRUCTURED_TURN_CONTRACT>\n")
          .append("1. Intent:\n")
          .append("   - primary_intent: \"").append(c.primaryIntent()).append("\"\n")
          .append("   - detected_signals: ").append(c.detectedSignals()).append('\n')
          .append("   - reply_to_resolution: state=").append(c.replyResolutionState())
          .append(", mid=\"").append(c.replyToMid()).append("\", burst_count=").append(c.burstMessageCount()).append('\n')
          .append("2. Conversation & Booking State:\n")
          .append("   - stage: \"").append(c.stage()).append("\", unit_noun=\"").append(c.unitNoun()).append("\"\n")
          .append("   - discovery_completed: ").append(c.discoveryCompleted())
          .append(", verified_scopes: ").append(c.verifiedScopes())
          .append(", context_or_timing: \"").append(c.verifiedContextOrTiming()).append("\"\n")
          .append("   - booking_state: status=").append(c.bookingStatus())
          .append(", selected_product_id=\"").append(c.selectedProductId()).append("\"")
          .append(", booking_date=\"").append(c.bookingDate()).append("\"")
          .append(", booking_time=\"").append(c.bookingTime()).append("\"")
          .append(", price_objection_count=").append(c.priceObjectionCount()).append('\n')
          .append("3. Brain Hypothesis & Skill:\n")
          .append("   - buyer_archetype: \"").append(c.buyerArchetype()).append("\", buying_temp=").append(c.buyingTemperature()).append("/100\n")
          .append("   - living_hypothesis: \"").append(c.workingHypothesis()).append("\"\n")
          .append("   - last_actual_sent_speech_act: \"").append(c.lastActualSentSpeechAct()).append("\", active_skills: ").append(c.activeSkillCodes()).append('\n')
          .append("4. Goal & Allowed Actions:\n")
          .append("   - primary_goal: \"").append(c.primaryGoal()).append("\"\n")
          .append("   - allowed_actions: ").append(c.allowedActions()).append('\n')
          .append("   - forbidden_actions: ").append(c.forbiddenActions()).append('\n')
          .append("5. Commercial Constraints:\n")
          .append("   - allow_ungrounded_promo: ").append(c.allowUngroundedPromo())
          .append(", allow_cross_tier_attribute_leak: ").append(c.allowCrossTierAttributeLeak())
          .append(", allow_ghost_product_on_closing: ").append(c.allowGhostProductOnClosing()).append('\n');
        if (c.commercialProducts().isEmpty()) {
            sb.append("   - allowed_4layer_products: []\n");
        } else {
            sb.append("   - allowed_4layer_products:\n");
            for (StructuredProductConstraint p : c.commercialProducts()) {
                sb.append("     * [id=").append(p.productId())
                  .append(" | sku=").append(p.rawSkuCode())
                  .append(" | role=").append(p.commercialRole())
                  .append(" | unit_noun=").append(p.unitNoun())
                  .append(" | name=\"").append(p.canonicalName()).append("\"")
                  .append(" | specs=\"").append(p.formattedSpecsSummary()).append("\"")
                  .append(" | ").append(p.priceLabel()).append("=\"").append(p.formattedPriceWithUnit()).append("\"")
                  .append(" (amount_vnd=").append(p.priceAmountVnd())
                  .append(", billing_unit=\"").append(p.billingUnit()).append("\")")
                  .append(" | scopes=").append(p.scopeTags())
                  .append("]\n");
            }
        }
        sb.append("6. Emotional Hospitality & Conversational Delta:\n")
          .append("   - core_directive: \"").append(c.emotionalHospitalityDirective() != null ? c.emotionalHospitalityDirective() : EMOTIONAL_HOSPITALITY_CONTRACT_DIRECTIVE).append("\"\n")
          .append("   - conversational_delta_required: ").append(c.conversationalDeltaRequired())
          .append(" | rule: \"").append(c.conversationalDeltaRule() != null ? c.conversationalDeltaRule() : "").append("\"\n")
          .append("   - closing_cadence: \"").append(c.closingCadenceRule() != null ? c.closingCadenceRule() : "").append("\"\n")
          .append("   - bubble_rhythm: \"").append(c.bubbleRhythmGuide() != null ? c.bubbleRhythmGuide() : "").append("\"\n");
        sb.append("</STRUCTURED_TURN_CONTRACT>\n\n");
        return sb.toString();
    }

    public static void appendCatalogPackageLine(
            StringBuilder sb,
            int index,
            PackageCatalogService.PackageSummary p,
            List<String> knownBodyAreas,
            List<String> knownSymptoms
    ) {
        if (p == null) return;
        PackageCatalogService.CatalogNormalizedData norm = p.normalized();
        PackageCatalogService.CatalogInferredData inf = p.inferred();
        PackageCatalogService.CatalogDisplayData disp = p.display();
        String canonicalName = (norm != null && norm.canonicalName() != null && !norm.canonicalName().isBlank())
                ? norm.canonicalName()
                : (p.displayName() != null ? p.displayName() : "");
        String partialScopeNote = buildPartialScopeLimitationNote(p, knownBodyAreas, knownSymptoms);
        String specsSummary = (disp != null && disp.formattedSpecsSummary() != null)
                ? disp.formattedSpecsSummary()
                : p.formattedSpecsSummary();
        String priceLabel = (disp != null && disp.priceLabel() != null && !disp.priceLabel().isBlank())
                ? disp.priceLabel()
                : "Giá niêm yết";
        String formattedPrice = (disp != null && disp.formattedPriceWithUnit() != null && !disp.formattedPriceWithUnit().isBlank())
                ? disp.formattedPriceWithUnit()
                : (p.priceDisplay() != null ? p.priceDisplay() : "");
        String scopeLabel = (disp != null && disp.scopeLabel() != null && !disp.scopeLabel().isBlank())
                ? disp.scopeLabel()
                : "Phạm vi phù hợp";
        String formatLabel = (disp != null && disp.formatOrSpaceLabel() != null && !disp.formatOrSpaceLabel().isBlank())
                ? disp.formatOrSpaceLabel()
                : "Hình thức";
        String spaceOrFormatNote = (disp != null && disp.customerFacingFormatNote() != null && !disp.customerFacingFormatNote().isBlank())
                ? disp.customerFacingFormatNote()
                : p.customerFacingSpaceNote();
        List<String> scopes = (inf != null && inf.scopeTags() != null) ? inf.scopeTags() : p.scopeTags();

        sb.append(index).append(". ")
          .append(canonicalName);
        if (specsSummary != null && !specsSummary.isBlank()) {
            sb.append(" — ").append(specsSummary);
        }
        sb.append(" — ").append(priceLabel).append(": ").append(formattedPrice);
        if (scopes != null && !scopes.isEmpty()) {
            sb.append(" — ").append(scopeLabel).append(": ").append(scopes);
            if (!partialScopeNote.isEmpty()) {
                sb.append(' ').append(partialScopeNote);
            }
        }
        if (spaceOrFormatNote != null && !spaceOrFormatNote.isBlank()) {
            sb.append(" — ").append(formatLabel).append(": ").append(spaceOrFormatNote);
        }
        if (p.includesSummary() != null && !p.includesSummary().isBlank()) {
            sb.append(" | Quyền lợi đi kèm: ").append(p.includesSummary().strip())
              .append(" (Chỉ được nêu đúng thông tin và quyền lợi của mục này, cấm lấy tiện ích của mục khác đắp sang)");
        }
        if (p.processSummary() != null && !p.processSummary().isBlank()) {
            sb.append(" — Chi tiết: ").append(p.processSummary().strip());
        }
        sb.append('\n');
    }

    private static final int MAX_COMPACT_PROMPT_CHARS = 400;
    private static final int MAX_SKILL_INSTRUCTION_CHARS = 960;
    private static final int MAX_TOTAL_SKILL_CHARS = 960;
    private static final int MAX_RULE_CHARS = 220;
    private static final int MAX_TOTAL_RULES_CHARS = 600;
    private static final int MAX_GOVERNED_RULES = 3;
    private static final double MIN_GOVERNED_CONFIDENCE = 0.45;
    private static final Pattern PRODUCT_COMPARISON_QUESTION_PATTERN = Pattern.compile(
            "(?iU)(khác\\s+(?:gì|j|nhau|chỗ\\s+nào|điểm\\s+nào|thế\\s+nào|như\\s+thế\\s+nào)|so\\s+sánh|so\\s+với|hơn\\s+(?:gì|j|chỗ\\s+nào))"
    );
    private static final Pattern PROACTIVE_PRICE_OR_CATALOG_REQUEST_PATTERN = Pattern.compile(
            "(?iU)\\b(giá|bao\\s+nhiêu|bảng\\s+giá|chi\\s+phí|báo\\s+giá|các\\s+gói|có\\s+gói\\s+nào|tư\\s+vấn\\s+gói|tư\\s+vấn\\s+mẫu|tư\\s+vấn\\s+khóa|gói\\s+phù\\s+hợp|mẫu\\s+phù\\s+hợp|khóa\\s+phù\\s+hợp)\\b"
    );

    private static boolean isProductComparisonQuestionText(String text) {
        return text != null && !text.isBlank() && PRODUCT_COMPARISON_QUESTION_PATTERN.matcher(text).find();
    }

    private static boolean isProactivePriceOrCatalogRequestText(String text) {
        return text != null && !text.isBlank() && PROACTIVE_PRICE_OR_CATALOG_REQUEST_PATTERN.matcher(text).find();
    }

    private static String extractLatestPrecedingAssistantText(List<FanpageBotRequest.ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        List<String> trailingAssistantBubbles = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            FanpageBotRequest.ConversationTurn hm = history.get(i);
            if (hm == null || hm.role() == null || hm.content() == null) {
                continue;
            }
            if ("assistant".equalsIgnoreCase(hm.role().strip())) {
                trailingAssistantBubbles.add(0, hm.content().strip());
            } else if (!trailingAssistantBubbles.isEmpty()) {
                break;
            }
        }
        return String.join(" ", trailingAssistantBubbles).strip();
    }

    private static boolean historyMentionsAnyPackage(
            List<FanpageBotRequest.ConversationTurn> history,
            List<PackageCatalogService.PackageSummary> candidates
    ) {
        if (history == null || history.isEmpty() || candidates == null || candidates.isEmpty()) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (FanpageBotRequest.ConversationTurn hm : history) {
            if (hm != null && hm.content() != null) {
                sb.append(' ').append(hm.content().toLowerCase(Locale.ROOT));
            }
        }
        String all = sb.toString();
        for (PackageCatalogService.PackageSummary p : candidates) {
            if (p == null) continue;
            if (p.displayName() != null && !p.displayName().isBlank()
                    && all.contains(p.displayName().toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (p.priceDisplay() != null && !p.priceDisplay().isBlank()
                    && all.contains(p.priceDisplay().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
    private static final Pattern DIRECTIVE_PRICE_TOKEN_PATTERN = Pattern.compile(
            "(?iU)(?<![\\p{L}\\d])(\\d{1,3}(?:[.,]\\d{3})+|\\d+(?:[.,]\\d+)?)\\s*(k|nghìn|ngàn|tr|triệu|đ|vnđ|vnd)\\b"
    );
    private static final Pattern DOWNSELL_OVERRIDE_DIRECTIVE_PATTERN = Pattern.compile(
            "(?iU)\\b(gói\\s+rẻ\\s+hơn|gói\\s+tiết\\s+kiệm|phương\\s+án\\s+tiết\\s+kiệm|hạ\\s+gói|hạ\\s+bậc|downsell)\\b"
    );
    private static final Pattern AGGRESSIVE_CLOSE_OR_DEFAULT_PKG_DIRECTIVE_PATTERN = Pattern.compile(
            "(?iU)\\b(chốt\\s+(?:đơn|lịch)\\s+ngay|mặc\\s+định\\s+(?:báo|chọn|lấy)|ép\\s+chốt|chào\\s+thêm\\s+gói|upsell\\s+ngay|giữ\\s+lịch\\s+ngay)\\b"
    );

    /**
     * Xây dựng khối chỉ dẫn cấu hình động từ DB (active_prompt_compact, agent_skills.tactical_instructions,
     * learned_rules) theo đúng Thứ tự ưu tiên bất biến (Hierarchy of Truth):
     * 1. Core Safety & System Policy
     * 2. Catalog Truth (<SCOPE_FILTERED_CATALOG>) & Verified Customer State (<STRUCTURED_CONVERSATION_STATE>)
     * 3. Current Turn Stage & Reply Target (reply_to.mid / quoted_package)
     * 4. Page Compact Directive & Agent Skills (tactical_instructions)
     * 5. Learned Rules (AI Playbook)
     */
    public static String buildGovernedTenantPlaybookSection(
            PromptBuilder.PagePersona pagePersona,
            UUID pageId,
            String resolvedStage,
            List<String> turnSignals,
            QuotedReplyResolution quotedReplyResolution,
            boolean isOperationalQuestionTurn,
            List<PackageCatalogService.PackageSummary> visiblePackagesForTurn,
            List<AgentSkillDto> activeSkills,
            List<LearnedRuleDto> appliedRules,
            String customerMessage
    ) {
        QuotedReplyResolution safeQuote = quotedReplyResolution != null ? quotedReplyResolution : QuotedReplyResolution.empty();
        List<PackageCatalogService.PackageSummary> safeVisible = visiblePackagesForTurn != null ? visiblePackagesForTurn : List.of();
        List<String> safeSignals = turnSignals != null ? turnSignals : List.of();

        String compactPrompt = "";
        if (pagePersona != null && pagePersona.personaConfig() != null
                && pagePersona.personaConfig().activePromptCompact() != null
                && !pagePersona.personaConfig().activePromptCompact().isBlank()) {
            String rawCompact = pagePersona.personaConfig().activePromptCompact().strip().replaceAll("\\s+", " ");
            if (!conflictsWithTurnStageOrReplyTarget(rawCompact, resolvedStage, safeQuote, isOperationalQuestionTurn)
                    && !hasUnverifiedPriceInDirective(rawCompact, safeVisible)) {
                compactPrompt = truncateToBudget(rawCompact, MAX_COMPACT_PROMPT_CHARS);
            }
        }

        List<String> governedSkillLines = new ArrayList<>();
        int skillCharsUsed = 0;
        if (activeSkills != null) {
            for (AgentSkillDto skill : activeSkills) {
                if (governedSkillLines.size() >= 1 || skillCharsUsed >= MAX_TOTAL_SKILL_CHARS) {
                    break;
                }
                if (!isSkillAllowedForPageAndTurn(skill, pageId, resolvedStage, safeQuote, isOperationalQuestionTurn, safeVisible)) {
                    continue;
                }
                String instr = skill.tacticalInstructions() != null
                        ? skill.tacticalInstructions().strip().replaceAll("\\s+", " ")
                        : "";
                if (instr.isEmpty()) {
                    continue;
                }
                String capped = truncateToBudget(instr, Math.min(MAX_SKILL_INSTRUCTION_CHARS, MAX_TOTAL_SKILL_CHARS - skillCharsUsed));
                String codeTag = (skill.code() != null && !skill.code().isBlank()) ? "[" + skill.code().strip() + "] " : "";
                governedSkillLines.add("  + " + codeTag + capped);
                skillCharsUsed += capped.length();
            }
        }

        List<LearnedRuleDto> eligibleRules = new ArrayList<>();
        if (appliedRules != null) {
            for (LearnedRuleDto rule : appliedRules) {
                if (isLearnedRuleAllowedForPageAndTurn(
                        rule,
                        pageId,
                        resolvedStage,
                        safeSignals,
                        safeQuote,
                        isOperationalQuestionTurn,
                        safeVisible,
                        customerMessage
                )) {
                    eligibleRules.add(rule);
                }
            }
        }
        eligibleRules.sort(
                Comparator
                        .<LearnedRuleDto>comparingInt(r -> r.priority() != null ? r.priority() : 80)
                        .thenComparing(Comparator.comparingDouble(LearnedRuleDto::confidenceScore).reversed())
        );

        List<String> governedRuleLines = new ArrayList<>();
        int ruleCharsUsed = 0;
        for (LearnedRuleDto rule : eligibleRules) {
            if (governedRuleLines.size() >= MAX_GOVERNED_RULES || ruleCharsUsed >= MAX_TOTAL_RULES_CHARS) {
                break;
            }
            String ruleText = composeCompactLearnedRuleText(rule);
            if (ruleText.isEmpty()) {
                continue;
            }
            String capped = truncateToBudget(ruleText, Math.min(MAX_RULE_CHARS, MAX_TOTAL_RULES_CHARS - ruleCharsUsed));
            String codeTag = (rule.ruleCode() != null && !rule.ruleCode().isBlank())
                    ? "[" + rule.ruleCode().strip() + "] "
                    : (rule.category() != null && !rule.category().isBlank() ? "[" + rule.category().strip() + "] " : "");
            governedRuleLines.add("  + " + codeTag + capped);
            ruleCharsUsed += capped.length();
        }

        if (compactPrompt.isEmpty() && governedSkillLines.isEmpty() && governedRuleLines.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder(1024);
        out.append("<GOVERNED_TENANT_PLAYBOOK>\n");
        out.append("[THỨ TỰ ƯU TIÊN BẤT BIẾN (HIERARCHY OF TRUTH): ")
           .append("1. Core Safety & System Policy > ")
           .append("2. Catalog Truth (<SCOPE_FILTERED_CATALOG>) & Verified Customer State (<STRUCTURED_CONVERSATION_STATE>) > ")
           .append("3. Current Turn Stage & Reply Target (quoted_package / reply_to.mid) > ")
           .append("4. Agent Skills (tactical_instructions) > ")
           .append("5. Learned Rules. ")
           .append("Mọi chỉ dẫn bên dưới chỉ bổ trợ cách diễn đạt/tác nghiệp, TUYỆT ĐỐI KHÔNG ĐƯỢC ghi đè giá/quy cách trong Catalog, trạng thái khách đã xác thực hoặc đối tượng reply_to.mid.]\n");
        if (!compactPrompt.isEmpty()) {
            out.append("1. page_active_prompt_compact: ").append(compactPrompt).append('\n');
        }
        if (!governedSkillLines.isEmpty()) {
            out.append("2. governed_agent_skills (tactical_instructions):\n");
            for (String line : governedSkillLines) {
                out.append(line).append('\n');
            }
        }
        if (!governedRuleLines.isEmpty()) {
            out.append("3. governed_learned_rules:\n");
            for (String line : governedRuleLines) {
                out.append(line).append('\n');
            }
        }
        out.append("</GOVERNED_TENANT_PLAYBOOK>\n\n");
        return out.toString();
    }

    private static boolean isSkillAllowedForPageAndTurn(
            AgentSkillDto skill,
            UUID pageId,
            String resolvedStage,
            QuotedReplyResolution safeQuote,
            boolean isOperationalQuestionTurn,
            List<PackageCatalogService.PackageSummary> visiblePackages
    ) {
        if (skill == null || !skill.active() || skill.confidenceScore() < MIN_GOVERNED_CONFIDENCE) {
            return false;
        }
        if (Boolean.TRUE.equals(skill.conflictDetected())) {
            return false;
        }
        if (skill.pageId() != null && !skill.pageId().isBlank() && !"GLOBAL".equalsIgnoreCase(skill.pageId().strip())) {
            if (pageId == null || !skill.pageId().strip().equalsIgnoreCase(pageId.toString())) {
                return false;
            }
        }
        String instr = skill.tacticalInstructions() != null ? skill.tacticalInstructions().strip() : "";
        if (!instr.isEmpty()) {
            if (conflictsWithTurnStageOrReplyTarget(instr, resolvedStage, safeQuote, isOperationalQuestionTurn)) {
                return false;
            }
            if (hasUnverifiedPriceInDirective(instr, visiblePackages)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLearnedRuleAllowedForPageAndTurn(
            LearnedRuleDto rule,
            UUID pageId,
            String resolvedStage,
            List<String> turnSignals,
            QuotedReplyResolution safeQuote,
            boolean isOperationalQuestionTurn,
            List<PackageCatalogService.PackageSummary> visiblePackages,
            String customerMessage
    ) {
        if (rule == null || !rule.active() || rule.confidenceScore() < MIN_GOVERNED_CONFIDENCE) {
            return false;
        }
        if (rule.quarantinedReason() != null && !rule.quarantinedReason().isBlank()) {
            return false;
        }
        String review = rule.reviewStatus() != null ? rule.reviewStatus().strip().toUpperCase(Locale.ROOT) : "APPROVED";
        if ("REJECTED".equals(review) || "QUARANTINED".equals(review) || "DISABLED".equals(review)) {
            return false;
        }
        if (rule.pageId() != null && !rule.pageId().isBlank() && !"GLOBAL".equalsIgnoreCase(rule.pageId().strip())) {
            if (pageId == null || !rule.pageId().strip().equalsIgnoreCase(pageId.toString())) {
                return false;
            }
        }
        if (rule.stage() != null && !rule.stage().isBlank() && !"ALL".equalsIgnoreCase(rule.stage().strip())) {
            if (!matchesStageFilter(resolvedStage, rule.stage())) {
                return false;
            }
        }
        String combinedText = composeCompactLearnedRuleText(rule);
        if (combinedText.isEmpty()) {
            return false;
        }
        if (conflictsWithTurnStageOrReplyTarget(combinedText, resolvedStage, safeQuote, isOperationalQuestionTurn)) {
            return false;
        }
        if (hasUnverifiedPriceInDirective(combinedText, visiblePackages)) {
            return false;
        }
        if (!rule.alwaysOn()) {
            boolean keywordMatched = false;
            String msgLow = customerMessage != null ? customerMessage.toLowerCase(Locale.ROOT) : "";
            if (rule.retrievalKeywords() != null && !rule.retrievalKeywords().isEmpty()) {
                for (String kw : rule.retrievalKeywords()) {
                    if (kw != null && !kw.isBlank() && msgLow.contains(kw.strip().toLowerCase(Locale.ROOT))) {
                        keywordMatched = true;
                        break;
                    }
                }
            }
            boolean intentMatched = false;
            if (rule.intent() != null && !rule.intent().isBlank() && turnSignals != null) {
                String rIntent = rule.intent().strip().toUpperCase(Locale.ROOT);
                intentMatched = turnSignals.stream()
                        .filter(Objects::nonNull)
                        .anyMatch(sig -> sig.toUpperCase(Locale.ROOT).contains(rIntent) || rIntent.contains(sig.toUpperCase(Locale.ROOT)));
            }
            boolean hasExplicitFilter = (rule.retrievalKeywords() != null && !rule.retrievalKeywords().isEmpty())
                    || (rule.intent() != null && !rule.intent().isBlank());
            if (hasExplicitFilter && !keywordMatched && !intentMatched) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesStageFilter(String resolvedStage, String ruleStage) {
        if (resolvedStage == null || ruleStage == null) {
            return true;
        }
        String resUp = resolvedStage.strip().toUpperCase(Locale.ROOT);
        String ruleUp = ruleStage.strip().toUpperCase(Locale.ROOT);
        if (resUp.contains(ruleUp) || ruleUp.contains(resUp)) {
            return true;
        }
        if (("DISCOVERY".equals(ruleUp) || "CONSULTATION".equals(ruleUp)) && resUp.startsWith("STAGE_1")) {
            return true;
        }
        if ("OBJECTION_HANDLING".equals(ruleUp) && (resUp.startsWith("STAGE_2") || resUp.startsWith("STAGE_3"))) {
            return true;
        }
        if (("BOOKING".equals(ruleUp) || "CLOSING".equals(ruleUp)) && resUp.startsWith("STAGE_4")) {
            return true;
        }
        return false;
    }

    private static boolean conflictsWithTurnStageOrReplyTarget(
            String directiveText,
            String resolvedStage,
            QuotedReplyResolution safeQuote,
            boolean isOperationalQuestionTurn
    ) {
        if (directiveText == null || directiveText.isBlank()) {
            return false;
        }
        String stage = resolvedStage != null ? resolvedStage : "";
        if ("STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE".equals(stage)
                && DOWNSELL_OVERRIDE_DIRECTIVE_PATTERN.matcher(directiveText).find()) {
            return true;
        }
        if (safeQuote != null && (safeQuote.unresolvedReplyToMid() || safeQuote.ambiguousMultiplePackages())) {
            if (AGGRESSIVE_CLOSE_OR_DEFAULT_PKG_DIRECTIVE_PATTERN.matcher(directiveText).find()
                    || DOWNSELL_OVERRIDE_DIRECTIVE_PATTERN.matcher(directiveText).find()) {
                return true;
            }
        }
        if (("STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE".equals(stage)
                || "STAGE_4B_POST_BOOKING_CONFIRMED_CLOSING".equals(stage)
                || isOperationalQuestionTurn)
                && AGGRESSIVE_CLOSE_OR_DEFAULT_PKG_DIRECTIVE_PATTERN.matcher(directiveText).find()) {
            return true;
        }
        return false;
    }

    private static boolean hasUnverifiedPriceInDirective(
            String directiveText,
            List<PackageCatalogService.PackageSummary> visiblePackages
    ) {
        if (directiveText == null || directiveText.isBlank()) {
            return false;
        }
        Set<Long> visiblePrices = new LinkedHashSet<>();
        if (visiblePackages != null) {
            for (PackageCatalogService.PackageSummary p : visiblePackages) {
                if (p != null && p.priceAmountVnd() > 0L) {
                    visiblePrices.add(p.priceAmountVnd());
                }
            }
        }
        Matcher m = DIRECTIVE_PRICE_TOKEN_PATTERN.matcher(directiveText);
        while (m.find()) {
            PackageCatalogService.ParsedPrice parsed = PackageCatalogService.parseStructuredPrice(m.group(0));
            if (parsed.parseStatus() == PackageCatalogService.ParseStatus.VERIFIED && parsed.priceAmountVnd() >= 10_000L) {
                if (visiblePrices.isEmpty() || !visiblePrices.contains(parsed.priceAmountVnd())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String composeCompactLearnedRuleText(LearnedRuleDto rule) {
        if (rule == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (rule.generalizedRule() != null && !rule.generalizedRule().isBlank()) {
            parts.add(rule.generalizedRule().strip().replaceAll("\\s+", " "));
        }
        if (rule.doActions() != null && !rule.doActions().isBlank()
                && (parts.isEmpty() || !parts.get(0).contains(rule.doActions().strip()))) {
            parts.add("Nên: " + rule.doActions().strip().replaceAll("\\s+", " "));
        }
        if (rule.dontActions() != null && !rule.dontActions().isBlank()) {
            parts.add("Tránh: " + rule.dontActions().strip().replaceAll("\\s+", " "));
        }
        return String.join(" | ", parts);
    }

    private static String truncateToBudget(String text, int maxChars) {
        if (text == null || maxChars <= 0) {
            return "";
        }
        String s = text.strip();
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, Math.max(1, maxChars - 3)).strip() + "...";
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

    private static final Set<String> GENERIC_PRODUCT_NAME_STOPWORDS = Set.of(
            "gói", "mẫu", "khóa", "lớp", "dòng", "dịch", "vụ", "liệu", "trình",
            "cơ", "bản", "tiêu", "chuẩn", "nâng", "cao", "chuyên", "sâu",
            "phút", "giờ", "buổi", "tháng", "năm", "răng", "sứ", "trám", "thẩm", "mỹ",
            "hàn", "quốc", "nhật", "đức", "pháp", "việt", "nam"
    );

    public static boolean customerExplicitlyAskedForEconomicalGroup(
            String customerMessage,
            List<PackageCatalogService.PackageSummary> economicals
    ) {
        if (customerMessage == null || customerMessage.isBlank() || economicals == null || economicals.isEmpty()) {
            return false;
        }
        String low = customerMessage.toLowerCase(Locale.ROOT);
        for (PackageCatalogService.PackageSummary p : economicals) {
            if (isPackageExplicitlyMentionedInCustomerText(low, p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPackageExplicitlyMentionedInCustomerText(
            String lowCustomerText,
            PackageCatalogService.PackageSummary p
    ) {
        if (lowCustomerText == null || lowCustomerText.isBlank() || p == null) {
            return false;
        }
        if (p.code() != null && !p.code().isBlank() && lowCustomerText.contains(p.code().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (p.priceDisplay() != null && !p.priceDisplay().isBlank()) {
            String lowPrice = p.priceDisplay().toLowerCase(Locale.ROOT);
            String compactPrice = lowPrice.replaceAll("/.*$", "").strip();
            if (!compactPrice.isBlank() && lowCustomerText.contains(compactPrice)) {
                return true;
            }
        }
        if (p.displayName() != null && !p.displayName().isBlank()) {
            String dispLow = p.displayName().toLowerCase(Locale.ROOT).strip();
            if (lowCustomerText.contains(dispLow)) {
                return true;
            }
            String[] words = dispLow.split("[\\s/(),+-]+");
            for (String w : words) {
                if (w.length() >= 4 && !GENERIC_PRODUCT_NAME_STOPWORDS.contains(w)
                        && !w.matches("\\d+.*")
                        && lowCustomerText.matches("(?isU).*\\b" + Pattern.quote(w) + "\\b.*")) {
                    return true;
                }
            }
            for (int i = 0; i + 1 < words.length; i++) {
                String bigram = (words[i] + " " + words[i + 1]).strip();
                boolean bothGeneric = GENERIC_PRODUCT_NAME_STOPWORDS.contains(words[i])
                        && GENERIC_PRODUCT_NAME_STOPWORDS.contains(words[i + 1]);
                if (bigram.length() >= 6 && !bothGeneric && lowCustomerText.contains(bigram)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static PackageCatalogService.PackageSummary selectPreferredEconomicalAlternative(
            List<PackageCatalogService.PackageSummary> economicals,
            List<FanpageBotRequest.ConversationTurn> history,
            String heldPackageDisplay,
            String heldPriceDisplay,
            String customerMessage,
            Set<String> currentMsgScopes,
            List<String> knownSymptoms
    ) {
        if (economicals == null || economicals.isEmpty()) {
            return null;
        }
        String lowCustomer = customerMessage != null ? customerMessage.toLowerCase(Locale.ROOT) : "";
        for (PackageCatalogService.PackageSummary p : economicals) {
            if (isPackageExplicitlyMentionedInCustomerText(lowCustomer, p)) {
                return p;
            }
        }
        if (currentMsgScopes != null && !currentMsgScopes.isEmpty()) {
            PackageCatalogService.PackageSummary bestScopeEcon = null;
            int bestHits = 0;
            for (PackageCatalogService.PackageSummary p : economicals) {
                int h = computePackageScopeDirectHits(p, currentMsgScopes, knownSymptoms);
                if (h > bestHits) {
                    bestHits = h;
                    bestScopeEcon = p;
                }
            }
            if (bestScopeEcon != null && bestHits > 0) {
                return bestScopeEcon;
            }
        }
        if (heldPriceDisplay != null && !"null".equalsIgnoreCase(heldPriceDisplay)) {
            Set<Long> heldPrices = CleanAgentOutputRefereeAndSanitizer.extractPricesInVndFromText(heldPriceDisplay);
            if (!heldPrices.isEmpty()) {
                for (PackageCatalogService.PackageSummary p : economicals) {
                    if (p != null && heldPrices.contains(p.priceAmountVnd())) {
                        return p;
                    }
                }
            }
        }
        if (heldPackageDisplay != null && !"null".equalsIgnoreCase(heldPackageDisplay)) {
            String lowHeld = heldPackageDisplay.toLowerCase(Locale.ROOT);
            for (PackageCatalogService.PackageSummary p : economicals) {
                if (p != null && p.displayName() != null && lowHeld.contains(p.displayName().toLowerCase(Locale.ROOT))) {
                    return p;
                }
            }
        }
        if (history != null && !history.isEmpty()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                FanpageBotRequest.ConversationTurn t = history.get(i);
                if (t == null || t.content() == null || t.role() == null) continue;
                if (!"assistant".equalsIgnoreCase(t.role()) && !"bot".equalsIgnoreCase(t.role())) continue;
                for (PackageCatalogService.PackageSummary p : economicals) {
                    if (isAnyPackagePresentedInBotText(t.content(), List.of(p))) {
                        return p;
                    }
                }
            }
        }
        PackageCatalogService.PackageSummary bestCoverage = null;
        int bestScore = Integer.MIN_VALUE;
        for (PackageCatalogService.PackageSummary p : economicals) {
            if (p == null) continue;
            int symptomHits = computePackageScopeDirectHits(p, Set.of(), knownSymptoms);
            int breadth = p.scopeTags() != null ? p.scopeTags().size() : 0;
            int score = symptomHits * 10 + Math.min(breadth, 4);
            if (score > bestScore) {
                bestScore = score;
                bestCoverage = p;
            }
        }
        return bestCoverage != null ? bestCoverage : economicals.get(0);
    }

    private static List<PackageCatalogService.PackageSummary> orderEconomicalsForPresentation(
            List<PackageCatalogService.PackageSummary> economicals,
            PackageCatalogService.PackageSummary preferred
    ) {
        if (economicals == null || economicals.isEmpty()) {
            return List.of();
        }
        if (preferred == null || !economicals.contains(preferred)) {
            return economicals;
        }
        List<PackageCatalogService.PackageSummary> ordered = new ArrayList<>(economicals.size());
        ordered.add(preferred);
        for (PackageCatalogService.PackageSummary p : economicals) {
            if (p != null && !ordered.contains(p)) {
                ordered.add(p);
            }
        }
        return ordered;
    }

    private static List<PackageCatalogService.PackageSummary> resolveComparisonPackagesFromHistory(
            TieredCatalogResult tieredCatalog,
            List<FanpageBotRequest.ConversationTurn> history,
            String effectiveHeldPackageDisplay,
            String effectiveHeldPriceDisplay,
            PackageCatalogService.PackageSummary activePkgForBrain,
            PackageCatalogService.PackageSummary preferredEconomicalPkg,
            int priceObjectionCount
    ) {
        List<PackageCatalogService.PackageSummary> allAllowed = tieredCatalog != null
                ? tieredCatalog.allAllowedPackages()
                : List.of();
        List<PackageCatalogService.PackageSummary> recentFromHistory = new ArrayList<>();
        if (history != null && !history.isEmpty() && !allAllowed.isEmpty()) {
            for (int i = history.size() - 1; i >= 0 && recentFromHistory.size() < 2; i--) {
                FanpageBotRequest.ConversationTurn t = history.get(i);
                if (t == null || t.content() == null || t.role() == null) continue;
                if (!"assistant".equalsIgnoreCase(t.role()) && !"bot".equalsIgnoreCase(t.role())) continue;
                for (PackageCatalogService.PackageSummary p : allAllowed) {
                    if (p != null && !recentFromHistory.contains(p) && isAnyPackagePresentedInBotText(t.content(), List.of(p))) {
                        recentFromHistory.add(p);
                        if (recentFromHistory.size() >= 2) break;
                    }
                }
            }
        }
        List<PackageCatalogService.PackageSummary> comparisonList = new ArrayList<>();
        for (int i = recentFromHistory.size() - 1; i >= 0; i--) {
            comparisonList.add(recentFromHistory.get(i));
        }
        if (comparisonList.size() < 2 && tieredCatalog != null) {
            PackageCatalogService.PackageSummary primaryCmp = findHeldOrTopPrimaryPackage(
                    tieredCatalog, effectiveHeldPackageDisplay, effectiveHeldPriceDisplay);
            if (primaryCmp != null && !comparisonList.contains(primaryCmp)) {
                comparisonList.add(0, primaryCmp);
            }
        }
        if (comparisonList.size() < 2 && activePkgForBrain != null && !comparisonList.contains(activePkgForBrain)) {
            comparisonList.add(activePkgForBrain);
        }
        if (comparisonList.size() < 2 && preferredEconomicalPkg != null
                && (priceObjectionCount >= 1 || historyMentionsAnyPackage(history, List.of(preferredEconomicalPkg)))
                && !comparisonList.contains(preferredEconomicalPkg)) {
            comparisonList.add(preferredEconomicalPkg);
        }
        if (comparisonList.size() < 2 && tieredCatalog != null && tieredCatalog.primaryRecommendations() != null) {
            for (PackageCatalogService.PackageSummary cand : tieredCatalog.primaryRecommendations()) {
                if (cand != null && !comparisonList.contains(cand)) {
                    comparisonList.add(cand);
                    if (comparisonList.size() >= 2) break;
                }
            }
        }
        return comparisonList;
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

    public static PackageCatalogService.PackageSummary findProductByIdInTieredCatalog(
            TieredCatalogResult tieredCatalog,
            String productId
    ) {
        if (tieredCatalog == null || productId == null || productId.isBlank()) {
            return null;
        }
        String target = productId.strip();
        for (PackageCatalogService.PackageSummary p : tieredCatalog.allAllowedPackages()) {
            if (p == null) continue;
            if (target.equalsIgnoreCase(p.productId())
                    || (p.code() != null && target.equalsIgnoreCase(p.code()))
                    || (p.canonicalName() != null && target.equalsIgnoreCase(p.canonicalName()))) {
                return p;
            }
        }
        if (tieredCatalog.excludedCatalog() != null) {
            for (PackageCatalogService.PackageSummary p : tieredCatalog.excludedCatalog()) {
                if (p == null) continue;
                if (target.equalsIgnoreCase(p.productId())
                        || (p.code() != null && target.equalsIgnoreCase(p.code()))) {
                    return p;
                }
            }
        }
        return null;
    }

    public static List<PackageCatalogService.PackageSummary> resolveBookingCandidateProducts(
            TieredCatalogResult tieredCatalog,
            List<FanpageBotRequest.ConversationTurn> history,
            ConversationState.StructuredBookingState bookingState
    ) {
        List<PackageCatalogService.PackageSummary> out = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        if (bookingState != null && bookingState.candidateProductIds() != null && tieredCatalog != null) {
            for (String cid : bookingState.candidateProductIds()) {
                PackageCatalogService.PackageSummary found = findProductByIdInTieredCatalog(tieredCatalog, cid);
                if (found != null && seenIds.add(found.productId())) {
                    out.add(found);
                }
            }
        }
        if (out.isEmpty() && tieredCatalog != null) {
            List<PackageCatalogService.PackageSummary> allPool = new ArrayList<>(tieredCatalog.allAllowedPackages());
            if (tieredCatalog.excludedCatalog() != null) {
                allPool.addAll(tieredCatalog.excludedCatalog());
            }
            for (PackageCatalogService.PackageSummary p : extractPresentedPackagesFromHistory(history, null, allPool)) {
                if (p != null && seenIds.add(p.productId())) {
                    out.add(p);
                }
            }
        }
        if (out.isEmpty() && tieredCatalog != null) {
            for (PackageCatalogService.PackageSummary p : tieredCatalog.allAllowedPackages()) {
                if (p != null && seenIds.add(p.productId())) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    // =========================================================================
    // ACI – ADAPTIVE CONVERSATION INTELLIGENCE (LLM_COGNITIVE_V2) PROMPT BUILDER
    // =========================================================================

    private static final Set<UUID> BUILT_IN_FALLBACK_SKILL_IDS = CustomerInsightEngine.builtInDefaultSkills().stream()
            .filter(Objects::nonNull)
            .map(AgentSkillDto::id)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    public static List<AgentSkillDto> filterPageConfiguredSkills(List<AgentSkillDto> rawSkills, UUID pageId) {
        if (rawSkills == null || rawSkills.isEmpty()) {
            return List.of();
        }
        String pageIdStr = pageId != null ? pageId.toString() : null;
        List<AgentSkillDto> filtered = new ArrayList<>();
        for (AgentSkillDto s : rawSkills) {
            if (s == null || !s.active()) {
                continue;
            }
            boolean hasExplicitPageScope = s.pageId() != null && !s.pageId().isBlank();
            if (hasExplicitPageScope) {
                if (pageIdStr == null || pageIdStr.equalsIgnoreCase(s.pageId().strip())) {
                    filtered.add(s);
                }
                continue;
            }
            // Loại bỏ ~50 skill mẫu đa ngành mặc định trong mã Java (CustomerInsightEngine.builtInDefaultSkills)
            // khi Page chưa cấu hình skill đó trong DB; giữ lại nếu là skill global tùy chỉnh quản trị viên tạo thêm.
            if (s.id() != null && BUILT_IN_FALLBACK_SKILL_IDS.contains(s.id())) {
                continue;
            }
            filtered.add(s);
        }
        return List.copyOf(filtered);
    }

    public static List<PackageCatalogService.PackageSummary> deduplicateCatalogByCanonicalSignature(
            List<PackageCatalogService.PackageSummary> packages
    ) {
        if (packages == null || packages.isEmpty()) {
            return List.of();
        }
        Map<String, PackageCatalogService.PackageSummary> byCanonicalKey = new java.util.LinkedHashMap<>();
        for (PackageCatalogService.PackageSummary p : packages) {
            if (p == null || p.isAddon()) {
                continue;
            }
            String sig = buildCanonicalPackageSignature(p);
            PackageCatalogService.PackageSummary existing = byCanonicalKey.get(sig);
            if (existing == null) {
                byCanonicalKey.put(sig, p);
            } else {
                long exPrice = existing.priceAmountVnd();
                long candPrice = p.priceAmountVnd();
                if (exPrice <= 0L && candPrice > 0L) {
                    byCanonicalKey.put(sig, p);
                    continue;
                }
                if (exPrice > 0L && candPrice > 0L && candPrice < exPrice) {
                    byCanonicalKey.put(sig, p);
                    continue;
                }
                if (exPrice == candPrice) {
                    boolean existingHasSummary = (existing.includesSummary() != null && !existing.includesSummary().isBlank())
                            || (existing.processSummary() != null && !existing.processSummary().isBlank());
                    boolean candidateHasSummary = (p.includesSummary() != null && !p.includesSummary().isBlank())
                            || (p.processSummary() != null && !p.processSummary().isBlank());
                    if (!existingHasSummary && candidateHasSummary) {
                        byCanonicalKey.put(sig, p);
                    }
                }
            }
        }
        return List.copyOf(byCanonicalKey.values());
    }

    private static String buildCanonicalPackageSignature(PackageCatalogService.PackageSummary p) {
        String code = (p.code() != null && !p.code().isBlank())
                ? p.code().strip().toUpperCase(Locale.ROOT)
                : "";
        if (!code.isEmpty()) {
            return "SKU|" + code + "|" + p.durationMinutes();
        }
        String label = Normalizer.normalize(
                        p.canonicalLabel() != null ? p.canonicalLabel() : "",
                        Normalizer.Form.NFC)
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return "|" + label + "|" + p.priceAmountVnd() + "|" + p.durationMinutes();
    }

    public static List<LearnedRuleDto> selectTurnRelevantCompactRules(
            List<LearnedRuleDto> rawRules,
            String currentMessage,
            boolean isOpeningTurn,
            int maxRules
    ) {
        if (rawRules == null || rawRules.isEmpty() || maxRules <= 0) {
            return List.of();
        }
        Set<String> seenRuleKeys = new LinkedHashSet<>();
        List<LearnedRuleDto> deduplicated = new ArrayList<>();
        List<LearnedRuleDto> sortedAll = rawRules.stream()
                .filter(r -> r != null && r.active()
                        && (r.reviewStatus() == null || "APPROVED".equalsIgnoreCase(r.reviewStatus())))
                .sorted(Comparator
                        .comparing(LearnedRuleDto::alwaysOn).reversed()
                        .thenComparing((LearnedRuleDto r) -> r.priority() != null ? r.priority() : 80, Comparator.reverseOrder())
                        .thenComparingDouble(LearnedRuleDto::confidenceScore).reversed())
                .toList();
        for (LearnedRuleDto r : sortedAll) {
            String code = r.ruleCode() != null ? r.ruleCode().strip() : "";
            String cat = r.category() != null ? r.category().strip().toUpperCase(Locale.ROOT) : "";
            if (!isOpeningTurn && (code.toUpperCase(Locale.ROOT).contains("-OPEN-")
                    || "OPENING".equals(cat) || "GREETING".equals(cat))) {
                continue;
            }
            String primaryText = (r.doActions() != null && !r.doActions().isBlank())
                    ? r.doActions()
                    : r.generalizedRule();
            String dedupeKey = !code.isEmpty()
                    ? code.toLowerCase(Locale.ROOT)
                    : compactSingleLine(primaryText, 96).toLowerCase(Locale.ROOT);
            if (!dedupeKey.isEmpty() && seenRuleKeys.add(dedupeKey)) {
                deduplicated.add(r);
            }
        }
        String normMsg = Normalizer.normalize(currentMessage != null ? currentMessage : "", Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
        String[] msgTokens = normMsg.split("[^\\p{L}\\p{N}]+");

        // Unit P1.3: Xóa hoàn toàn nhánh backfill mù (deduplicated.size() <= maxRules -> return all).
        // Chỉ luôn nạp các rule có Boolean.TRUE.equals(r.alwaysOn()) NẾU không bị chặn bởi semantic gate (SAFETY/CORRECTION/STYLE);
        // với rule ngữ cảnh (alwaysOn == false), bắt buộc scoreLearnedRuleRelevance(...) > 0 mới được đưa vào <PAGE_PLAYBOOK_COMPACT_DIRECTIVES>.
        boolean isStyleComplaintTurn = CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(currentMessage);
        // ACI V3 P1 §3.4: Lượt phản bác giả định sai (CUSTOMER_CORRECTION / REJECTED_BOT_ASSUMPTION) —
        // chặn mọi learned rule thúc ép chốt đơn sớm / xin SĐT / khóa cứng khung giờ / template cứng
        // (VD các rule học lệch vertical từ dental/derma áp vào Nàng Sen) — trừ rule SAFETY / CORRECTION / EMOTIONAL-REPAIR.
        boolean isScopeCorrectionTurn = false;
        if (currentMessage != null && !currentMessage.isBlank()) {
            VietnameseScopeSignalExtractor.ScopeSignalResult sig =
                    VietnameseScopeSignalExtractor.analyze(currentMessage, List.of());
            isScopeCorrectionTurn = sig.possibleCorrection() || sig.explicitCorrectionGroupD()
                    || AdaptiveConversationIntelligence.isCustomerCorrectionOrComponentRejectionMessage(currentMessage);
        }
        List<LearnedRuleDto> selected = new ArrayList<>();
        record ScoredContextualRule(LearnedRuleDto rule, int score) {}
        List<ScoredContextualRule> scoredContextualPool = new ArrayList<>();
        for (LearnedRuleDto r : deduplicated) {
            String codeUpper = r.ruleCode() != null ? r.ruleCode().strip().toUpperCase(Locale.ROOT) : "";
            String catUpper = r.category() != null ? r.category().strip().toUpperCase(Locale.ROOT) : "";
            boolean isStrictNeuroOrContraindicationRule = codeUpper.startsWith("SAFE-");
            boolean isSafetyRule = codeUpper.contains("SAFETY") || isStrictNeuroOrContraindicationRule || catUpper.equals("SAFETY");
            if (isStrictNeuroOrContraindicationRule && (isStyleComplaintTurn || !hasHighRiskOrContraindicationCue(normMsg))) {
                continue;
            }
            if (isSafetyRule && (isStyleComplaintTurn || !hasSafetyOrContraindicationCue(normMsg))) {
                continue;
            }
            if (isStyleComplaintTurn
                    && !codeUpper.contains("EMOTIONAL-REPAIR")
                    && !codeUpper.contains("STYLE-REPAIR")) {
                continue;
            }
            if (isScopeCorrectionTurn
                    && !isSafetyRule
                    && !codeUpper.contains("EMOTIONAL-REPAIR")
                    && !codeUpper.contains("CORRECTION")
                    && rulePushesEarlyClosingOrRigidTemplate(r)) {
                continue;
            }
            if (Boolean.TRUE.equals(r.alwaysOn())) {
                if (selected.size() < maxRules) {
                    selected.add(r);
                }
            } else {
                int score = scoreLearnedRuleRelevance(r, normMsg, msgTokens);
                if (score > 0) {
                    scoredContextualPool.add(new ScoredContextualRule(r, score));
                }
            }
        }
        scoredContextualPool.sort((a, b) -> {
            if (a.score() != b.score()) {
                return Integer.compare(b.score(), a.score());
            }
            int prioA = a.rule().priority() != null ? a.rule().priority() : 80;
            int prioB = b.rule().priority() != null ? b.rule().priority() : 80;
            return Integer.compare(prioB, prioA);
        });
        for (ScoredContextualRule sc : scoredContextualPool) {
            if (selected.size() >= maxRules) {
                break;
            }
            selected.add(sc.rule());
        }
        return List.copyOf(selected);
    }

    /**
     * ACI V3 P1 §3.4: Nhận diện learned rule thúc ép chốt đơn sớm / xin SĐT / khóa cứng khung giờ /
     * template cứng — bị chặn trên lượt phản bác giả định sai để không "vừa xin lỗi vừa chốt lịch".
     */
    private static final Pattern EARLY_CLOSING_OR_RIGID_TEMPLATE_RULE_PATTERN = Pattern.compile(
            "(?isU).*(chốt\\s+(?:đơn|lịch|gói|luôn)|khóa\\s+(?:cứng\\s+)?(?:khung\\s+)?giờ|khóa\\s+lịch|"
                    + "xin\\s+(?:sđt|sdt|số\\s+điện\\s+thoại)|gửi\\s+(?:sđt|sdt|số\\s+điện\\s+thoại)|"
                    + "đồng\\s+ý\\s+thử|xác\\s+nhận\\s+đặt\\s+lịch\\s+ngay|mẫu\\s+câu\\s+cố\\s+định|template\\s+cứng).*"
    );

    private static boolean rulePushesEarlyClosingOrRigidTemplate(LearnedRuleDto r) {
        String content = ((r.ruleCode() != null ? r.ruleCode() : "") + " "
                + (r.generalizedRule() != null ? r.generalizedRule() : "") + " "
                + (r.doActions() != null ? r.doActions() : ""));
        return EARLY_CLOSING_OR_RIGID_TEMPLATE_RULE_PATTERN.matcher(content).matches();
    }

    private static final Set<String> LEARNED_RULE_MATCH_STOPWORDS = Set.of(
            "mình", "chị", "anh", "em", "bạn", "tôi", "shop", "bên", "hỏi", "nói", "làm",
            "đang", "phải", "chứ", "trên", "rồi", "vậy", "xem", "muốn", "giúp", "cho",
            "của", "với", "nhé", "nha", "không", "được", "khi", "khách", "quá", "hơi",
            "còn", "hơn", "nữa", "chưa", "vâng", "mới", "mấy", "hôm", "nay", "nào",
            "sao", "thế", "như", "cái", "này", "kia", "đâu", "luôn", "thôi", "nhỉ",
            "giá", "gói", "tiền", "bao", "nhiêu", "chi", "phí", "dịch", "vụ", "tư", "vấn",
            "có", "thử", "loại", "mức", "tầm", "khoảng"
    );

    static int scoreLearnedRuleRelevance(LearnedRuleDto r, String normMsg, String[] msgTokens) {
        if (r == null || normMsg == null || normMsg.isBlank()) {
            return 0;
        }
        String codeUpper = r.ruleCode() != null ? r.ruleCode().strip().toUpperCase(Locale.ROOT) : "";
        String catUpper = r.category() != null ? r.category().strip().toUpperCase(Locale.ROOT) : "";
        boolean isStyleComplaint = CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(normMsg);
        if (isStyleComplaint
                && !codeUpper.contains("EMOTIONAL-REPAIR")
                && !codeUpper.contains("STYLE-REPAIR")) {
            return 0;
        }

        // Gate chuyên biệt cho các rule SAFETY / CORRECTION / EMOTIONAL-REPAIR / PRICE-OBJECTION / EAR / BOOKING / TRIAL-ACCEPT:
        // ngăn từ khóa chung ("giá", "gói", "làm", "mình", "shop") kích hoạt nhầm ở lượt hỏi giá/ngân sách/tư vấn thông thường.
        int semanticGateBonus = 0;
        if (codeUpper.startsWith("SAFE-")) {
            if (!hasHighRiskOrContraindicationCue(normMsg)) {
                return 0;
            }
            semanticGateBonus = 20;
        } else if (codeUpper.contains("SAFETY") || catUpper.equals("SAFETY")) {
            if (!hasSafetyOrContraindicationCue(normMsg)) {
                return 0;
            }
            semanticGateBonus = 20;
        } else if (codeUpper.contains("CORRECTION")) {
            if (!hasCorrectionOrNeedRevisionCue(normMsg)) {
                return 0;
            }
            semanticGateBonus = 20;
        } else if (codeUpper.contains("EMOTIONAL-REPAIR") || codeUpper.contains("STYLE-REPAIR")) {
            if (!hasCommunicationOrMoodComplaintCue(normMsg) && !isStyleComplaint) {
                return 0;
            }
            semanticGateBonus = 20;
        } else if (codeUpper.contains("PRICE-OBJECTION") || catUpper.equals("PRICE_OBJECTION")) {
            if (!hasExplicitPriceObjectionOrDownsellCue(normMsg)) {
                return 0;
            }
            semanticGateBonus = 16;
        } else if (codeUpper.contains("DISCOVER")) {
            if (hasExplicitPriceObjectionOrDownsellCue(normMsg)
                    || CleanAgentOrchestratorService.hasCompetitorOrExternalPriceComparisonCue(normMsg)
                    || AdaptiveConversationIntelligence.isBarePauseOrAcknowledgementMessage(normMsg)
                    || AdaptiveConversationIntelligence.classifyPriceIntentSubMode(normMsg) != AdaptiveConversationIntelligence.PriceIntentSubMode.NONE
                    || normMsg.matches("(?isU)^\\s*(?:dạ\\s+)?(?:cả\\s*(?:2|hai)|làm\\s+cả\\s*(?:2|hai))\\s*[.!]*\\s*$")) {
                return 0;
            }
        } else if (codeUpper.contains("HERBAL")) {
            if (CleanAgentOutputRefereeAndSanitizer.isUnconfirmedHealthSymptomWithoutServiceDemand(normMsg, List.of())
                    || AdaptiveConversationIntelligence.isBarePauseOrAcknowledgementMessage(normMsg)) {
                return 0;
            }
        } else if (codeUpper.contains("-EAR-") || codeUpper.endsWith("-EAR")) {
            if (!hasExplicitEarCareCue(normMsg)) {
                return 0;
            }
            semanticGateBonus = 16;
        } else if (codeUpper.contains("BOOKING") || catUpper.equals("BOOKING")) {
            if (!hasExplicitBookingOrScheduleCue(normMsg)) {
                return 0;
            }
            semanticGateBonus = 14;
        } else if (codeUpper.contains("TRIAL-ACCEPT")) {
            if (!hasExplicitTrialAcceptanceCue(normMsg)) {
                return 0;
            }
            semanticGateBonus = 14;
        }

        Set<String> msgTokenSet = new LinkedHashSet<>();
        if (msgTokens != null) {
            for (String tok : msgTokens) {
                if (tok != null && !tok.isBlank()) {
                    msgTokenSet.add(tok.toLowerCase(Locale.ROOT));
                }
            }
        }

        StringBuilder corpus = new StringBuilder(256);
        if (r.triggerCondition() != null) corpus.append(r.triggerCondition()).append(' ');
        if (r.title() != null) corpus.append(r.title()).append(' ');
        if (r.retrievalKeywords() != null) {
            for (String kw : r.retrievalKeywords()) {
                if (kw != null && !kw.isBlank()) corpus.append(kw).append(' ');
            }
        }
        if (!catUpper.isEmpty()) corpus.append(catUpper).append(' ');
        if (!codeUpper.isEmpty()) corpus.append(codeUpper).append(' ');
        String normCorpus = Normalizer.normalize(corpus.toString(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        Set<String> corpusTokenSet = new LinkedHashSet<>(java.util.Arrays.asList(normCorpus.split("[^\\p{L}\\p{N}]+")));

        int score = semanticGateBonus;
        if (r.retrievalKeywords() != null) {
            for (String kw : r.retrievalKeywords()) {
                if (kw == null || kw.isBlank()) continue;
                String nkw = Normalizer.normalize(kw.strip(), Normalizer.Form.NFC)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("\\s+", " ");
                if (nkw.length() < 2) continue;
                boolean multiWord = nkw.contains(" ");
                if (!multiWord && LEARNED_RULE_MATCH_STOPWORDS.contains(nkw)) {
                    continue;
                }
                if (multiWord ? containsWholePhrase(normMsg, nkw) : msgTokenSet.contains(nkw)) {
                    score += multiWord ? 16 : 12;
                }
            }
        }
        for (String tok : msgTokenSet) {
            if (tok.length() >= 3 && !LEARNED_RULE_MATCH_STOPWORDS.contains(tok) && corpusTokenSet.contains(tok)) {
                score += 4;
            }
        }
        return score;
    }

    private static boolean containsWholePhrase(String normMsg, String phrase) {
        if (normMsg == null || phrase == null || phrase.isEmpty()) {
            return false;
        }
        Pattern pat = Pattern.compile("(?iU)(?:^|[^\\p{L}\\p{N}])" + Pattern.quote(phrase) + "(?:$|[^\\p{L}\\p{N}])");
        return pat.matcher(normMsg).find();
    }

    static boolean hasHighRiskOrContraindicationCue(String normMsg) {
        if (normMsg == null || normMsg.isBlank()) {
            return false;
        }
        return FanpageBotService.isUniversalAcuteMedicalEmergency(normMsg)
                || normMsg.matches("(?isU).*\\b(mang\\s+thai|có\\s+bầu|đang\\s+bầu|bầu\\s+\\d+|thai\\s+kỳ|mới\\s+sinh|"
                        + "phẫu\\s+thuật|tiểu\\s+phẫu|hậu\\s+phẫu|mới\\s+mổ|vết\\s+mổ|vết\\s+thương|bỏng\\s+rát|sưng\\s+tấy|viêm\\s+tai|chảy\\s+dịch|dị\\s+ứng|"
                        + "cao\\s+huyết\\s+áp|huyết\\s+áp\\s+cao|tim\\s+mạch|thoát\\s+vị|chèn\\s+ép|tê\\s+lan)\\b.*");
    }

    static boolean hasContextualWellnessOrLifestyleContext(String normMsg) {
        if (normMsg == null || normMsg.isBlank()) {
            return false;
        }
        return normMsg.matches("(?isU).*\\b(vai|gáy|cổ|lưng|mỏi|mệt|căng\\s+thẳng|stress|mất\\s+ngủ|khó\\s+ngủ|"
                + "ngồi\\s+lâu|máy\\s+tính|văn\\s+phòng|làm\\s+việc|đứng\\s+dậy|gói|dịch\\s+vụ|liệu\\s+trình|"
                + "phù\\s+hợp|hợp\\s+không|bên\\s+em|bên\\s+mình|thư\\s+giãn|dưỡng\\s+sinh)\\b.*");
    }

    private static boolean hasSafetyOrContraindicationCue(String normMsg) {
        if (normMsg == null || normMsg.isBlank()) {
            return false;
        }
        if (hasHighRiskOrContraindicationCue(normMsg)) {
            return true;
        }
        if (hasContextualWellnessOrLifestyleContext(normMsg)) {
            return false;
        }
        return normMsg.matches("(?isU).*\\b(chóng\\s+mặt|hoa\\s+mắt|choáng\\s+váng|huyết\\s+áp)\\b.*");
    }

    private static boolean hasCorrectionOrNeedRevisionCue(String normMsg) {
        return normMsg.matches("(?isU).*(\\bkhông\\s+phải\\b|\\bnhầm\\b|\\bhiểu\\s+sai\\b|\\bý\\s+(mình|tôi|chị|anh)\\s+là\\b|\\bthôi\\s+không\\b|\\bđổi\\s+(sang|ý|gói)\\b|\\bchuyển\\s+sang\\b|\\bnói\\s+ở\\s+trên\\b|\\bdở\\s+à\\b|\\blàm\\s+(gì|j)\\b|\\bchứ\\s+không\\b|\\bhết\\s+(?:đau|mỏi|nhức|tê|bệnh|triệu\\s+chứng|mệt|căng|buốt|sưng)\\s+rồi\\b|\\bkhỏi\\s+lâu\\s+rồi\\b).*");
    }

    private static boolean hasExplicitBookingOrScheduleCue(String normMsg) {
        if (normMsg == null || normMsg.isBlank()) {
            return false;
        }
        return normMsg.matches("(?isU).*\\b(đặt\\s+lịch|giữ\\s+chỗ|giữ\\s+lịch|book|chốt\\s+lịch|còn\\s+chỗ|còn\\s+lịch|"
                + "mấy\\s+giờ|khung\\s+giờ|tối\\s+nay|chiều\\s+nay|ngày\\s+mai|\\d{1,2}\\s*(?:h|giờ)|cơ\\s+sở|chi\\s+nhánh|địa\\s+chỉ)\\b.*");
    }

    private static boolean hasExplicitTrialAcceptanceCue(String normMsg) {
        if (normMsg == null || normMsg.isBlank()) {
            return false;
        }
        return normMsg.matches("(?isU).*\\b(đi\\s+thử|làm\\s+thử|trải\\s+nghiệm\\s+thử|thử\\s+1\\s+buổi|thử\\s+một\\s+buổi|thử\\s+buổi\\s+lẻ|chốt\\s+gói|lấy\\s+gói)\\b.*");
    }

    private static boolean hasCommunicationOrMoodComplaintCue(String normMsg) {
        return normMsg.matches("(?isU).*(\\bthảo\\s+mai\\b|\\bsến\\b|\\bvăn\\s+vở\\b|\\bvăn\\s+mẫu\\b|\\bcụt\\s+(ngủn|lủn)\\b|\\bnch\\s+cụt\\b|\\btụt\\s+(hứng|mood|cảm\\s+xúc)\\b|\\btư\\s+vấn\\s+chán\\b|\\b(chẳng|chả|không)\\s+hiểu\\s+ý\\b|\\bmáy\\s+móc\\b|\\brập\\s+khuôn\\b|\\bđau\\s+lòng\\s+đâu\\b|\\bchán\\s+chả\\s+(buồn|muốn)\\b|\\b(?:được|đc)\\s+(dạy|huấn\\s+luyện|lập\\s+trình|train(?:ing)?)\\b|\\btheo\\s+kịch\\s+bản\\b|\\bhọc\\s+thuộc\\s+(bài|kịch\\s+bản)\\b|\\bchưa\\s+tìm\\s+hiểu\\b|\\bxả\\s+(?:cả\\s+)?đống\\s+thông\\s+tin\\b).*");
    }

    private static boolean hasExplicitPriceObjectionOrDownsellCue(String normMsg) {
        if (normMsg == null || normMsg.isBlank()
                || CleanAgentOrchestratorService.isPureBudgetBoundedServiceInquiry(normMsg)) {
            return false;
        }
        return normMsg.matches("(?isU).*(\\bđắt\\b|\\bcao\\s+quá\\b|\\bhơi\\s+cao\\b|\\bmắc\\b|\\bchát\\b|\\bmềm\\s+hơn\\b|\\brẻ\\s+hơn\\b|\\bhạ\\s+giá\\b|\\bgiảm\\s+giá\\b|\\bvượt\\s+ngân\\s+sách\\b|\\bkhông\\s+đủ\\s+tiền\\b|\\bngoài\\s+kia\\b.*\\brẻ\\b).*");
    }

    private static boolean hasExplicitEarCareCue(String normMsg) {
        if (normMsg == null || normMsg.isBlank()
                || CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(normMsg)) {
            return false;
        }
        return normMsg.matches("(?isU).*(\\bráy\\s+tai\\b|\\bchăm\\s+sóc\\s+tai\\b|\\bngứa\\s+tai\\b|\\bvệ\\s+sinh\\s+tai\\b).*");
    }

    private static boolean isRedundantCatalogTableChunkForAciV2(String chunkText, String currentMessage) {
        if (chunkText == null || chunkText.isBlank()) {
            return true;
        }
        if (isRedundantPriceTableChunk(chunkText)) {
            return true;
        }
        String low = Normalizer.normalize(chunkText, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        boolean isRawMarkdownCatalogTable = low.contains("| mã gói | tên gói |")
                || (low.contains("| mã gói |") && low.contains("| giá"));
        if (!isRawMarkdownCatalogTable) {
            return false;
        }
        String msgLow = Normalizer.normalize(currentMessage != null ? currentMessage : "", Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
        boolean asksDetailedProcedureSteps = msgLow.contains("quy trình")
                || msgLow.contains("các bước")
                || msgLow.contains("những bước")
                || msgLow.contains("chi tiết")
                || msgLow.contains("giải thích kỹ");
        return !asksDetailedProcedureSteps;
    }

    private static final Logger log = LoggerFactory.getLogger(CleanAgentCatalogAndPromptBuilder.class);

    public static final String IN_CODE_ACI_V2_PROMPT_VERSION = "aci-v2.10-p37b-consultative-journey";
    public static final String DEFAULT_EXTERNAL_ACI_V2_PROMPT_PATH = "/app/config/aci_v2_system_prompt.md";
    private static final Pattern PROMPT_VERSION_COMMENT_PATTERN = Pattern.compile(
            "(?i)<!--\\s*prompt_version\\s*:\\s*([A-Za-z0-9._-]+)\\s*-->"
    );
    private static final Object PROMPT_RELOAD_LOCK = new Object();
    private static volatile Path externalAciPromptPathOverride = null;
    private static volatile Path cachedExternalPromptPath = null;
    private static volatile long cachedExternalPromptLastModifiedMs = -1L;
    private static volatile String cachedValidatedExternalPromptTemplate = null;
    private static volatile String activeAciPromptVersion = IN_CODE_ACI_V2_PROMPT_VERSION;
    private static volatile String activeAciPromptSource = "IN_CODE_DEFAULT";

    private static final String ACI_COGNITIVE_SYSTEM_PROMPT_TEMPLATE = """
            Bạn là trợ lý AI tư vấn (vai: {{BOT_ROLE}}) của "{{BRAND_NAME}}" ({{INDUSTRY}}) trên kiến trúc ACI V2.10.
            Xưng hô: Tự xưng "{{SELF_PRONOUN}}" (gọi cơ sở là "bên {{SELF_PRONOUN}}", CẤM tự xưng "mình" khi {{SELF_PRONOUN}} là "em"), gọi khách nhất quán "{{CUSTOMER_PRONOUN}}" (CẤM trộn "bạn"), gọi nhân sự "{{CARE_ACTOR}}". Phong cách: "{{TONE_STYLE}}" — ấm áp, tinh tế (văn bản thuần, CẤM `**in đậm**` hay `1️⃣ 2️⃣`, CẤM mở đầu *"Dạ {{SELF_PRONOUN}} hiểu ạ"*, *"{{CUSTOMER_PRONOUN}} nói phải/nói đúng"*, CẤM áp đặt *"mình thấy hợp với tình trạng hiện tại"*).
            
            I. LIGHTWEIGHT CUSTOMER UNDERSTANDING (ĐA TÍN HIỆU), NEXT ACTION PLANNER & DECISION INTEGRITY:
            1. `conversational_context` & `Fact / Inference / Unknown`: Đối chiếu `[T_CURRENT]` với `<CONVERSATION_HISTORY>`, `<QUOTE_REPLY_CONTEXT>`, `<SESSION_MEMORY_5_TIER>` (mục 4). Tách `Fact` (đã xác nhận), `Inference` (suy luận + `confidence`), `Unknown` (chưa rõ). 0 suy luận lại điều đã rõ; cập nhật suy luận cũ khi có bằng chứng mới. Tin nhắn 1–3 chữ chỉ chỉnh độ dài phản hồi, 0 suy diễn lạnh nhạt. 1 lần chê đắt 0 kết luận luôn nhạy cảm giá. `"K"`, `"Ok"`, `"Ừ"` khi chưa chốt lịch là `CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT`, 0 mặc định `CUSTOMER_EXIT`.
            2. `Next Action Guidance` (Chọn ĐÚNG 1 hành động chính theo `recommended_action` & `contextual_action_guidance`, nguyên tắc `FEEL FIRST. SELL SECOND.`): `GREETING_AND_DISCOVERY | NEED_DISCOVERY | SERVICE_RECOMMENDATION | SPECIFIC_PRICE_ANSWER | PRICE_OBJECTION_HANDLING | VALUE_EXPLANATION | ALTERNATIVE_OFFER | RESPECTFUL_DEESCALATION | CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT | CUSTOMER_DEFERRAL | CUSTOMER_EXIT | FEEDBACK_RECOVERY | BOOKING_DISCUSSION | SELF_IDENTIFY_GENDER`.
            3. 4 loại `Price Intent` (từ `<VERIFIED_BUSINESS_CATALOG>`):
              - (A) `GENERAL_PRICE_INQUIRY` ("Giá", "Rổ giá sao"): Bubble 1 chào theo `brand_welcome_line`; Bubble 2 nêu gọn giá khởi điểm theo `core_service_group_starting_prices` + 1 câu hỏi khám phá nhu cầu (0 đọc danh sách dài).
              - (B) `SPECIFIC_PRICE_INQUIRY` (hỏi giá 1 dịch vụ/sau khi nêu triệu chứng): Báo trực tiếp giá & thời lượng gói phù hợp, 0 né bằng câu hỏi khám phá.
              - (C) `BUDGET_OR_LOWEST_PRICE_INQUIRY` (hỏi gói dưới ngưỡng/rẻ nhất): `ASK_PRICE` + `BUDGET_FIT`, `cost_concern_expressed: false`, `strategy: "Direct Answer"`. Nói trung thực mức đó có gì, CHỈ giới thiệu 1 gói sát nhu cầu nhất gần ngân sách (0 liệt kê gói đắt hơn, 0 ép mua). Hạng mục chưa có gói lẻ nhưng gói tổng hợp cùng tầm giá trong `<VERIFIED_BUSINESS_CATALOG>` đã gồm sẵn thì giới thiệu ngay gói đó (0 khất hỏi cơ sở).
              - (D) `EXPLICIT_FULL_PRICE_LIST_REQUEST` ("cho tôi bảng giá"): Trình bày bảng giá các gói chính.
            4. Phản đối giá & So sánh bên ngoài:
              - Phản đối giá lần đầu (`ALTERNATIVE_OFFER` / `PRICE_OBJECTION_HANDLING`): đồng cảm (`barrier_type: "VALUE_PERCEPTION_GAP"`, 0 mặc định thiếu tiền), làm rõ giá trị cốt lõi gắn vùng mỏi và gợi ý gói tiết kiệm phù hợp trong cùng `service_group` (0 upsell, 0 chào combo, `next_action: "SOFT_FEELING_CHECK"`, CẤM `GENTLE_SCHEDULE_CHECK`).
              - Phản đối giá lặp lại (`RESPECTFUL_DEESCALATION`): hạ nhiệt tôn trọng ngân sách, 0 ép chốt, 0 chào combo, 0 lặp tiện ích phụ, kết mở 0 câu hỏi (`?` = 0).
              - So sánh giá bên ngoài (`VALUE_EXPLANATION` / `COMPETITOR_COMPARISON`): (1) công nhận giá bên ngoài hợp lý cho nhu cầu cơ bản nhanh gọn (CẤM hạ thấp dịch vụ bên ngoài); (2) giải thích khác biệt nội tại theo thời lượng & quy trình thật; (3) gắn vào triệu chứng khách đã chia sẻ; (4) gợi ý nhẹ gói cơ bản dễ tiếp cận nhất.
            5. `confirmed_facts_delta` (`self_reported_symptom|need_scope|available_time|price_reaction|constraint`, status `CURRENT|RESOLVED|PAST|NEGATED|SUPERSEDED`): tách nguyên tử, ghi ĐỦ triệu chứng/vùng khách nêu (*"ví đang mỏng"*, *"đau ví"* là `BUDGET_FIT`). Từ khóa khách trích từ lời bot 0 phải nhu cầu của khách.
            6. `interaction_permission`, Tạm dừng & Đính chính đại từ (`SELF_IDENTIFY_GENDER`): `CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT` ("K", "Ok", "Ừ") -> 1 bong bóng ngắn giữ thiện cảm mở, `NO_CTA`, 0 dấu `?`. Hẹn xem sau (`CUSTOMER_DEFERRAL`: "xem đã", "để nghĩ đã") hoặc từ chối (`CUSTOMER_EXIT` / `EXPLICIT_REFUSAL`) -> `PAUSED_BY_CUSTOMER`, `Respectful Pause`, `NO_CTA`, 1 bong bóng, 0 dấu `?`. Khách tự xưng/chỉnh đại từ ("t là nam", "gọi là anh", "không phải chị đâu") -> `SELF_IDENTIFY_GENDER`, xin lỗi 1 câu vì sơ suất xưng hô, đổi ngay sang `{{CUSTOMER_PRONOUN}}` (`pronoun_confidence >= 0.95`), khẳng định dịch vụ phù hợp (CẤM chụp mũ *"các anh thường..."*), `NO_CTA`, `activated_skill_codes: []`, 1 bong bóng, 0 dấu `?`. `interested_package_id` tách khỏi `proposed_booking_patch.selected_product_id` (CHỈ điền khi `READY_TO_BOOK`). CẤM xuất SKU/enum vào `bubbles`.
            
            II. CÂU HỎI KHÁM PHÁ ≠ LỜI MỜI BÁN HÀNG & PHỤC HỒI GIAO TIẾP (`FEEDBACK_RECOVERY`):
            - `DISCOVERY_QUESTION` (`next_action: "ASK_ONE_DISCOVERY_QUESTION"`): câu hỏi mở lắng nghe cảm giác/vùng cần chăm sóc — 0 tên gói, 0 giá, 0 mời trải nghiệm, 0 hỏi giờ/cơ sở, 0 gộp 2 vế. `SALES_CTA` (= `cta_level` khác `NO_CTA`): chỉ dùng khi `buying_signal >= MODERATE` hoặc có tín hiệu đặt lịch.
            - `PROCESS_FEEDBACK` (phàn nàn cách tư vấn): `emotion: "FRUSTRATED"`, `FRICTION_RECOVERY`, `priority: "SERVICE_RECOVERY"`, `strategy: "Active Listening"`, `cta_level: "NO_CTA"` (0 giá/gói/đặt lịch), `adaptation: "PIVOT"`, `activated_skill_codes: []`, 1 bong bóng. Nhận lỗi trực diện (CẤM *"{{SELF_PRONOUN}} xin lỗi nếu..."* hay *"Thực ra {{SELF_PRONOUN}} chỉ..."*):
              (a) Trách chưa lắng nghe đã xả thông tin -> `next_action: "ASK_ONE_DISCOVERY_QUESTION"`: xin lỗi trực diện, kết bằng 1 câu hỏi mở về vùng khó chịu (CẤM *"{{SELF_PRONOUN}} sẽ không gợi ý nữa"*).
              (b) Trách hỏi nhiều, nói dài, máy móc/văn mẫu, thiếu cảm xúc hoặc phê phán tự khai AI -> `next_action: "NO_ADDITIONAL_ACTION"`, `interaction_permission: "CONCISE_PREFERRED"`: xin lỗi trực diện, xin điều chỉnh ngắn gọn hơn, 0 câu hỏi (CẤM *"xử lý quy tắc và dữ liệu"*, *"lập trình"*, *"trợ lý AI"*).
            - Danh tính AI: CHỈ khi khách hỏi trực tiếp là bot/AI hay người thật hoặc hỏi training -> thừa nhận trung thực {{SELF_PRONOUN}} là trợ lý AI trên trang "{{BRAND_NAME}}", `NO_CTA`, 0 câu hỏi. Khi phàn nàn/trêu đùa: CẤM tự khai *"{{SELF_PRONOUN}} là trợ lý AI"*.
            
            III. ĐỊNH DẠNG BONG BÓNG THEO ĐƠN VỊ Ý (`IDEA-BASED BUBBLES`) & BẤT BIẾN CÂU HỎI:
            - Lập `response_plan` TRƯỚC `bubbles`. Chia 1–3 bong bóng (thường 1–2), mỗi bong bóng 1 đơn vị ý gọn (~20–40 từ, 0 dồn đồng cảm + báo giá + tiện ích + câu hỏi trong Bubble 1; CẤM `\\n\\n`, CẤM câu dẫn kết bằng `:`).
            - BẤT BIẾN CÂU HỎI: toàn phản hồi TỐI ĐA 1 dấu `?`, đặt ở CUỐI bong bóng CUỐI CÙNG; bong bóng trước 0 dấu `?`. Đặt câu hỏi trực tiếp (CẤM *"và hỏi {{CUSTOMER_PRONOUN}} có muốn..."*).
            - Nhịp khám phá -> đề xuất:
              + Lượt chỉ nhắn "Tư vấn" hoặc chào chung (`GREETING_AND_DISCOVERY`): CHỈ chào ấm áp + 1 câu hỏi mở về nhu cầu/vùng mỏi (0 báo giá/gói).
              + Lượt mới chia sẻ triệu chứng lần đầu chưa hỏi gói/giá (`NEED_DISCOVERY`): Bubble 1 đồng cảm, giới thiệu định hướng chăm sóc vùng mỏi; Bubble 2 hỏi {{CUSTOMER_PRONOUN}} ưu tiên vùng nào hay chọn buổi vừa đủ/chuyên sâu (0 đưa ngay tên/giá 2 gói kèm không gian/ăn nhẹ; CẤM *"điều trị"*, CẤM cam kết cải thiện mất ngủ/nhức đầu).
              + Lượt đã xác nhận phạm vi ("Cả 2" -> `SERVICE_RECOMMENDATION`): Bubble 1 đề xuất gói phù hợp kèm giá, thời lượng và hạng mục cốt lõi; Bubble 2 hỏi hướng tới trải nghiệm/thời lượng gói (0 hỏi lại thời điểm/tần suất triệu chứng). CẤM lặp tiện ích phụ trong `already_mentioned_details_do_not_repeat`.
            
            IV. SỰ THẬT, GIỮ CHÂN KHÁCH HÀNG & CHĂM SÓC TRIỆU CHỨNG THEO NGỮ CẢNH:
            1. `Strict Grounding` & `4-Tier Claim Taxonomy` (`SERVICE_FACT | CUSTOMER_FEEDBACK | EXPECTED_EXPERIENCE | MEDICAL_CLAIM`): giá, thời lượng, phạm vi, không gian, tiện ích, chính sách khớp 100% `<VERIFIED_PAGE_POLICY>`, `<VERIFIED_BUSINESS_CATALOG>` & `<DOCUMENT_CONTEXT>`. Chưa có trong nguồn (như gửi xe) -> báo chưa có thông tin chính thức và đề nghị kiểm tra với cơ sở; CẤM tự đoán.
            2. PHẦN VIII — HEALTH BOUNDARY & HUMAN RESPONSIBILITY (CẤM bệnh lý hóa tâm sự đời thường): {{SELF_PRONOUN}} TUYỆT ĐỐI KHÔNG chẩn đoán bệnh, KHÔNG phân loại mức độ nghiêm trọng của triệu chứng, KHÔNG tự kết luận {{CUSTOMER_PRONOUN}} có đủ điều kiện sử dụng dịch vụ hay không.
              - (A) `NEED_DISCOVERY` (khách chỉ chia sẻ mệt mỏi/đau đầu/mất ngủ/tê mỏi cá nhân): lắng nghe đồng cảm tự nhiên, 0 giảng giải y khoa, 0 lời khuyên tư thế/y khoa, 0 tự động handoff, 0 vội ép bán/xả gói khi khách chưa hỏi. CẤM hứa chữa khỏi hay cam kết "không đau"/1 lần hết.
              - (B) `SUITABILITY_INQUIRY_HANDOFF` (khách trực tiếp hỏi dịch vụ có phù hợp với tình trạng sức khỏe của mình không, vd *"Tình trạng của tôi như vậy có làm massage được không?"*): ghi nhận nguyên văn điều khách chia sẻ và báo chuyển {{CARE_ACTOR}}/chuyên viên của cơ sở tư vấn trực tiếp theo quy trình (`handoff_request.requested = true`). CẤM tự khẳng định *"làm được bình thường"*, *"hoàn toàn làm được"*, *"rất an toàn"*; CẤM tự hứa cách thao tác/tư thế nằm/tránh vùng nào; CẤM đẩy khách đi nơi khác.
              - (C) Cấp cứu nguy kịch (tức ngực khó thở, yếu liệt/tê nửa người, méo miệng, ngất xỉu, đau đầu/chóng mặt dữ dội đột ngột): khuyên gọi 115 hoặc đến cơ sở y tế gần nhất ngay (0 chào bán/hẹn dịch vụ; nếu cần nhân viên thì báo chuyển hỗ trợ).
            2b. Pain-Matched Benefit Filtering, Buying Signal Lock & Direct Pivot on Correction:
              - `NEED_MATCHED_RECOMMENDATION`: chỉ nêu thành phần gói liên quan trực tiếp đến nhu cầu hiện tại (CẤM tự đọc hết thành phần phụ như *"phục hồi tóc"*, ăn nhẹ khi khách đang đau đầu/mỏi gối/tê tay/khó ngủ). Chỉ liệt kê đầy đủ 100% thành phần khi khách hỏi trực tiếp gói gồm những gì (`FULL_DISCLOSURE`, vd *"Gói 339k gồm những gì? Có phục hồi tóc à?"*).
              - CẤM tự tạo thông tin ngoài `<VERIFIED_BUSINESS_CATALOG>`: 0 suy diễn thời lượng từng bước nhỏ (vd nói phần chênh lệch 20 phút là thêm massage cổ vai gáy), 0 gọi thành phần chính là *"tặng kèm"* khi Catalog không xác nhận.
              - `TRIAL_COMMITMENT_LOCK` (*"Tôi muốn vừa đủ để thử"*): khóa & xác nhận đúng gói cơ bản/vừa đủ vừa giới thiệu (xem `commitment_level`, `last_offered_package_ids`), CẤM upsell sang gói đắt hơn.
              - `CUSTOMER_CORRECTION_PIVOT` (*"Ủa sao lại phục hồi tóc..."*, *"Tôi không muốn chăm sóc tóc, chỉ muốn massage"*): thừa nhận ngay 1 câu và chuyển thẳng sang gói thuần massage phù hợp trong Catalog (vd `NS01` 399k 60p / `NS02` 350k 60p), tôn trọng `negative_preferences`; 0 bảo vệ gói cũ, CẤM hỏi lại câu thừa *"{{CUSTOMER_PRONOUN}} có muốn tìm lựa chọn tập trung massage không?"*.
            3. `ZERO_SCOPE_DRIFT` & Riêng tư (`PRIVACY_CONCERN`): giữ đề xuất trong đúng `service_group`. Khi ngại riêng tư/tiếng ồn -> trấn an sự tĩnh lặng và điều kiện riêng tư thực tế của gói + 1 câu hỏi xem đã yên tâm chưa.
            4. Vận hành, Bảo mật Nội bộ & Cô lập Đa Tenant: hỏi địa chỉ 1 cơ sở -> chỉ báo cơ sở đó. Có cơ sở + giờ -> kiểm tra giờ nhận khách cuối, điền `proposed_booking_patch`, báo giữ lịch tạm chờ kiểm tra lịch trống (`AWAITING_AVAILABILITY_CHECK`; CẤM báo `CONFIRMED` và CẤM in mã enum vào `bubbles`). Chuyển khoản -> `customer_statements` + `commitment_delta(CREATE, CASHIER)` + `requested=true`.
            
            V. ĐẦU RA BẮT BUỘC (1 JSON HỢP LỆ, KHÔNG MARKDOWN; `brain_reflection` THU GỌN TRƯỚC `bubbles`, TRƯỜNG TEXT <= 6 TỪ, CHỈ GHI DELTA MỚI):
            {"brain_reflection":{"conversational_context":{"current_topic":"<=5 từ","unresolved_requests":[{"request":"<=6 từ","status":"OPEN|ANSWERED|CANCELLED|SUPERSEDED"}]},"customer_understanding":{"turn_intent":"MÃ_Ý_ĐỊNH_CHÍNH","secondary_intents":[],"conversation_state":"ACTIVE_CONSULTATION|OBJECTION_NEGOTIATION|BOOKING_READY|FRICTION_RECOVERY|META_PLAYFUL|PAUSED_BY_CUSTOMER","conversation_goal":"MỤC_TIÊU_PHIÊN","need_revision":false,"interaction_permission":"OPEN|CONCISE_PREFERRED|BOUNDED_INFO_ONLY|PAUSED","buying_signal":"NONE|WEAK|MODERATE|STRONG|ACTION","confirmed_facts_delta":[{"key":"self_reported_symptom|need_scope|available_time|price_reaction|constraint","value":"<=6 từ nguyên tử","op":"ADD|REVISE","status":"CURRENT|RESOLVED|PAST|NEGATED|SUPERSEDED"}],"interested_package_id":null,"emotion":"UNKNOWN|NEUTRAL|CALM|HESITANT|SKEPTICAL|FRUSTRATED|IMPATIENT|TIRED|DECISIVE|POLITE_DECLINE","inferred_customer_pronoun":null,"pronoun_confidence":0.0,"cost_concern_expressed":false,"verified_symptom_duration":null,"booking_readiness":"EXPLORING|CONSIDERING|READY_TO_BOOK","communication_style_preference":null},"priority":"SAFETY|SERVICE_RECOVERY|TRUST_ASSURANCE|CLARIFICATION|VALUE_ALIGNMENT|BUDGET_FIT|PRICE_INFORMATION|BOOKING_COMPLETION|POLITE_CLOSURE","working_hypothesis":{"hypothesis":"<=6 từ","barrier_type":"NONE|VALUE_PERCEPTION_GAP|UNCLEAR_VALUE|COMPETITOR_COMPARISON|BUDGET_LIMIT|COMMUNICATION_FRICTION","confidence":0.8,"lifecycle_op":"MAINTAIN|REINFORCE|ADJUST|DOWNGRADE|REJECT"},"response_plan":{"strategy":"Direct Answer|Warm Acknowledgment|Active Listening|Clarify Gently|Explain Value|Handle Objection|Reassure Honestly|Soft Recommendation|Respectful Pause|Transaction Handoff","tone":"LEVEL_1_NATURAL|LEVEL_2_REFLECTIVE|LEVEL_3_SENSORY","depth":"CONCISE|STANDARD|DETAILED_ON_DEMAND","cta_level":"NO_CTA|SOFT_CTA|DIRECT_CTA|TRANSACTION_HANDOFF","next_action":"NO_ADDITIONAL_ACTION|ASK_ONE_DISCOVERY_QUESTION|ASK_ONE_CLARIFYING_QUESTION|SOFT_FEELING_CHECK|GENTLE_SCHEDULE_CHECK","adaptation":"CONTINUATION|REFINEMENT|PIVOT|PRIORITY_SHIFT|STOP","previous_outcome":"ACCEPTED|REJECTED|PARTIALLY_ACCEPTED|UNCLEAR|NOT_APPLICABLE"},"activated_skill_codes":[],"handoff_request":{"requested":false,"reason":null},"proposed_booking_patch":{"selected_product_id":null,"proposed_date":null,"proposed_time":null}},"bubbles":["Bong bóng 1"]}
            (Chỉ thêm `"customer_statements":["..."]` và `"commitment_delta":[{"operation":"CREATE|UPDATE_CONTENT|REMIND|CANCEL_BY_CUSTOMER","content":"...","owner":"BOT|MANAGER|CASHIER|STAFF","verified_timeframe":null}]` khi phát sinh báo chuyển khoản, tranh chấp lời hứa hoặc cam kết treo.)
            """;

    static String getInCodeAciPromptTemplate() {
        return ACI_COGNITIVE_SYSTEM_PROMPT_TEMPLATE;
    }

    static String extractPromptVersionFromExternalContent(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher vm = PROMPT_VERSION_COMMENT_PATTERN.matcher(raw);
        return vm.find() ? vm.group(1).strip() : null;
    }

    static String stripPromptVersionComment(String raw) {
        if (raw == null) {
            return "";
        }
        return PROMPT_VERSION_COMMENT_PATTERN.matcher(raw).replaceFirst("").strip();
    }

    public static void setExternalAciPromptPathForTest(Path customPath) {
        synchronized (PROMPT_RELOAD_LOCK) {
            externalAciPromptPathOverride = customPath;
            cachedExternalPromptPath = null;
            cachedExternalPromptLastModifiedMs = -1L;
            cachedValidatedExternalPromptTemplate = null;
            activeAciPromptVersion = IN_CODE_ACI_V2_PROMPT_VERSION;
            activeAciPromptSource = "IN_CODE_DEFAULT";
        }
    }

    public static String getActiveAciPromptVersion() {
        return activeAciPromptVersion;
    }

    public static String getActiveAciPromptSource() {
        return activeAciPromptSource;
    }

    private static Path resolveCandidateExternalAciPromptPath() {
        Path override = externalAciPromptPathOverride;
        if (override != null) {
            return override;
        }
        Path containerPath = Path.of(DEFAULT_EXTERNAL_ACI_V2_PROMPT_PATH);
        if (Files.isRegularFile(containerPath)) {
            return containerPath;
        }
        Path localDirect = Path.of("config/aci_v2_system_prompt.md");
        if (Files.isRegularFile(localDirect)) {
            return localDirect;
        }
        Path localParent = Path.of("../config/aci_v2_system_prompt.md");
        if (Files.isRegularFile(localParent)) {
            return localParent;
        }
        return containerPath;
    }

    static boolean isValidAciV2PromptTemplate(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String stripped = raw.strip();
        return stripped.length() >= 300
                && stripped.contains("brain_reflection")
                && stripped.contains("bubbles");
    }

    static String loadValidatedAciSystemPromptTemplate() {
        Path candidate = resolveCandidateExternalAciPromptPath();
        try {
            if (candidate != null && Files.isRegularFile(candidate)) {
                long lastModMs = Files.getLastModifiedTime(candidate).toMillis();
                String fastCached = cachedValidatedExternalPromptTemplate;
                if (fastCached != null
                        && Objects.equals(cachedExternalPromptPath, candidate)
                        && cachedExternalPromptLastModifiedMs == lastModMs) {
                    return fastCached;
                }
                synchronized (PROMPT_RELOAD_LOCK) {
                    if (cachedValidatedExternalPromptTemplate != null
                            && Objects.equals(cachedExternalPromptPath, candidate)
                            && cachedExternalPromptLastModifiedMs == lastModMs) {
                        return cachedValidatedExternalPromptTemplate;
                    }
                    String raw = Files.readString(candidate, StandardCharsets.UTF_8);
                    if (!isValidAciV2PromptTemplate(raw)) {
                        log.warn("[ACI-V2-PROMPT-HOT-RELOAD] Invalid/empty external prompt at {} -> falling back to in-code template ({})",
                                candidate, IN_CODE_ACI_V2_PROMPT_VERSION);
                        cachedExternalPromptPath = null;
                        cachedExternalPromptLastModifiedMs = -1L;
                        cachedValidatedExternalPromptTemplate = null;
                        activeAciPromptVersion = IN_CODE_ACI_V2_PROMPT_VERSION;
                        activeAciPromptSource = "IN_CODE_FALLBACK";
                        return ACI_COGNITIVE_SYSTEM_PROMPT_TEMPLATE;
                    }
                    Matcher vm = PROMPT_VERSION_COMMENT_PATTERN.matcher(raw);
                    String extractedVersion = vm.find() ? vm.group(1).strip() : "external-unversioned";
                    String cleanedTemplate = PROMPT_VERSION_COMMENT_PATTERN.matcher(raw).replaceFirst("").strip();
                    cachedExternalPromptPath = candidate;
                    cachedExternalPromptLastModifiedMs = lastModMs;
                    cachedValidatedExternalPromptTemplate = cleanedTemplate;
                    activeAciPromptVersion = extractedVersion;
                    activeAciPromptSource = "EXTERNAL_FILE:" + candidate;
                    log.info("[ACI-V2-PROMPT-HOT-RELOAD] Loaded external prompt version={} lastModifiedMs={} path={}",
                            extractedVersion, lastModMs, candidate);
                    return cleanedTemplate;
                }
            }
        } catch (Exception e) {
            log.warn("[ACI-V2-PROMPT-HOT-RELOAD] Error reading external prompt {}: {} -> falling back to in-code template",
                    candidate, e.getMessage());
        }
        activeAciPromptVersion = IN_CODE_ACI_V2_PROMPT_VERSION;
        activeAciPromptSource = "IN_CODE_FALLBACK";
        return ACI_COGNITIVE_SYSTEM_PROMPT_TEMPLATE;
    }

    private static String renderAciSystemPromptTemplate(
            String template,
            String botRole,
            String brandName,
            String industry,
            String selfPronoun,
            String custPronoun,
            String careActor,
            String toneStyle,
            String openerSample
    ) {
        String safeTpl = (template != null && !template.isBlank()) ? template : ACI_COGNITIVE_SYSTEM_PROMPT_TEMPLATE;
        if (safeTpl.contains("{{")) {
            return safeTpl
                    .replace("{{BOT_ROLE}}", botRole)
                    .replace("{{BRAND_NAME}}", brandName)
                    .replace("{{INDUSTRY}}", industry)
                    .replace("{{SELF_PRONOUN}}", selfPronoun)
                    .replace("{{CUSTOMER_PRONOUN}}", custPronoun)
                    .replace("{{CARE_ACTOR}}", careActor)
                    .replace("{{TONE_STYLE}}", toneStyle)
                    .replace("{{OPENER_SAMPLE}}", openerSample);
        }
        try {
            return String.format(
                    safeTpl,
                    botRole,
                    brandName,
                    industry,
                    selfPronoun,
                    custPronoun,
                    careActor,
                    toneStyle,
                    custPronoun,
                    openerSample
            );
        } catch (Exception e) {
            log.warn("[ACI-V2-PROMPT-HOT-RELOAD] Format exception on custom template ({}), rendering in-code default", e.getMessage());
            return renderAciSystemPromptTemplate(
                    ACI_COGNITIVE_SYSTEM_PROMPT_TEMPLATE,
                    botRole,
                    brandName,
                    industry,
                    selfPronoun,
                    custPronoun,
                    careActor,
                    toneStyle,
                    openerSample
            );
        }
    }

    public static String resolveCurrentSequentialTurnId(List<FanpageBotRequest.ConversationTurn> history) {
        int count = 0;
        if (history != null) {
            for (FanpageBotRequest.ConversationTurn t : history) {
                if (t != null && t.content() != null && !t.content().isBlank()) {
                    count++;
                }
            }
        }
        return "T" + (count + 1);
    }

    /**
     * Xây dựng Prompt hợp nhất <b>Single-Pass Cognitive Contract</b> cho chế độ {@code LLM_COGNITIVE_V2} (Mục 2, 3, 4):
     * <ul>
     *   <li>Loại bỏ trùng lặp 3 khối cũ ({@code <STRUCTURED_TURN_CONTRACT>}, {@code <AGENT_BRAIN_INSIGHT>}, {@code <STRUCTURED_CONVERSATION_STATE>}).</li>
     *   <li>Bỏ làm mù nhận thức Catalog (Epistemic Unblinding): hiển thị đầy đủ các gói hợp lệ với vai trò {@code PRIMARY} và {@code ECONOMICAL_OPTION}.</li>
     *   <li>Trình bày rạch ròi 5 tầng bộ nhớ ({@code <SESSION_MEMORY_5_TIER>}), ứng viên regex {@code verified=false},
     *       vòng đời giả thuyết, kết quả chiến thuật lượt trước và danh sách {@code CommitmentRecord}.</li>
     *   <li>Giữ nguyên văn 100% {@code Original Message} + từng bong bóng burst gốc và đánh mã lượt {@code [T1]..[T_CURRENT]}.</li>
     * </ul>
     */
    public static String buildAciSinglePassPrompt(
            PromptBuilder.PagePersona pagePersona,
            String customerHonorific,
            AdaptiveConversationIntelligence.ThreeStreamTurnInput threeStreamInput,
            List<PackageCatalogService.PackageSummary> primaryPackages,
            List<PackageCatalogService.PackageSummary> economicalPackages,
            QuotedReplyResolution quotedReplyResolution,
            List<AdaptiveConversationIntelligence.CustomerStatementRecord> tier1CustomerStatements,
            AdaptiveConversationIntelligence.WorkingHypothesisRecord tier2WorkingHypothesis,
            List<AdaptiveConversationIntelligence.VerifiedBusinessFact> tier4VerifiedBusinessFacts,
            ConversationState.StructuredBookingState authoritativeBookingState,
            AdaptiveConversationIntelligence.OperationalStateSnapshot tier5OperationalState,
            AdaptiveConversationIntelligence.PreviousStrategyRecord previousStrategyRecord,
            List<AdaptiveConversationIntelligence.CommitmentRecord> sessionCommitments,
            String verifiedPageSlaPolicy,
            boolean hasAuthoritativePaymentVerification,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<FanpageBotRequest.ConversationTurn> history
    ) {
        String defaultCust = (pagePersona != null && pagePersona.resolvedConfig() != null)
                ? pagePersona.resolvedConfig().resolvedDefaultCustomerPronoun()
                : ((customerHonorific != null && !customerHonorific.isBlank()) ? customerHonorific.strip() : "mình");
        String effectiveCust = (customerHonorific != null && !customerHonorific.isBlank())
                ? customerHonorific.strip()
                : defaultCust;
        String inferredCust = !effectiveCust.equalsIgnoreCase(defaultCust) ? effectiveCust : null;
        return buildAciSinglePassPrompt(
                pagePersona,
                defaultCust,
                inferredCust,
                effectiveCust,
                threeStreamInput,
                primaryPackages,
                economicalPackages,
                quotedReplyResolution,
                tier1CustomerStatements,
                tier2WorkingHypothesis,
                tier4VerifiedBusinessFacts,
                authoritativeBookingState,
                tier5OperationalState,
                previousStrategyRecord,
                sessionCommitments,
                verifiedPageSlaPolicy,
                hasAuthoritativePaymentVerification,
                fusedChunks,
                history,
                List.of(),
                List.of(),
                null
        );
    }

    public static String buildAciSinglePassPrompt(
            PromptBuilder.PagePersona pagePersona,
            String defaultCustomerPronoun,
            String inferredCustomerPronoun,
            String effectiveCustomerPronoun,
            AdaptiveConversationIntelligence.ThreeStreamTurnInput threeStreamInput,
            List<PackageCatalogService.PackageSummary> primaryPackages,
            List<PackageCatalogService.PackageSummary> economicalPackages,
            QuotedReplyResolution quotedReplyResolution,
            List<AdaptiveConversationIntelligence.CustomerStatementRecord> tier1CustomerStatements,
            AdaptiveConversationIntelligence.WorkingHypothesisRecord tier2WorkingHypothesis,
            List<AdaptiveConversationIntelligence.VerifiedBusinessFact> tier4VerifiedBusinessFacts,
            ConversationState.StructuredBookingState authoritativeBookingState,
            AdaptiveConversationIntelligence.OperationalStateSnapshot tier5OperationalState,
            AdaptiveConversationIntelligence.PreviousStrategyRecord previousStrategyRecord,
            List<AdaptiveConversationIntelligence.CommitmentRecord> sessionCommitments,
            String verifiedPageSlaPolicy,
            boolean hasAuthoritativePaymentVerification,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<FanpageBotRequest.ConversationTurn> history,
            List<AgentSkillDto> enabledPageSkills,
            List<LearnedRuleDto> activeLearnedRules
    ) {
        return buildAciSinglePassPrompt(
                pagePersona,
                defaultCustomerPronoun,
                inferredCustomerPronoun,
                effectiveCustomerPronoun,
                threeStreamInput,
                primaryPackages,
                economicalPackages,
                quotedReplyResolution,
                tier1CustomerStatements,
                tier2WorkingHypothesis,
                tier4VerifiedBusinessFacts,
                authoritativeBookingState,
                tier5OperationalState,
                previousStrategyRecord,
                sessionCommitments,
                verifiedPageSlaPolicy,
                hasAuthoritativePaymentVerification,
                fusedChunks,
                history,
                enabledPageSkills,
                activeLearnedRules,
                null
        );
    }

    public static String buildAciSinglePassPrompt(
            PromptBuilder.PagePersona pagePersona,
            String defaultCustomerPronoun,
            String inferredCustomerPronoun,
            String effectiveCustomerPronoun,
            AdaptiveConversationIntelligence.ThreeStreamTurnInput threeStreamInput,
            List<PackageCatalogService.PackageSummary> primaryPackages,
            List<PackageCatalogService.PackageSummary> economicalPackages,
            QuotedReplyResolution quotedReplyResolution,
            List<AdaptiveConversationIntelligence.CustomerStatementRecord> tier1CustomerStatements,
            AdaptiveConversationIntelligence.WorkingHypothesisRecord tier2WorkingHypothesis,
            List<AdaptiveConversationIntelligence.VerifiedBusinessFact> tier4VerifiedBusinessFacts,
            ConversationState.StructuredBookingState authoritativeBookingState,
            AdaptiveConversationIntelligence.OperationalStateSnapshot tier5OperationalState,
            AdaptiveConversationIntelligence.PreviousStrategyRecord previousStrategyRecord,
            List<AdaptiveConversationIntelligence.CommitmentRecord> sessionCommitments,
            String verifiedPageSlaPolicy,
            boolean hasAuthoritativePaymentVerification,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<FanpageBotRequest.ConversationTurn> history,
            List<AgentSkillDto> enabledPageSkills,
            List<LearnedRuleDto> activeLearnedRules,
            AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot draft2Snapshot
    ) {
        return buildAciSinglePassPrompt(
                pagePersona,
                defaultCustomerPronoun,
                inferredCustomerPronoun,
                effectiveCustomerPronoun,
                threeStreamInput,
                primaryPackages,
                economicalPackages,
                quotedReplyResolution,
                tier1CustomerStatements,
                tier2WorkingHypothesis,
                tier4VerifiedBusinessFacts,
                authoritativeBookingState,
                tier5OperationalState,
                previousStrategyRecord,
                sessionCommitments,
                verifiedPageSlaPolicy,
                hasAuthoritativePaymentVerification,
                fusedChunks,
                history,
                enabledPageSkills,
                activeLearnedRules,
                draft2Snapshot,
                null
        );
    }

    public static String buildAciSinglePassPrompt(
            PromptBuilder.PagePersona pagePersona,
            String defaultCustomerPronoun,
            String inferredCustomerPronoun,
            String effectiveCustomerPronoun,
            AdaptiveConversationIntelligence.ThreeStreamTurnInput threeStreamInput,
            List<PackageCatalogService.PackageSummary> primaryPackages,
            List<PackageCatalogService.PackageSummary> economicalPackages,
            QuotedReplyResolution quotedReplyResolution,
            List<AdaptiveConversationIntelligence.CustomerStatementRecord> tier1CustomerStatements,
            AdaptiveConversationIntelligence.WorkingHypothesisRecord tier2WorkingHypothesis,
            List<AdaptiveConversationIntelligence.VerifiedBusinessFact> tier4VerifiedBusinessFacts,
            ConversationState.StructuredBookingState authoritativeBookingState,
            AdaptiveConversationIntelligence.OperationalStateSnapshot tier5OperationalState,
            AdaptiveConversationIntelligence.PreviousStrategyRecord previousStrategyRecord,
            List<AdaptiveConversationIntelligence.CommitmentRecord> sessionCommitments,
            String verifiedPageSlaPolicy,
            boolean hasAuthoritativePaymentVerification,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<FanpageBotRequest.ConversationTurn> history,
            List<AgentSkillDto> enabledPageSkills,
            List<LearnedRuleDto> activeLearnedRules,
            AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot draft2Snapshot,
            AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan prebuiltTurnPlan
    ) {
        PagePersonaConfig resolvedCfg = pagePersona != null ? pagePersona.resolvedConfig() : PagePersonaConfig.empty();
        String brandName = (pagePersona != null && pagePersona.name() != null && !pagePersona.name().isBlank())
                ? pagePersona.name().strip()
                : "Fanpage";
        String industry = (pagePersona != null && pagePersona.industry() != null && !pagePersona.industry().isBlank())
                ? pagePersona.industry().strip()
                : ((resolvedCfg.industryCode() != null && !resolvedCfg.industryCode().isBlank())
                    ? resolvedCfg.industryCode().strip()
                    : "Dịch vụ chăm sóc khách hàng");
        String selfPronoun = resolvedCfg.resolvedSelfPronoun();
        String defaultPronoun = (defaultCustomerPronoun != null && !defaultCustomerPronoun.isBlank())
                ? defaultCustomerPronoun.strip()
                : resolvedCfg.resolvedDefaultCustomerPronoun();
        String inferredPronoun = (inferredCustomerPronoun != null && !inferredCustomerPronoun.isBlank())
                ? inferredCustomerPronoun.strip()
                : null;
        String custPronoun = (effectiveCustomerPronoun != null && !effectiveCustomerPronoun.isBlank())
                ? effectiveCustomerPronoun.strip()
                : (inferredPronoun != null ? inferredPronoun : defaultPronoun);
        String botRole = (resolvedCfg.botName() != null && !resolvedCfg.botName().isBlank())
                ? resolvedCfg.botName().strip()
                : "Chuyên viên Tư vấn Hội thoại";
        String careActor = resolvedCfg.resolvedCareActor();
        String toneStyle = (resolvedCfg.toneStyle() != null && !resolvedCfg.toneStyle().isBlank())
                ? resolvedCfg.toneStyle().strip()
                : "Ấm áp, tôn trọng, tinh tế, chân thành";
        List<String> forbiddenWords = resolvedCfg.safeForbiddenWords();

        String activeTemplate = loadValidatedAciSystemPromptTemplate();
        String systemPrompt = renderAciSystemPromptTemplate(
                activeTemplate,
                botRole,
                brandName,
                industry,
                selfPronoun,
                custPronoun,
                careActor,
                toneStyle,
                "Dạ " + custPronoun + " / Dạ " + selfPronoun
        );

        QuotedReplyResolution safeQuote = quotedReplyResolution != null ? quotedReplyResolution : QuotedReplyResolution.empty();
        String sequentialTurnId = resolveCurrentSequentialTurnId(history);
        boolean isOpeningTurn = (history == null || history.isEmpty());
        String verbatimOriginal = threeStreamInput != null ? threeStreamInput.originalMessage() : "";

        StringBuilder sb = new StringBuilder(5120);
        sb.append("<CLEAN_AGENT_SYSTEM_PROMPT>\n")
          .append(systemPrompt.strip())
          .append("\n</CLEAN_AGENT_SYSTEM_PROMPT>\n")
          .append("<!-- SYSTEM_USER_SPLIT -->\n\n");

        sb.append("<SINGLE_PASS_COGNITIVE_CONTRACT>\n");

        // 1. VERIFIED PAGE POLICY (100% UI Page Config Efficacy + Turn-1 Hospitality Opening Bubbles)
        sb.append("  <VERIFIED_PAGE_POLICY>\n")
          .append("    brand_name: \"").append(brandName).append("\"\n")
          .append("    industry: \"").append(industry).append("\"\n")
          .append("    bot_role: \"").append(compactSingleLine(botRole, 100)).append("\"\n")
          .append("    care_actor: \"").append(careActor).append("\"\n")
          .append("    tone_style: \"").append(compactSingleLine(toneStyle, 180)).append("\"\n")
          .append("    pronoun_resolution: { default_customer_pronoun: \"").append(defaultPronoun)
          .append("\", inferred_customer_pronoun: ").append(inferredPronoun != null ? ("\"" + inferredPronoun + "\"") : "null")
          .append(", effective_customer_pronoun: \"").append(custPronoun).append("\" }\n")
          .append("    mandatory_pronouns: { self: \"").append(selfPronoun)
          .append("\", customer: \"").append(custPronoun).append("\" }\n");
        if (isOpeningTurn
                && resolvedCfg.openingBubbles() != null
                && !resolvedCfg.openingBubbles().isEmpty()) {
            List<String> rawResolvedOpening = resolvedCfg.resolvedOpeningBubbles(
                    brandName,
                    pagePersona != null ? pagePersona.description() : null,
                    custPronoun
            );
            List<String> resolvedOpening = new ArrayList<>(rawResolvedOpening.size());
            boolean neutralizeUnconfirmedPronounInOpening = (inferredPronoun == null || inferredPronoun.isBlank())
                    && ("mình".equalsIgnoreCase(custPronoun) || "bạn".equalsIgnoreCase(custPronoun));
            for (String ro : rawResolvedOpening) {
                if (ro == null) continue;
                String line = ro;
                if (neutralizeUnconfirmedPronounInOpening) {
                    line = line.replaceAll("(?iU)\\banh/chị\\b", custPronoun)
                               .replaceAll("(?iU)(?<![\\p{L}\\p{M}])chị(?!\\s+(?:gái|dâu|họ|ruột|ấy|đó|kia))(?![\\p{L}\\p{M}])", custPronoun);
                }
                resolvedOpening.add(line);
            }
            if (!resolvedOpening.isEmpty()) {
                if (!isDirectTurnOnePriceOrOperationalQuestion(verbatimOriginal)) {
                    boolean isBareGeneralConsult = isBareTurnOneGreetingOrConsultationRequest(verbatimOriginal);
                    sb.append("    configured_opening_bubbles (Dùng linh hoạt cho Hospitality khi khách chào hỏi / mở lời cần tư vấn chung ở lượt 1):\n");
                    int idxNum = 0;
                    for (int i = 0; i < resolvedOpening.size(); i++) {
                        // Khi khách chỉ nhắn "Tư vấn" / chào hỏi ngắn mà chưa nêu triệu chứng hay hỏi giá, bỏ đoạn quảng cáo giữa
                        // để tránh xả thông tin hoặc dính lỗi gõ "thả lỏo", chỉ giữ lời chào + câu hỏi mở khám phá nhu cầu.
                        if (isBareGeneralConsult && resolvedOpening.size() >= 3 && i > 0 && i < resolvedOpening.size() - 1) {
                            continue;
                        }
                        idxNum++;
                        String openLine = compactSingleLine(resolvedOpening.get(i), 180)
                                .replace("thả lỏo", "thả lỏng")
                                .replace("thức giấy", "thức giấc");
                        if (isBareGeneralConsult && i == resolvedOpening.size() - 1
                                && openLine.toLowerCase(Locale.ROOT).contains("quan tâm tới dịch vụ nào")) {
                            openLine = "Hiện " + custPronoun + " đang cảm thấy mỏi, căng thẳng ở vùng nào hay mong muốn thư giãn ra sao để "
                                    + selfPronoun + " tư vấn thật sát nhu cầu của " + custPronoun + " ạ?";
                        }
                        sb.append("      ").append(idxNum).append(". \"").append(openLine).append("\"\n");
                    }
                    if (isBareGeneralConsult) {
                        sb.append("    turn_1_general_consultation_policy: \"GENERAL_CONSULTATION_REQUEST: Khi khách chỉ nhắn 'Tư vấn' hoặc chào hỏi mở lời chung ở Lượt 1 mà chưa hỏi giá hay nêu triệu chứng cụ thể -> chào đón ấm áp, thân thiện và đặt ĐÚNG 1 câu hỏi mở xoay quanh NHU CẦU/CẢM GIÁC CỦA KHÁCH (hỏi khách đang mỏi hay cần thư giãn vùng nào; KHÔNG hỏi câu hướng sản phẩm 'quan tâm dịch vụ nào' và TUYỆT ĐỐI CẤM báo giá hay liệt kê gói dịch vụ)\"\n");
                    }
                } else {
                    sb.append("    brand_welcome_line (Chào đón thương hiệu ấm áp, thân thiện ở đầu lượt 1 trước khi trả lời thẳng giá/thông tin): \"")
                      .append(compactSingleLine(resolvedOpening.get(0), 160))
                      .append("\"\n");
                }
            }
        }
        if (!forbiddenWords.isEmpty()) {
            sb.append("    forbidden_words: [");
            for (int i = 0; i < forbiddenWords.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append('"').append(compactSingleLine(forbiddenWords.get(i), 60)).append('"');
            }
            sb.append("] (CẤM xuất hiện)\n");
        } else {
            sb.append("    forbidden_words: []\n");
        }
        sb.append("    human_consultation_and_retention_policy: { customer_retention_priority: \"HIGH\", external_medical_referral_policy: \"ACUTE_RED_FLAG_ONLY\", internal_consultation_actor: \"")
          .append(compactSingleLine(careActor, 60))
          .append("/chuyên viên/bác sĩ cơ sở\", handoff_tone: \"WARM_REASSURING_NON_DISMISSIVE\", preserve_booking_opportunity: true, allow_custom_service_adaptation_note: true, unverified_policy_handling: \"ADMIT_UNVERIFIED_AND_OFFER_INTERNAL_CHECK\" }\n");
        appendVerifiedOperationalPoliciesFromSourceChunks(sb, fusedChunks, verbatimOriginal, primaryPackages, economicalPackages);
        sb.append("    verified_sla_policy: ").append(verifiedPageSlaPolicy != null && !verifiedPageSlaPolicy.isBlank()
                ? ("\"" + verifiedPageSlaPolicy.strip() + "\"")
                : "null (CẤM tự hứa SLA phút/giờ cụ thể)")
          .append('\n')
          .append("    booking_authority_max_status: \"AWAITING_AVAILABILITY_CHECK\" (CẤM báo CONFIRMED/đã giữ chỗ)\n")
          .append("    payment_verified_by_cashier: ").append(hasAuthoritativePaymentVerification)
          .append(" (Khi false, CẤM báo đã nhận tiền)\n")
          .append("  </VERIFIED_PAGE_POLICY>\n\n");

        // 1.5. COMPACT PAGE PLAYBOOK DIRECTIVES (Top turn-relevant rules & skills, deduplicated & compact)
        appendCompactPagePlaybookDirectives(sb, enabledPageSkills, activeLearnedRules, custPronoun, verbatimOriginal, isOpeningTurn);

        // 2. VERIFIED BUSINESS CATALOG (EPISTEMIC UNBLINDING + CANONICAL BRANCH DEDUPLICATION + SERVICE GROUP LOCK)
        sb.append("  <VERIFIED_BUSINESS_CATALOG>\n")
          .append("    sku_visibility_policy: \"id/code trong [...] chỉ dùng nội bộ; CẤM xuất mã SKU vào bubbles\"\n")
          .append("    space_privacy_policy: \"Gói tiêu chuẩn (Phòng chung có rèm riêng từng giường, KHÔNG phòng VIP riêng): khi ngại chung/ồn, trấn an rõ (1) không gian tĩnh lặng, yên tĩnh và (2) mỗi giường đều có rèm che riêng kín đáo; chỉ chào gói phòng VIP riêng khi khách cần phòng riêng hoàn toàn\"\n")
          .append("    service_scope_continuity_policy: \"ZERO_SCOPE_DRIFT: Khi đã xác nhận service_group, mọi đề xuất và xử lý chê đắt BẮT BUỘC giữ trong đúng service_group đó; CẤM tự bẻ sang service_group khác\"\n");
        List<PackageCatalogService.PackageSummary> dedupPrimaries = deduplicateCatalogByCanonicalSignature(primaryPackages);
        List<PackageCatalogService.PackageSummary> dedupEconomicals = deduplicateCatalogByCanonicalSignature(economicalPackages);
        String coreGroupPricesSummary = buildCoreServiceGroupStartingPriceSummary(dedupPrimaries, dedupEconomicals);
        if (!coreGroupPricesSummary.isEmpty()) {
            sb.append("    core_service_group_starting_prices: \"")
              .append(coreGroupPricesSummary)
              .append("\"\n");
        }
        CatalogViewMode viewMode = resolveCatalogViewMode(verbatimOriginal);
        boolean allowHairDetail = viewMode != CatalogViewMode.NEED_MATCHED_RECOMMENDATION
                || (verbatimOriginal != null && verbatimOriginal.toLowerCase(Locale.ROOT)
                    .matches("(?isU).*\\b(tóc|gội|da\\s+đầu)\\b.*"));
        Set<String> renderedPkgIds = new LinkedHashSet<>();
        Set<String> renderedCanonicalSigs = new LinkedHashSet<>();
        int pkgIndex = 0;
        for (PackageCatalogService.PackageSummary p : dedupPrimaries) {
            if (p == null || p.isAddon() || !renderedPkgIds.add(p.productId())
                    || !renderedCanonicalSigs.add(buildCanonicalPackageSignature(p))) {
                continue;
            }
            pkgIndex++;
            appendAciUnblindedCatalogLine(sb, pkgIndex, "PRIMARY", p, viewMode, allowHairDetail);
        }
        boolean hideShortNicheEconOnTurn1GeneralPrice = isOpeningTurn
                && isDirectTurnOnePriceOrOperationalQuestion(verbatimOriginal)
                && (verbatimOriginal == null
                    || !verbatimOriginal.toLowerCase(Locale.ROOT).matches("(?isU).*\\b(tai|ráy\\s+tai|\\d{2,4}\\s*k|rẻ|tiết\\s+kiệm|thấp\\s+nhất)\\b.*"));
        for (PackageCatalogService.PackageSummary p : dedupEconomicals) {
            if (p == null || p.isAddon() || !renderedPkgIds.add(p.productId())
                    || !renderedCanonicalSigs.add(buildCanonicalPackageSignature(p))) {
                continue;
            }
            if (hideShortNicheEconOnTurn1GeneralPrice
                    && p.durationMinutes() != null && p.durationMinutes() > 0 && p.durationMinutes() <= 35) {
                continue;
            }
            pkgIndex++;
            appendAciUnblindedCatalogLine(sb, pkgIndex, "ECONOMICAL_OPTION", p, viewMode, allowHairDetail);
        }
        if (pkgIndex == 0) {
            sb.append("    (Chưa có gói dịch vụ cấu trúc — chỉ dựa trên <DOCUMENT_CONTEXT>, cấm tự bịa gói/giá)\n");
        }
        sb.append("  </VERIFIED_BUSINESS_CATALOG>\n\n");

        // 3. QUOTE REPLY CONTEXT (chỉ xuất khi có reply_to.mid thực tế)
        if (safeQuote.hasReplyToContext()) {
            sb.append("  <QUOTE_REPLY_CONTEXT>\n")
              .append("    reply_to_mid: \"").append(safeQuote.replyToMid()).append("\"\n")
              .append("    resolution_state: \"").append(safeQuote.resolutionState().name()).append("\"\n");
            if (safeQuote.quotedBubbleText() != null && !safeQuote.quotedBubbleText().isBlank()) {
                sb.append("    quoted_bubble_text: \"").append(safeQuote.quotedBubbleText().strip()).append("\"\n");
            }
            if (safeQuote.quotedPackage() != null) {
                sb.append("    resolved_quoted_product_id: \"").append(safeQuote.quotedPackage().productId())
                  .append("\" (").append(safeQuote.quotedPackage().canonicalLabel())
                  .append(" - ").append(safeQuote.quotedPackage().priceDisplay()).append(")\n");
            } else if (!safeQuote.candidatePackages().isEmpty()) {
                sb.append("    candidate_products_to_clarify: [");
                for (int i = 0; i < safeQuote.candidatePackages().size(); i++) {
                    if (i > 0) sb.append(", ");
                    PackageCatalogService.PackageSummary cp = safeQuote.candidatePackages().get(i);
                    sb.append(cp.productId()).append(": ").append(cp.canonicalLabel()).append(" (").append(cp.priceDisplay()).append(")");
                }
                sb.append("]\n");
            }
            sb.append("  </QUOTE_REPLY_CONTEXT>\n\n");
        }

        // 4. UNIFIED 3-LAYER SESSION MEMORY (CUSTOMER UNDERSTANDING MODEL V2.3 — ZERO DUPLICATION)
        sb.append("  <SESSION_MEMORY_5_TIER>\n");
        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot safeDraft2 =
                draft2Snapshot != null ? draft2Snapshot : AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial();
        AdaptiveConversationIntelligence.ConversationalContextLayer l1 = safeDraft2.conversationalContext();
        AdaptiveConversationIntelligence.ConsultativeProfileLayer l2 = safeDraft2.consultativeProfile();
        AdaptiveConversationIntelligence.BusinessCommitmentLayer l3 = safeDraft2.businessCommitment();
        AdaptiveConversationIntelligence.WorkingHypothesisRecord safeHyp =
                tier2WorkingHypothesis != null ? tier2WorkingHypothesis : AdaptiveConversationIntelligence.WorkingHypothesisRecord.initialEmpty();
        List<AdaptiveConversationIntelligence.UnverifiedParserCandidate> candidates =
                threeStreamInput != null ? threeStreamInput.unverifiedParserCandidates() : List.of();
        ConversationState.StructuredBookingState safeBooking =
                authoritativeBookingState != null ? authoritativeBookingState : ConversationState.StructuredBookingState.none();
        AdaptiveConversationIntelligence.OperationalStateSnapshot safeOp =
                tier5OperationalState != null ? tier5OperationalState : AdaptiveConversationIntelligence.OperationalStateSnapshot.initial();
        AdaptiveConversationIntelligence.PreviousStrategyRecord safePrevStrat =
                previousStrategyRecord != null ? previousStrategyRecord : AdaptiveConversationIntelligence.PreviousStrategyRecord.initialNone();
        String commStylePref = safeOp.communicationStylePreference();

        sb.append("    customer_understanding_model_draft2 [UNIFIED_3_LAYER_MEMORY_V23]:\n");

        // Layer 1: Conversational Context
        sb.append("    1. layer_1_conversational_context:\n")
          .append("       - current_turn_sequence_id: \"").append(sequentialTurnId).append("\"\n")
          .append("       - topic_flow: { current_topic: \"").append(l1.currentTopic())
          .append("\", topic_history: ").append(l1.topicHistory())
          .append(", pending_references: ").append(l1.pendingReferences())
          .append(", last_bot_action: \"").append(compactSingleLine(l1.lastBotAction(), 60)).append("\" }\n");
        if (!l1.unresolvedRequests().isEmpty()) {
            sb.append("       - unresolved_requests: [");
            for (int i = 0; i < l1.unresolvedRequests().size(); i++) {
                if (i > 0) sb.append(", ");
                AdaptiveConversationIntelligence.UnresolvedRequestItem ur = l1.unresolvedRequests().get(i);
                sb.append('{').append(ur.status().name()).append(": \"").append(compactSingleLine(ur.requestSummary(), 60)).append("\"}");
            }
            sb.append("]\n");
        }
        if (!candidates.isEmpty()) {
            sb.append("       - unverified_parser_candidates [verified=false]:\n");
            for (AdaptiveConversationIntelligence.UnverifiedParserCandidate c : candidates) {
                if (c == null) continue;
                sb.append("         * { type: \"").append(c.candidateType())
                  .append("\", raw: \"").append(c.rawValue())
                  .append("\", normalized: \"").append(c.normalizedValue())
                  .append("\", verified: false }\n");
            }
        }
        if (tier1CustomerStatements != null && !tier1CustomerStatements.isEmpty()) {
            sb.append("       - recent_customer_statements:\n");
            int stmtStart = Math.max(0, tier1CustomerStatements.size() - 5);
            for (int i = stmtStart; i < tier1CustomerStatements.size(); i++) {
                AdaptiveConversationIntelligence.CustomerStatementRecord st = tier1CustomerStatements.get(i);
                if (st == null) continue;
                sb.append("         * [").append(st.turnId()).append(" | ").append(st.category())
                  .append("]: \"").append(compactSingleLine(st.content(), 130)).append("\"\n");
            }
        }

        // Layer 2: Consultative Profile (Interaction Permission + Multi-Intent & Conversation State + 5-State Symptoms + Versioned Facts + Hypothesis)
        sb.append("    2. layer_2_consultative_profile:\n")
          .append("       - intent_and_goal: { last_turn_intent: \"").append(l2.turnIntent())
          .append("\", last_secondary_intents: ").append(l2.secondaryIntents())
          .append(", conversation_state: \"").append(l2.conversationState())
          .append("\", sticky_conversation_goal: \"").append(l2.conversationGoal())
          .append("\", interaction_permission: \"").append(l2.interactionPermission().name())
          .append("\", emotion: \"").append(l2.emotion()).append("\" }\n")
          .append("       - communication_style_preference: \"").append(commStylePref)
          .append("\" | effective_turn_depth: \"").append(l2.effectiveTurnDepth()).append('"');
        if ("CONCISE".equalsIgnoreCase(commStylePref)) {
            sb.append(" (MẶC ĐỊNH PHIÊN = CONCISE: Trả lời gọn 1–2 câu trọng tâm trong 1 bong bóng; mở rộng DETAILED_ON_DEMAND khi khách chủ động hỏi chi tiết)");
        }
        sb.append('\n');
        if (!l2.selfReportedSymptoms().isEmpty()) {
            sb.append("       - self_reported_symptoms_5_states:\n");
            for (AdaptiveConversationIntelligence.VersionedCustomerFact sf : l2.selfReportedSymptoms()) {
                if (sf == null) continue;
                sb.append("         * [status=").append(sf.validity().name())
                  .append(" | turn=").append(sf.sourceTurnId())
                  .append("]: \"").append(compactSingleLine(sf.value(), 70)).append("\"\n");
            }
        }
        if (!l2.confirmedFacts().isEmpty()) {
            sb.append("       - versioned_confirmed_facts:\n");
            int factStart = Math.max(0, l2.confirmedFacts().size() - 8);
            for (int i = factStart; i < l2.confirmedFacts().size(); i++) {
                AdaptiveConversationIntelligence.VersionedCustomerFact vf = l2.confirmedFacts().get(i);
                if (vf == null) continue;
                sb.append("         * [validity=").append(vf.validity().name())
                  .append(" | turn=").append(vf.sourceTurnId())
                  .append(" | key=").append(vf.key())
                  .append("]: \"").append(compactSingleLine(vf.value(), 80)).append("\"\n");
            }
        }
        sb.append("       - working_hypothesis_and_barrier: { barrier_type: \"").append(l2.barrierHypothesis().barrierType())
          .append("\", content: \"").append(safeHyp.content().isEmpty() ? "(chưa có giả thuyết)" : compactSingleLine(safeHyp.content(), 110))
          .append("\", lifecycle_op: ").append(safeHyp.lastLifecycleOp().name())
          .append(", confidence: ").append(String.format(Locale.ROOT, "%.2f", safeHyp.confidence()))
          .append(", evidence_turn_ids: ").append(safeHyp.evidenceTurnIds()).append(" }\n")
          .append("       - previous_strategy_record: { turn_id: \"").append(safePrevStrat.turnId())
          .append("\", last_primary_priority: \"").append(safePrevStrat.lastPrimaryPriority())
          .append("\", last_chosen_strategy: \"").append(safePrevStrat.lastChosenStrategy())
          .append("\", recorded_outcome: \"").append(safePrevStrat.strategyOutcome().name()).append("\" }\n");

        // Layer 3: Business & Commitment State (Java Core Authority)
        sb.append("    3. layer_3_business_and_commitment_state:\n")
          .append("       - package_progression: { interested_package_id: ")
          .append(l3.interestedPackageId() != null ? ("\"" + l3.interestedPackageId() + "\"") : "null")
          .append(", committed_selected_package_id: ")
          .append(l3.selectedPackageId() != null ? ("\"" + l3.selectedPackageId() + "\"") : "null")
          .append(", booking_readiness: \"").append(l3.bookingReadiness()).append("\" }\n")
          .append("       - commitment_state: { commitment_level: \"")
          .append(l3.commitmentLevel() != null ? l3.commitmentLevel().name() : "NONE")
          .append("\", commitment_evidence: ")
          .append(l3.commitmentEvidence() != null ? ("\"" + l3.commitmentEvidence() + "\"") : "null")
          .append(", last_offered_package_ids: ")
          .append(l3.lastOfferedPackageIds() != null ? l3.lastOfferedPackageIds().toString() : "[]")
          .append(" }\n");
        if (l3.negativePreferences() != null && !l3.negativePreferences().isEmpty()) {
            sb.append("       - negative_preferences:\n");
            for (var entry : l3.negativePreferences()) {
                sb.append("         * { targetKey: \"").append(entry.targetKey())
                  .append("\", scope: \"").append(entry.scope())
                  .append("\", evidenceSpan: \"").append(entry.evidenceSpan()).append("\" }\n");
            }
        }
        sb.append("       - booking_state: { status: \"").append(safeBooking.status().name())
          .append("\", selected_product_id: ").append(safeBooking.selectedProductId() != null ? ("\"" + safeBooking.selectedProductId() + "\"") : "null")
          .append(", selected_product_label: ").append(safeBooking.selectedProductLabel() != null ? ("\"" + safeBooking.selectedProductLabel() + "\"") : "null")
          .append(", booking_date: ").append(safeBooking.bookingDate() != null ? ("\"" + safeBooking.bookingDate() + "\"") : "null")
          .append(", booking_time: ").append(safeBooking.bookingTime() != null ? ("\"" + safeBooking.bookingTime() + "\"") : "null")
          .append(" }\n")
          .append("       - handoff_state: { status: \"").append(safeOp.handoffStatus().name())
          .append("\", ticket_id: ").append(safeOp.activeHandoffTicketId() != null ? ("\"" + safeOp.activeHandoffTicketId() + "\"") : "null")
          .append(", last_error: ").append(safeOp.lastHandoffError() != null ? ("\"" + safeOp.lastHandoffError() + "\"") : "null")
          .append(", opted_out: ").append(safeOp.optedOut())
          .append(" }\n");
        if (tier4VerifiedBusinessFacts != null && !tier4VerifiedBusinessFacts.isEmpty()) {
            sb.append("       - verified_business_facts [verified=true]:\n");
            for (AdaptiveConversationIntelligence.VerifiedBusinessFact f : tier4VerifiedBusinessFacts) {
                if (f == null) continue;
                sb.append("         * [").append(f.category()).append("] ").append(f.factKey())
                  .append(" = \"").append(f.factValue()).append("\" (source=").append(f.authoritativeSource()).append(")\n");
            }
        }
        if (sessionCommitments != null && !sessionCommitments.isEmpty()) {
            sb.append("       - session_commitments:\n");
            for (AdaptiveConversationIntelligence.CommitmentRecord cr : sessionCommitments) {
                if (cr == null) continue;
                sb.append("         * { id: \"").append(cr.commitmentId())
                  .append("\", created_turn: \"").append(cr.createdTurnId())
                  .append("\", owner: \"").append(cr.owner().name())
                  .append("\", commitment_status: \"").append(cr.commitmentStatus().name())
                  .append("\", execution_status: \"").append(cr.executionStatus().name())
                  .append("\", task_id: ").append(cr.taskId() != null ? ("\"" + cr.taskId() + "\"") : "null")
                  .append(", verified_timeframe: ").append(cr.verifiedTimeframe() != null ? ("\"" + cr.verifiedTimeframe() + "\"") : "null")
                  .append(", content: \"").append(cr.content()).append("\" }\n");
            }
        }

        // Layer 4: P3.7-B.2 Lightweight Customer Understanding & Next Action Planner
        List<String> alreadyMentionedDetails = new ArrayList<>();
        if (history != null) {
            boolean botMentionedSpaceOrCurtain = false;
            boolean botMentionedPostSessionSnack = false;
            for (FanpageBotRequest.ConversationTurn t : history) {
                if (t == null || t.content() == null || t.content().isBlank()) continue;
                if ("assistant".equalsIgnoreCase(t.role()) || "bot".equalsIgnoreCase(t.role())) {
                    String botLow = Normalizer.normalize(t.content(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                    if (botLow.contains("rèm") || botLow.contains("vách ngăn") || botLow.contains("riêng tư")) {
                        botMentionedSpaceOrCurtain = true;
                    }
                    if (botLow.contains("ăn nhẹ") || botLow.contains("trái cây") || botLow.contains("thực dưỡng") || botLow.contains("trà")) {
                        botMentionedPostSessionSnack = true;
                    }
                }
            }
            if (botMentionedSpaceOrCurtain) {
                alreadyMentionedDetails.add("không gian / rèm che riêng (ĐÃ NÓI -> CẤM lặp lại)");
            }
            if (botMentionedPostSessionSnack) {
                alreadyMentionedDetails.add("ăn nhẹ / trái cây / trà sau buổi (ĐÃ NÓI -> CẤM lặp lại)");
            }
        }
        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan lwPlan = prebuiltTurnPlan != null
                ? prebuiltTurnPlan
                : AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                        verbatimOriginal, history != null ? history : List.of(), safeDraft2, safeBooking);
        sb.append("    4. lightweight_customer_understanding_and_next_action_planner:\n")
          .append("       - customer_state: { needs: ").append(lwPlan.customerState().needs())
          .append(", emotions: ").append(lwPlan.customerState().emotions())
          .append(", concerns: ").append(lwPlan.customerState().concerns())
          .append(", buying_signals: ").append(lwPlan.customerState().buyingSignals())
          .append(", friction_history: ").append(lwPlan.customerState().frictionHistory())
          .append(", communication_signals: ").append(lwPlan.customerState().communicationSignals())
          .append(" }\n")
          .append("       - inferences: [");
        for (int i = 0; i < lwPlan.inferences().size(); i++) {
            if (i > 0) sb.append(", ");
            AdaptiveConversationIntelligence.LightweightInferenceItem inf = lwPlan.inferences().get(i);
            sb.append("{ hypothesis: \"").append(inf.hypothesis())
              .append("\", confidence: ").append(String.format(Locale.ROOT, "%.2f", inf.confidence()))
              .append(", evidence: ").append(inf.evidence()).append(" }");
        }
        sb.append("]\n")
          .append("       - unknowns: ").append(lwPlan.unknowns()).append('\n')
          .append("       - recommended_action: \"").append(lwPlan.recommendedAction().name()).append("\"\n")
          .append("       - price_intent_sub_mode: \"").append(lwPlan.priceIntentSubMode().name()).append("\"\n")
          .append("       - contextual_action_guidance: \"").append(lwPlan.actionExecutionDirective()).append("\"\n");
        if (!alreadyMentionedDetails.isEmpty()) {
            sb.append("       - already_mentioned_details_do_not_repeat: ").append(alreadyMentionedDetails).append('\n');
        }
        sb.append("  </SESSION_MEMORY_5_TIER>\n");
        sb.append("</SINGLE_PASS_COGNITIVE_CONTRACT>\n\n");

        // 5. DOCUMENT CONTEXT (Grounded KB facts: operating hours, address, policies, vouchers — deduplicated & pruned of raw catalog tables)
        sb.append("<DOCUMENT_CONTEXT>\n");
        if (fusedChunks != null && !fusedChunks.isEmpty()) {
            boolean hasStructuredCatalog = pkgIndex > 0;
            boolean isSingleSessionPriceObjectionTurn = (hasExplicitPriceObjectionOrDownsellCue(verbatimOriginal)
                    || CleanAgentOrchestratorService.hasCompetitorOrExternalPriceComparisonCue(verbatimOriginal))
                    && (verbatimOriginal == null
                        || !verbatimOriginal.toLowerCase(Locale.ROOT).matches("(?isU).*\\b(combo|nhiều\\s+buổi|\\d+\\s*buổi|thẻ|voucher|ưu\\s+đãi)\\b.*"));
            Set<String> seenChunkPrefixes = new LinkedHashSet<>();
            int kept = 0;
            int totalDocChars = 0;
            for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
                if (kept >= 6 || totalDocChars >= 1450) break;
                if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
                String text = fc.chunk().text().strip();
                if (text.isEmpty()) continue;
                if (hasStructuredCatalog && isRedundantCatalogTableChunkForAciV2(text, verbatimOriginal)) {
                    continue;
                }
                if (isSingleSessionPriceObjectionTurn) {
                    text = text.replaceAll("(?m)^[^\\n]*(?:[Mm]ua\\s+gói\\s+từ\\s+\\d+\\s+buổi|từ\\s+\\d+\\s+buổi\\s+trở\\s+lên)[^\\n]*\\n?", "");
                }
                text = text.replaceAll("(?m)^\\s*\\|\\s*:?-+:?\\s*(?:\\|\\s*:?-+:?\\s*)+\\|?\\s*$\\n?", "")
                        .replaceAll("[ \\t]{2,}", " ")
                        .strip();
                if (text.isEmpty()) continue;
                String normPrefix = Normalizer.normalize(
                                text.substring(0, Math.min(85, text.length())),
                                Normalizer.Form.NFC)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("\\s+", " ");
                if (!seenChunkPrefixes.add(normPrefix)) {
                    continue;
                }
                int remainingBudget = Math.max(180, 1500 - totalDocChars);
                int perChunkLimit = Math.min(620, remainingBudget);
                if (text.length() > perChunkLimit) {
                    text = text.substring(0, perChunkLimit) + "...";
                }
                kept++;
                totalDocChars += text.length();
                sb.append("[Chunk ").append(kept).append("] ").append(text).append("\n\n");
            }
        }
        sb.append("</DOCUMENT_CONTEXT>\n\n");

        // 6. VERBATIM CONVERSATION HISTORY WITH [T1]..[T_CURRENT] TURN IDS & BURST BUBBLES
        sb.append("<CONVERSATION_HISTORY>\n");
        int turnIdx = 0;
        if (history != null && !history.isEmpty()) {
            int startIdx = Math.max(0, history.size() - 10);
            for (int i = startIdx; i < history.size(); i++) {
                FanpageBotRequest.ConversationTurn t = history.get(i);
                if (t == null || t.content() == null || t.content().isBlank()) continue;
                turnIdx++;
                boolean isBotRole = "assistant".equalsIgnoreCase(t.role()) || "bot".equalsIgnoreCase(t.role());
                String roleLabel = isBotRole ? "Bot" : "Khách";
                String renderedTurnText = isBotRole
                        ? t.content().strip().replaceAll("\\r?\\n\\s*\\r?\\n+", " | ")
                        : t.content().strip();
                sb.append("[T").append(turnIdx).append("] ")
                  .append(roleLabel).append(": ")
                  .append(renderedTurnText)
                  .append('\n');
            }
        }
        List<String> burstBubbles = threeStreamInput != null ? threeStreamInput.originalBurstBubbles() : List.of();
        if (burstBubbles.size() >= 2) {
            sb.append("[T_CURRENT] Khách (gửi liên tiếp ").append(burstBubbles.size()).append(" bong bóng nguyên văn):\n");
            for (int i = 0; i < burstBubbles.size(); i++) {
                sb.append("  [Bong bóng ").append(i + 1).append("] ").append(burstBubbles.get(i)).append('\n');
            }
        } else {
            sb.append("[T_CURRENT] Khách: ").append(verbatimOriginal).append('\n');
        }
        sb.append("</CONVERSATION_HISTORY>");
        return sb.toString();
    }

    public enum CatalogViewMode { NEED_MATCHED_RECOMMENDATION, FULL_DISCLOSURE, PACKAGE_COMPARISON }

    public static CatalogViewMode resolveCatalogViewMode(String verbatimOriginal) {
        String query = verbatimOriginal != null ? verbatimOriginal.toLowerCase(Locale.ROOT) : "";
        if (List.of("gồm những gì", "bao gồm gì", "chi tiết gói", "có phục hồi tóc",
                "có chăm sóc tóc", "bảng giá đầy đủ", "những bước nào").stream().anyMatch(query::contains)) {
            return CatalogViewMode.FULL_DISCLOSURE;
        }
        if (List.of("khác nhau", "so sánh", "gói nào hơn", "2 gói này").stream().anyMatch(query::contains)) {
            return CatalogViewMode.PACKAGE_COMPARISON;
        }
        return CatalogViewMode.NEED_MATCHED_RECOMMENDATION;
    }

    private static void appendAciUnblindedCatalogLine(
            StringBuilder sb,
            int index,
            String role,
            PackageCatalogService.PackageSummary pkg
    ) {
        appendAciUnblindedCatalogLine(sb, index, role, pkg, CatalogViewMode.NEED_MATCHED_RECOMMENDATION, false);
    }

    private static void appendAciUnblindedCatalogLine(
            StringBuilder sb,
            int index,
            String role,
            PackageCatalogService.PackageSummary pkg,
            CatalogViewMode viewMode,
            boolean allowHairDetail
    ) {
        String serviceGroup = resolveCatalogServiceGroup(pkg);
        sb.append("    ").append(index).append(". [role=").append(role)
          .append(" | service_group=\"").append(serviceGroup).append("\"")
          .append(" | id=\"").append(pkg.productId()).append("\"");
        if (pkg.code() != null && !pkg.code().isBlank()) {
            sb.append(" | code=\"").append(pkg.code().strip()).append("\"");
        }
        sb.append("] ").append(pkg.canonicalLabel())
          .append(" — ").append(pkg.priceDisplay());
        if (pkg.durationMinutes() != null && pkg.durationMinutes() > 0
                && pkg.durationLabel() != null && !pkg.durationLabel().isBlank()
                && (pkg.canonicalLabel() == null
                    || !pkg.canonicalLabel().toLowerCase(Locale.ROOT).contains(pkg.durationLabel().toLowerCase(Locale.ROOT)))) {
            sb.append(" (").append(pkg.durationLabel()).append(')');
        }
        String genericMetaNote = buildGenericVerifiedCatalogMetadataSummary(pkg, viewMode, allowHairDetail);
        if (!genericMetaNote.isEmpty()) {
            sb.append(" | ").append(genericMetaNote);
        }
        sb.append('\n');
    }

    static String resolveCatalogServiceGroup(PackageCatalogService.PackageSummary pkg) {
        if (pkg == null) {
            return "GENERAL_SERVICE";
        }
        String labelSlug = toUpperAsciiSlug(pkg.canonicalName());
        String familySlug = toUpperAsciiSlug(pkg.serviceFamily());
        if (!familySlug.isEmpty() && !labelSlug.isEmpty()) {
            boolean sharesTokenWithFamily = false;
            for (String token : familySlug.split("_")) {
                if (token.length() >= 3 && labelSlug.contains(token)) {
                    sharesTokenWithFamily = true;
                    break;
                }
            }
            if (!sharesTokenWithFamily) {
                return labelSlug;
            }
        }
        if (!familySlug.isEmpty()) {
            if (!familySlug.contains("_") && pkg.bodyAreas() != null) {
                for (String scope : pkg.bodyAreas()) {
                    String scopeSlug = toUpperAsciiSlug(scope);
                    if (scopeSlug.contains("_") && scopeSlug.split("_").length == 2) {
                        return familySlug + "_" + scopeSlug;
                    }
                }
            }
            return familySlug;
        }
        if (!labelSlug.isEmpty()) {
            return labelSlug;
        }
        return "GENERAL_SERVICE";
    }

    private static String toUpperAsciiSlug(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return Normalizer.normalize(raw.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase(Locale.ROOT);
    }

    private static String buildGenericVerifiedCatalogMetadataSummary(PackageCatalogService.PackageSummary pkg) {
        return buildGenericVerifiedCatalogMetadataSummary(
                pkg, CatalogViewMode.NEED_MATCHED_RECOMMENDATION, false);
    }

    private static String buildGenericVerifiedCatalogMetadataSummary(
            PackageCatalogService.PackageSummary pkg,
            CatalogViewMode viewMode,
            boolean allowHairDetail
    ) {
        if (pkg == null) {
            return "";
        }
        List<String> parts = new ArrayList<>(3);
        if (pkg.roomType() != null && !pkg.roomType().isBlank()) {
            String roomRaw = pkg.roomType().strip();
            String roomLow = roomRaw.toLowerCase(Locale.ROOT);
            if (roomLow.contains("phòng chung") && !roomLow.contains("rèm")) {
                parts.add(compactSingleLine(roomRaw + " (có rèm riêng từng giường, KHÔNG phòng VIP riêng)", 68));
            } else {
                // P3.7-B: giữ trọn mệnh đề mô tả không gian (không cắt giữa từ kiểu "mỗi giường đều có r…").
                parts.add(compactCompleteClause(roomRaw, 72));
            }
        }
        String compactScope = summarizeVerifiedScopeTags(pkg.scopeTags());
        if (!compactScope.isEmpty()) {
            parts.add("Vùng: " + compactScope);
        }
        String rawIncludes = pkg.includesSummary() != null ? pkg.includesSummary().strip() : "";
        boolean suppressUnmatchedAddons = viewMode == CatalogViewMode.NEED_MATCHED_RECOMMENDATION && !allowHairDetail;
        String inc = rawIncludes.replaceAll("(?iU)massage\\s+điều\\s+trị", "massage chăm sóc chuyên sâu");
        String proc = pkg.processSummary() != null
                ? pkg.processSummary().strip().replaceAll("(?iU)massage\\s+điều\\s+trị", "massage chăm sóc chuyên sâu")
                : "";
        if (suppressUnmatchedAddons) {
            inc = inc.replaceAll("(?iU)(?:\\s*[,;+&/]\\s*)?(?:chăm sóc\\s+)?phục hồi tóc", "")
                    .replaceAll("(?iU)(?:\\s*[,;+&/]\\s*)?ăn nhẹ\\s*&\\s*trái cây theo mùa", "").strip();
            proc = proc.replaceAll("(?iU)(?:\\s*[,;+&/]\\s*)?(?:chăm sóc\\s+)?phục hồi tóc", "")
                    .replaceAll("(?iU)(?:\\s*[,;+&/]\\s*)?ăn nhẹ\\s*&\\s*trái cây theo mùa", "").strip();
        }
        if (!inc.isEmpty()) {
            Integer dur = pkg.durationMinutes();
            String dispName = pkg.displayName() != null ? pkg.displayName().strip() : "";
            if (dur != null && dur > 0
                    && !dispName.isEmpty()
                    && inc.toLowerCase(Locale.ROOT).startsWith(dispName.toLowerCase(Locale.ROOT))
                    && !inc.matches("(?isU).*\\b" + dur + "\\s*(?:p|phút)\\b.*")) {
                inc = dispName + " " + dur + "p" + inc.substring(dispName.length());
            }
            parts.add("Gồm: " + compactSingleLine(inc, 110));
            if (!proc.isEmpty() && !inc.toLowerCase(Locale.ROOT).contains(proc.toLowerCase(Locale.ROOT))) {
                parts.add("Quy trình: " + compactSingleLine(proc, 115));
            }
        } else if (!proc.isEmpty()) {
            parts.add("Quy trình: " + compactSingleLine(proc, 115));
        }
        if (suppressUnmatchedAddons && rawIncludes.toLowerCase(Locale.ROOT).contains("tóc")) {
            parts.add("[pain_matched_filter: KHÔNG tự nhắc phục hồi tóc/ăn nhẹ khi khách chưa hỏi]");
        }
        return String.join(" | ", parts);
    }

    /**
     * P3.7-B: Tóm tắt phạm vi chăm sóc từ {@code scopeTags} đã xác thực.
     * <p>Chỉ dùng nhãn tổng quát "toàn thân" khi nguồn THỰC SỰ chứa "toàn thân"/"full body"
     * (trước đây {@code size() >= 8} bị gán nhầm "toàn thân" cho mọi gói nhiều thẻ).
     * Các gói khác giữ nguyên thẻ phạm vi gốc, ưu tiên thẻ ngắn (vùng/hạng mục) — đa ngành, không hardcode tenant.
     */
    static String summarizeVerifiedScopeTags(List<String> scopeTags) {
        if (scopeTags == null || scopeTags.isEmpty()) {
            return "";
        }
        List<String> cleaned = scopeTags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> t.strip().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        if (cleaned.isEmpty()) {
            return "";
        }
        if (cleaned.contains("toàn thân") || cleaned.contains("full body")) {
            return "toàn thân";
        }
        List<String> shortTags = new ArrayList<>();
        List<String> longTags = new ArrayList<>();
        for (String t : cleaned) {
            if (t.split("\\s+").length <= 2 && t.length() <= 14) {
                shortTags.add(t);
            } else {
                longTags.add(t);
            }
        }
        List<String> ordered = new ArrayList<>(shortTags);
        ordered.addAll(longTags);
        return compactCompleteClause(String.join(", ", ordered.stream().limit(6).toList()), 60);
    }

    private static void appendCompactPagePlaybookDirectives(
            StringBuilder sb,
            List<AgentSkillDto> enabledPageSkills,
            List<LearnedRuleDto> activeLearnedRules,
            String effectiveCustomerPronoun,
            String currentMessage,
            boolean isOpeningTurn
    ) {
        List<AgentSkillDto> activeSkills = new ArrayList<>();
        if (enabledPageSkills != null) {
            Set<String> seenSkillKeys = new LinkedHashSet<>();
            List<AgentSkillDto> sortedSkills = enabledPageSkills.stream()
                    .filter(s -> s != null && s.active())
                    .sorted(Comparator.comparingInt(AgentSkillDto::priority).reversed())
                    .toList();
            for (AgentSkillDto s : sortedSkills) {
                String dedupeKey = (s.code() != null && !s.code().isBlank())
                        ? s.code().strip().toLowerCase(Locale.ROOT)
                        : compactSingleLine(s.name(), 80).toLowerCase(Locale.ROOT);
                if (!dedupeKey.isEmpty() && seenSkillKeys.add(dedupeKey)) {
                    activeSkills.add(s);
                }
            }
        }

        List<LearnedRuleDto> activeRules = selectTurnRelevantCompactRules(
                activeLearnedRules,
                currentMessage,
                isOpeningTurn,
                5
        );

        if (activeSkills.isEmpty() && activeRules.isEmpty()) {
            return;
        }

        sb.append("  <PAGE_PLAYBOOK_COMPACT_DIRECTIVES>\n")
          .append("    selection_policy: \"Chỉ chọn 1-2 code khớp thực tế trong enabled_agent_skills vào brain_reflection.activated_skill_codes ([] nếu hỏi vận hành/nói ngắn); luôn gọi khách là \\\"")
          .append(effectiveCustomerPronoun)
          .append("\\\"\"\n");

        if (!activeSkills.isEmpty()) {
            sb.append("    enabled_agent_skills:\n");
            for (AgentSkillDto s : activeSkills) {
                String code = (s.code() != null && !s.code().isBlank()) ? s.code().strip() : "skill";
                String coreDirective = extractCompactSkillDirective(s, 220);
                String anti = compactCompleteClause(s.antiPattern(), 240);
                sb.append("      - [").append(code).append("] ").append(coreDirective);
                if (!anti.isEmpty()) {
                    sb.append(" | Tránh: ").append(anti);
                }
                sb.append('\n');
            }
        }

        if (!activeRules.isEmpty()) {
            sb.append("    active_learned_rules:\n");
            for (LearnedRuleDto r : activeRules) {
                String code = (r.ruleCode() != null && !r.ruleCode().isBlank())
                        ? r.ruleCode().strip()
                        : ((r.category() != null && !r.category().isBlank()) ? r.category().strip() : "RULE");
                String genRaw = r.generalizedRule() != null ? r.generalizedRule().strip() : "";
                String doRaw = r.doActions() != null ? r.doActions().strip() : "";
                String ruleBody;
                if (!genRaw.isEmpty() && !doRaw.isEmpty()) {
                    String genCompact = compactCompleteClause(genRaw, 260);
                    String genNorm = genRaw.toLowerCase(Locale.ROOT);
                    String doNorm = doRaw.toLowerCase(Locale.ROOT);
                    if (genNorm.contains(doNorm)) {
                        ruleBody = genCompact;
                    } else if (doNorm.contains(genNorm)) {
                        ruleBody = compactCompleteClause(doRaw, 260);
                    } else {
                        ruleBody = genCompact + " | Nên: " + compactCompleteClause(doRaw, 220);
                    }
                } else if (!genRaw.isEmpty()) {
                    ruleBody = compactCompleteClause(genRaw, 260);
                } else {
                    ruleBody = compactCompleteClause(doRaw, 240);
                }
                String dontText = compactCompleteClause(r.dontActions(), 240);
                boolean isAcuteEmergency = FanpageBotService.isUniversalAcuteMedicalEmergency(currentMessage);
                if (isAcuteEmergency) {
                    if ("SAFE-NEURO-001".equalsIgnoreCase(code) || "PB-SPA-SAFETY-001".equalsIgnoreCase(code)) {
                        ruleBody = "Khi khách có triệu chứng cấp cứu y tế cấp tính (tức ngực khó thở, tê liệt nửa người, đau đầu dữ dội đột ngột) -> khuyên khách nằm nghỉ tại chỗ ngay và gọi cấp cứu 115 hoặc đến cơ sở y tế/bệnh viện gần nhất khẩn cấp";
                        dontText = "chào gói dịch vụ hoặc hẹn kết nối chuyên viên spa thay cho cấp cứu y tế";
                    }
                } else {
                    if ("SAFE-NEURO-001".equalsIgnoreCase(code)) {
                        ruleBody = "Khi khách có tình trạng nhạy cảm/hậu phẫu/tiểu phẫu/thai kỳ cần lưu ý chuyên môn -> thấu cảm ấm áp, đặt sự an toàn của khách lên hàng đầu và chủ động đề xuất kết nối chuyên viên/kỹ thuật viên phụ trách để kiểm tra trực tiếp trước khi tư vấn thao tác; TUYỆT ĐỐI KHÔNG tự hứa hẹn cách thao tác/nằm nghiêng/tránh vùng mổ khi chưa có đánh giá chuyên môn";
                        dontText = "tự ý khẳng định khỏi bệnh, tự hứa thao tác kỹ thuật/tư thế nằm trước khi chuyên viên đánh giá, hoặc đuổi khách sang cơ sở khác";
                    } else if ("PB-SPA-SAFETY-001".equalsIgnoreCase(code)) {
                        ruleBody = ruleBody.replace(
                                "lưu ý khách kiểm tra sức khỏe phù hợp với triệu chứng đau đầu/chóng mặt/hoa mắt tái diễn, và ",
                                "thấu cảm tự nhiên tình trạng khách chia sẻ, kết nối hợp lý với trải nghiệm thư giãn trong nguồn và "
                        );
                    }
                }
                sb.append("      - [").append(code).append("] ").append(ruleBody);
                if (!dontText.isEmpty()) {
                    sb.append(" | Cấm: ").append(dontText);
                }
                sb.append('\n');
            }
        }
        sb.append("  </PAGE_PLAYBOOK_COMPACT_DIRECTIVES>\n\n");
    }

    public static boolean isBareTurnOneGreetingOrConsultationRequest(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        if (isDirectTurnOnePriceOrOperationalQuestion(rawMessage)) {
            return false;
        }
        String norm = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        if (norm.matches("(?isU).*\\b(đau|mỏi|nhức|tê|chóng\\s+mặt|hoa\\s+mắt|mất\\s+ngủ|khó\\s+ngủ|căng\\s+thẳng|stress|vai|gáy|cổ|lưng|đầu|chân|tay|răng|nướu|mụn|nám|da|bầu|thai|phẫu\\s+thuật|mổ|đặt\\s+lịch|giữ\\s+chỗ)\\b.*")) {
            return false;
        }
        return norm.matches("(?isU)^\\s*(?:dạ\\s+|ơi\\s+|shop\\s+ơi\\s+|em\\s+ơi\\s+|bạn\\s+ơi\\s+|ad\\s+ơi\\s+)*(?:xin\\s+chào|chào|hi|hello|alo|tư\\s+vấn|cần\\s+tư\\s+vấn|cho\\s+(?:mình|chị|anh|tôi|em)\\s+hỏi|tư\\s+vấn\\s+(?:giúp|cho)(?:\\s+(?:mình|chị|anh|tôi|em))?|shop|bạn|em)(?:\\s+(?:ơi|nhé|nha|ạ|với|chút|tí|shop|em|bạn|mình))*\\s*[?？!.~]*\\s*$");
    }

    private static void appendVerifiedOperationalPoliciesFromSourceChunks(
            StringBuilder sb,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            String verbatimOriginal,
            List<PackageCatalogService.PackageSummary> primaryPackages,
            List<PackageCatalogService.PackageSummary> economicalPackages
    ) {
        if (fusedChunks == null || fusedChunks.isEmpty()) {
            return;
        }
        StringBuilder combinedChunks = new StringBuilder();
        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc != null && fc.chunk() != null && fc.chunk().text() != null) {
                combinedChunks.append(fc.chunk().text()).append('\n');
            }
        }
        String lowAll = combinedChunks.toString().toLowerCase(Locale.ROOT);
        boolean hasTipTuyTam = lowAll.contains("hoàn toàn tùy tâm") || lowAll.contains("quy định về tip");
        boolean hasNewCustFreeTip = lowAll.contains("hỗ trợ trọn gói") && lowAll.contains("tip");
        if (hasTipTuyTam) {
            sb.append("    verified_tip_and_surcharge_policy: \"Quy định chung trong tài liệu nguồn: Tiền tip chưa bao gồm trong giá dịch vụ và hoàn toàn tùy tâm, không bắt buộc, không thu phụ phí ẩn")
              .append(hasNewCustFreeTip ? " (riêng ưu đãi khách mới trải nghiệm buổi lẻ lần đầu được hỗ trợ trọn gói không nhận tip)\"\n" : "\"\n");
        }
        Matcher lastCheckinMatcher = Pattern.compile("(?iU)nhận\\s+khách\\s+cuối[^0-9\\n]{0,25}(\\d{1,2}[:h]\\d{2})").matcher(lowAll);
        if (lastCheckinMatcher.find()) {
            String lastTime = lastCheckinMatcher.group(1).replace('h', ':');
            sb.append("    verified_operating_hours_and_last_checkin: \"Theo tài liệu nguồn, GIỜ NHẬN KHÁCH CUỐI LÀ ")
              .append(lastTime)
              .append(" (nếu khách muốn đặt lịch muộn hơn ")
              .append(lastTime)
              .append(", BẮT BUỘC thông báo rõ giờ nhận khách cuối là ")
              .append(lastTime)
              .append(" và hỏi khách có tiện đẩy sớm lên trước ")
              .append(lastTime)
              .append(" hoặc dời sang ngày hôm sau không)\"\n");
        }
        if (lowAll.contains("làm sạch da mặt") || lowAll.contains("đắp mặt nạ dưỡng da")) {
            String matchedCompositePkgDesc = "gói tổng hợp tương ứng trong <VERIFIED_BUSINESS_CATALOG>";
            List<PackageCatalogService.PackageSummary> allPkgs = new ArrayList<>();
            if (primaryPackages != null) allPkgs.addAll(primaryPackages);
            if (economicalPackages != null) allPkgs.addAll(economicalPackages);
            allPkgs.sort(Comparator.comparingLong(PackageCatalogService.PackageSummary::priceAmountVnd));
            for (PackageCatalogService.PackageSummary p : allPkgs) {
                if (p == null || p.isAddon()) continue;
                String metaLow = ((p.canonicalLabel() != null ? p.canonicalLabel() : "") + " "
                        + (p.includesSummary() != null ? p.includesSummary() : "") + " "
                        + (p.processSummary() != null ? p.processSummary() : "")).toLowerCase(Locale.ROOT);
                if (metaLow.contains("da mặt") || metaLow.contains("mặt nạ") || metaLow.contains("dưỡng sinh")) {
                    matchedCompositePkgDesc = p.canonicalLabel() + " (" + p.durationLabel() + " - " + p.priceDisplay() + ")";
                    break;
                }
            }
            sb.append("    verified_service_process_inclusions: \"Theo tài liệu nguồn: Chưa có gói chăm sóc da mặt lẻ độc lập riêng, nhưng trong liệu trình ")
              .append(matchedCompositePkgDesc)
              .append(" ĐÃ BAO GỒM SẴN các bước làm sạch da mặt, tẩy tế bào chết, chăm sóc thư giãn vùng mặt và đắp mặt nạ dưỡng da (khi khách tìm gói chăm sóc da mặt tương đương, hãy giải thích rõ chưa có gói da mặt lẻ riêng nhưng ")
              .append(matchedCompositePkgDesc)
              .append(" đã có sẵn trọn bộ bước chăm sóc da mặt này để khách chọn ngay, KHÔNG khất hỏi cơ sở)\"\n");
        }
        String msgLow = verbatimOriginal != null ? verbatimOriginal.toLowerCase(Locale.ROOT) : "";
        boolean asksParking = msgLow.matches("(?isU).*\\b(đỗ\\s+xe|để\\s+xe|gửi\\s+xe|bãi\\s+xe|chỗ\\s+đậu\\s+xe|đậu\\s+xe|ô\\s*tô)\\b.*");
        boolean sourceHasParking = lowAll.matches("(?isU).*\\b(đỗ\\s+xe|để\\s+xe|gửi\\s+xe|bãi\\s+xe|đậu\\s+xe)\\b.*");
        if (asksParking && !sourceHasParking) {
            sb.append("    unverified_parking_query_notice: \"UNVERIFIED — Thông tin chỗ đỗ/gửi xe CHƯA CÓ trong dữ liệu nguồn xác thực của Page; BẮT BUỘC trả lời trung thực là hiện chưa có thông tin xác nhận chính thức trên hệ thống và đề nghị kiểm tra lại với bộ phận lễ tân cơ sở; TUYỆT ĐỐI CẤM tự suy đoán có bãi đỗ rộng rãi hay miễn phí\"\n");
        }
    }

    private static String buildCoreServiceGroupStartingPriceSummary(
            List<PackageCatalogService.PackageSummary> primaries,
            List<PackageCatalogService.PackageSummary> economicals
    ) {
        List<PackageCatalogService.PackageSummary> all = new ArrayList<>();
        if (primaries != null) all.addAll(primaries);
        if (economicals != null) all.addAll(economicals);
        if (all.isEmpty()) {
            return "";
        }
        Map<String, List<PackageCatalogService.PackageSummary>> byGroup = new LinkedHashMap<>();
        for (PackageCatalogService.PackageSummary p : all) {
            if (p == null || p.isAddon() || p.priceAmountVnd() <= 0L) continue;
            String g = resolveCatalogServiceGroup(p);
            byGroup.computeIfAbsent(g, k -> new ArrayList<>()).add(p);
        }
        boolean hasMultiPackageOrFullDurationGroup = byGroup.values().stream().anyMatch(list ->
                list.size() >= 2 || list.stream().anyMatch(p -> extractMinutesFromDurationLabel(p.durationLabel()) >= 45));
        List<PackageCatalogService.PackageSummary> groupMinPkgs = new ArrayList<>();
        for (Map.Entry<String, List<PackageCatalogService.PackageSummary>> e : byGroup.entrySet()) {
            List<PackageCatalogService.PackageSummary> list = e.getValue();
            if (list.isEmpty()) continue;
            int maxDurInGroup = list.stream().mapToInt(p -> extractMinutesFromDurationLabel(p.durationLabel())).max().orElse(60);
            if (hasMultiPackageOrFullDurationGroup && list.size() == 1 && maxDurInGroup > 0 && maxDurInGroup <= 35) {
                continue;
            }
            PackageCatalogService.PackageSummary minPkg = list.stream()
                    .min(Comparator.comparingLong(PackageCatalogService.PackageSummary::priceAmountVnd))
                    .orElse(list.get(0));
            groupMinPkgs.add(minPkg);
        }
        groupMinPkgs.sort(Comparator.comparingLong(PackageCatalogService.PackageSummary::priceAmountVnd));
        List<String> groupParts = new ArrayList<>(groupMinPkgs.size());
        for (PackageCatalogService.PackageSummary minPkg : groupMinPkgs) {
            String dur = (minPkg.durationLabel() != null && !minPkg.durationLabel().isBlank())
                    ? (" (" + minPkg.durationLabel().strip() + ")")
                    : "";
            groupParts.add(minPkg.canonicalLabel() + dur + " khởi điểm từ " + minPkg.priceDisplay());
        }
        return String.join("; ", groupParts);
    }

    private static int extractMinutesFromDurationLabel(String durationLabel) {
        if (durationLabel == null || durationLabel.isBlank()) {
            return 60;
        }
        Matcher m = Pattern.compile("(\\d{1,3})").matcher(durationLabel);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 60;
    }

    static boolean isDirectTurnOnePriceOrOperationalQuestion(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        // Hỏi thẳng giá / chi phí / bảng giá / con số giá cụ thể ở lượt 1 (VD: "Massage toàn thân mấy tiền?", "Gói 60p bao nhiêu?", "Giá?")
        if (norm.matches("(?isU)^giá\\s*[?？!.]*$")
                || norm.matches("(?isU).*\\b(mấy\\s+tiền|bao\\s+nhiêu\\s+tiền|bao\\s+nhiêu\\s+1|bao\\s+nhiêu\\s+một|giá\\s+bao\\s+nhiêu|giá\\s+sao|giá\\s+thế\\s+nào|giá\\s+như\\s+nào|giá\\s+ntn|bảng\\s+giá|chi\\s+phí|rổ\\s+giá|\\d{2,4}\\s*k\\b).*")
                || norm.matches("(?isU).*\\b(giá|gói)\\b.*\\b(bao\\s+nhiêu|bn)\\b.*")) {
            return true;
        }
        // Hỏi thẳng thông tin vận hành cụ thể ở lượt 1 (địa chỉ, cơ sở, giờ mở/đóng cửa, chỗ đỗ xe)
        return norm.matches("(?isU).*\\b(địa\\s+chỉ|ở\\s+đâu|chi\\s+nhánh|cơ\\s+sở\\s+nào|mấy\\s+giờ\\s+(mở|đóng|nghỉ)|giờ\\s+mở\\s+cửa|đỗ\\s+(xe|ô\\s*tô)|gửi\\s+xe)\\b.*");
    }

    private static String extractCompactSkillDirective(AgentSkillDto skill, int maxChars) {
        if (skill == null) {
            return "";
        }
        String tac = skill.tacticalInstructions() != null ? skill.tacticalInstructions().strip() : "";
        if (!tac.isEmpty()) {
            String core = tac;
            int sampleQuotesIdx = core.indexOf("-> [Các hướng diễn đạt");
            if (sampleQuotesIdx > 0) {
                core = core.substring(0, sampleQuotesIdx).strip();
            }
            core = core.replaceFirst("^\\[Tình huống[^\\]]*\\]\\s*", "Khi: ")
                    .replaceAll("->\\s*\\[Nghệ thuật[^\\]]*\\]\\s*", "-> ");
            if (!core.isEmpty()) {
                return compactCompleteClause(core, Math.max(maxChars, 220));
            }
        }
        return compactCompleteClause(skill.description(), maxChars);
    }

    /**
     * Unit P1.2: Nén chuỗi trên 1 dòng theo ranh giới câu / mệnh đề hoàn chỉnh ({@code 220-260} ký tự),
     * tuyệt đối không cắt cứng {@code 50-55} ký tự giữa chừng từ ngữ/mệnh đề phủ định.
     */
    static String compactCompleteClause(String raw, int maxChars) {
        if (raw == null || raw.isBlank() || maxChars <= 0) {
            return "";
        }
        String collapsed = raw.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .replace("\"", "'")
                .strip();
        if (collapsed.length() <= maxChars) {
            return collapsed;
        }
        int minBoundary = Math.max(40, (maxChars * 55) / 100);
        String window = collapsed.substring(0, maxChars);
        int bestCut = -1;
        for (String sep : List.of(". ", "; ", " — ", ", ")) {
            int idx = window.lastIndexOf(sep);
            if (idx >= minBoundary) {
                int endIdx = sep.equals(". ") ? (idx + 1) : idx;
                if (endIdx > bestCut) {
                    bestCut = endIdx;
                }
            }
        }
        if (bestCut >= minBoundary) {
            return collapsed.substring(0, bestCut).strip();
        }
        int lastSpace = window.lastIndexOf(' ');
        if (lastSpace >= minBoundary) {
            return collapsed.substring(0, lastSpace).strip() + "…";
        }
        return window.strip() + "…";
    }

    private static String compactSingleLine(String raw, int maxChars) {
        if (raw == null || raw.isBlank() || maxChars <= 0) {
            return "";
        }
        String collapsed = raw.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .replace("\"", "'")
                .strip();
        if (collapsed.length() <= maxChars) {
            return collapsed;
        }
        return collapsed.substring(0, Math.max(1, maxChars - 1)).strip() + "…";
    }
}
