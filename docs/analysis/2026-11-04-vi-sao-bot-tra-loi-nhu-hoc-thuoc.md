# Vì sao bot Nàng Sen trả lời như học thuộc, giọng không giống sale, EQ thấp và không có chiến lược chốt

**Ngày phân tích:** 2026-11-04
**Repo:** `docmind` — branch `refactor/aci-v2-cleanup`, HEAD `1cd09e4`
**Trang:** Nàng Sen — `page_id = 2850a8ef-ca4a-40eb-87fa-4f808640efee`
**Nguồn bằng chứng:**
1. Transcript thật trong Postgres: `conversation_sessions.id = 6ccb68f3-6882-416a-ae7e-9c1ceed8ea62` (8 lượt khách, 8 lượt bot, 19:28–19:45).
2. Log runtime `[ACI-V2]`, `[ACI-V2-DRAFT2]`, `[ACI-V2-BRAIN-TRACE]` cùng thời điểm.
3. Mã nguồn hiện hành + 42 migration + dữ liệu `agent_skills`, `learned_rules`, `knowledge_pages`, `chunks`.

> Kết luận ngắn: đây **không phải lỗi model**. Đây là lỗi kiến trúc. Những câu "máy móc" nhất trong transcript **do Java sinh ra**, không phải do LLM. LLM bị đặt vào một cái khuôn: 30.000 ký tự luật, một `good_example` viết sẵn, nhiệt độ 0.35, không có hình phạt lặp, và một tầng guard tự ghép câu trả lời bằng template.

---

## 0. TL;DR — 6 nguyên nhân gốc

| # | Nguyên nhân | Một câu giải thích |
|---|---|---|
| 1 | **Prompt là bản kiểm tuân thủ, không phải tính cách** | 14.5k ký tự luật nén + 113 regex phụ trợ; model học cách "không vi phạm" chứ không học cách "nói như người". |
| 2 | **Tầng guard tự viết câu trả lời bằng template** | 4/8 lượt bot chứa đúng một câu do Java sinh: `"Em ghi nhận đúng là mình đang gặp … ạ."` (referee:6582). Riêng T15, 2/3 câu do Java ghép. |
| 3 | **"Kế hoạch" chỉ là nhãn tự khai, không phải mệnh lệnh** | `selectedSpeechAct` = chính `strategy` do LLM tự viết (orchestrator:5886). Không có bộ kiểm tra quán tính `objective ↔ strategy ↔ next_action`. Reply được viết **cùng lúc** với kế hoạch, nên kế hoạch chỉ là lời bào chữa sau khi viết. |
| 4 | **Cảm xúc bị "dính" và không có lối thoát** | `conversation_goal` bị ghi đè thành *"GIẢI THOÁT ÁP LỰC VÀ TƯ VẤN DỪNG HỎI"* ở T7 rồi **không thể đổi** (AgentBrain:2033–2041). Từ T7 trở đi mọi lượt đều `SERVICE_RECOVERY` + `Active Listening` + `NO_ADDITIONAL_ACTION`. Một lượt khách bực giết cả phiên. |
| 5 | **Không có tầng bán hàng thật** | Chỉ còn 6 skill EQ (V24 xoá sạch bộ skill SPIN/CLOSER của V16). 0 skill mời chốt chủ động, 0 sample dialogue thật. Skill đặt lịch bị **chặn cứng** khi khách chưa `READY_TO_BOOK` (orchestrator:6678), mà bot không bao giờ đưa khách tới `READY_TO_BOOK`. Deadlock. |
| 6 | **Mù thông tin + tối ưu sai chỗ** | Embedding là **stub hash**; `candidate_k=80` > 65 chunk → mỗi lượt vector trả về đúng 57/65 chunk bất kể khách hỏi gì. Lượt khách nổi nóng nhất bị ép `FAST_NO_THINK` (orchestrator:6823–6828) và trả lời chậm nhất 31.6s. |

---

## 1. Bằng chứng: 8 lượt, và câu nào do ai viết

### 1.1. Toàn bộ transcript

| # | Khách | Bot |
|---|---|---|
| T1 | `giá` | `Nàng Sen chào mình ạ 🌷 Rất vui vì hôm nay mình ghé qua đây.`<br>`Gói dưỡng sinh cơ bản 60 phút — 239k, gói massage cơ bản 60 phút — 399k. Mình ưu tiên gói nào ạ?` |
| T3 | `tôi hoa mát trong mắt ù tai mệt mỏi` | `Em hiểu mình đang cảm thấy mệt mỏi và không thoải mái ạ.`<br>`Mình muốn trải nghiệm vừa đủ hay chuyên sâu hơn ạ?` |
| T5 | `Nó khác j nhau` | `Em hiểu mình đang cảm thấy mệt mỏi và không thoải mái ạ. Gói dưỡng sinh cơ bản 60 phút — 239k tập trung gội đầu + massage cổ vai gáy, còn gói massage cơ bản 60 phút — 399k là trị liệu toàn thân. Mình muốn trải nghiệm vừa đủ hay chuyên sâu hơn ạ?` |
| T7 | `Hỏi lại lắm thế` | `Em xin lỗi nếu mình cảm thấy bị dồn dập ạ. Em ghi nhận đúng là mình đang gặp ù tai và mệt mỏi ạ. Em sẽ không hỏi hay gợi ý thêm nữa.` |
| T9 | `thế rốt cuộc 2 gói khác j nhau` | `Gói 239k là gội đầu + massage cổ vai gáy 60 phút, gói 399k là massage trị liệu toàn thân 60 phút, cả hai đều yên tĩnh và có rèm riêng từng giường ạ. Mình thấy vùng nào cần chăm sóc hơn?` |
| T11 | `Đã mệt rồi còn hỏi vùng nào chăm sóc hơn, chăm 1 vùng rồi bỏ các vùng khác à` | `Em xin lỗi vì đã hỏi khiến mình thấy mệt thêm. Em ghi nhận đúng là mình đang gặp ù tai và mệt mỏi ạ. Mình yên tâm, gói toàn thân sẽ chăm trọn từ đầu đến chân, không bỏ sót vùng nào ạ?` |
| T13 | `haizzz, ngoài 2 gói trên còn gói nào khác k` | `Em xin lỗi vì đã khiến mình bực bần ạ. Em ghi nhận đúng là mình đang gặp ù tai, mệt mỏi và mệt ạ. Ngoài 2 gói trên, bên em còn có gói dưỡng sinh cao cấp 80 phút 339k và gói phục hồi 100 phút 580k, mình muốn tìm hiểu gói nào hơn ạ?` |
| T15 | `thế mà lúc giới thiệu nói có 2 gói, làm người ta tưởng chỉ có 2 gói , cách nói rất gây hiểu lầm` | `Em xin lỗi vì đã khiến mình hiểu lầm ạ. Em ghi nhận đúng là mình đang gặp ù tai, mệt mỏi và mệt ạ. Hiện tại trong các tình trạng ù tai, mệt mỏi và mệt thì mình đang thấy mệt và muốn được chăm sóc ưu tiên ở phần nào trước ạ?` |

### 1.2. Câu nào do ai sinh ra

| Câu | Nguồn thật | Bằng chứng |
|---|---|---|
| `Em ghi nhận đúng là mình đang gặp {triệu chứng} ạ.` | **Java** (guard ghép vào) | `CleanAgentOutputRefereeAndSanitizer.java:6580–6584` |
| `Hiện tại trong các tình trạng {list} thì mình đang thấy mệt và muốn được chăm sóc ưu tiên ở phần nào trước ạ?` | **Java** (guard ghép vào) | `CleanAgentOutputRefereeAndSanitizer.java:6587–6595` |
| `Nàng Sen chào mình ạ 🌷 …` | **DB persona** (`opening_bubbles`) | `knowledge_pages.persona_config` |
| `Em hiểu mình đang cảm thấy mệt mỏi và không thoải mái ạ.` (lặp y nguyên T3→T5) | LLM, **tự copy lượt trước của chính nó** trong `<CONVERSATION_HISTORY>` | `CleanAgentCatalogAndPromptBuilder.java:5333–5379` |
| `Em sẽ không hỏi hay gợi ý thêm nữa.` | LLM, làm theo prompt II.23(b) | `config/aci_v2_system_prompt.md:25` |
| `… là trị liệu toàn thân` | LLM (prompt cấm "điều trị" nhưng không cấm "trị liệu") | `aci_v2_system_prompt.md:34` |

**Đây là phát hiện quan trọng nhất:** 4/8 câu trả lời chứa một câu tiếng Việt do Java viết cứng. Riêng T15, 2/3 câu do Java ghép. Nếu bạn thấy bot "học thuộc", thì đúng là nó đang đọc thuộc — nhưng bài thuộc nằm trong file `.java`, không nằm trong model.

### 1.3. Những con số biết nói

- **7/8** câu trả lời kết thúc bằng dấu `?`. Khách đã nói "mệt", đã phàn nàn "hỏi lại lắm thế", bot vẫn hỏi.
- **4/8** câu mở đầu bằng `Em xin lỗi` (T7, T11, T13, T15) — dù T13 khách chỉ thở dài và hỏi một câu bình thường.
- **4/8** câu chứa đúng một câu template Java.
- **0/8** câu nhắc tới: đặt lịch, khung giờ, chi nhánh, số điện thoại, ưu đãi, bước tiếp theo.
- **0** lần bot hỏi khách ở Hà Nội hay Sài Gòn — dù giá hai chi nhánh khác nhau (1.270.000đ là giá SG).
- Danh sách triệu chứng bị **trùng lặp tích luỹ**: `ù tai, mệt mỏi và mệt`. Nguyên nhân ở mục 3.7.
- Lượt khách nổi nóng nhất (T11) là lượt **chậm nhất**: `llmMs=31.040`, `totalTurnMs=31.614` (25s timeout + 6s fallback sang DeepSeek).
- Mỗi lượt: `promptTokens 10.949–13.619`, `completionTokens 443–520`.

---

## 2. Giải phẫu: một câu trả lời được sinh ra như thế nào

```
Khách nhắn
   │
   ├─(1) Retrieval: BM25 + vector(stub) ──► 7 chunk
   │
   ├─(2) Build prompt (~30.000 ký tự):
   │      system prompt 14.5k luật
   │    + catalog + document context
   │    + memory 5 tầng
   │    + <AGENT_BRAIN_INSIGHT> chứa good_example VIẾT SẴN
   │    + <CONVERSATION_HISTORY> chứa nguyên văn 8 lượt bot cũ
   │    + recommended_action do if/else Java chọn
   │
   ├─(3) 1 lần gọi LLM duy nhất (FAST_NO_THINK, temp 0.35, max 2500):
   │      model vừa "phân tích" vừa "lên kế hoạch" vừa "viết bài"
   │      → trả JSON: brain_reflection + response_plan + bubbles
   │
   ├─(4) Referee: CHỈ kiểm sự thật/an toàn/quyền hạn,
   │      KHÔNG kiểm chiến lược, KHÔNG kiểm giọng, KHÔNG kiểm lặp
   │
   ├─(5) Guard/sanitizer: viết lại câu, CHÈN câu template,
   │      nối thêm câu hỏi khám phá nếu thiếu
   │
   └─(6) Gửi
```

Bốn điểm chết nằm ở (2), (3), (4), (5).

### 2.1. Prompt: 14.5k ký tự luật, viết như văn bản pháp quy

`config/aci_v2_system_prompt.md` dài 14.511 ký tự, chia 5 mục I–V, dày đặc `CẤM`, `0` (viết tắt của "không"), `?` = 0, `0 khoảng giá min–max`, `0 tự khai AI`… Mẫu:

> `3. 4 loại Price Intent … (A) GENERAL_PRICE_INQUIRY ("Giá", "Rổ giá sao"): Bubble 1 chào theo brand_welcome_line; Bubble 2 nêu 1–2 gói đại diện kèm giá, thời lượng, hạng mục chính (0 khoảng giá min–max, 0 danh sách dài) + 1 câu hỏi nhẹ khách ưu tiên gói nào.`

Đây là **đặc tả tuân thủ**, không phải **chỉ dẫn thể hiện**. Model đọc 14.5k ký tự luật để làm một việc 20 giây. Khi bị đặt giữa "phải trả lời đúng" và "phải viết hay", nó chọn cách an toàn: **câu ngắn, trung tính, đúng luật, không cá tính**. Đó chính là giọng học thuộc.

Đáng chú ý: prompt **cấm đúng những thứ tạo cảm giác ấm** — cấm `Dạ em hiểu ạ`, cấm `thảo mai`, cấm `mình thấy hợp với tình trạng hiện tại`, cấm mở đầu bằng xin lỗi. Sau nhiều vòng vá (V29 "anti-thảo-mai", V36, V37…), hệ quả là bot **không còn câu nào để nói ngoài câu trung tính**. Đây là over-correction kinh điển: chặn "thảo mai" bằng cách chặn luôn cảm xúc.

### 2.2. `good_example`: model được phát cho bài giải mẫu

`CleanAgentCatalogAndPromptBuilder.java:2485–2492`:

```java
sb.append("<AGENT_BRAIN_INSIGHT>\n")
  .append("3. turn_tactical_focus: \"").append(tacticalFocus).append("\"\n")
  .append("4. contrastive_reference:\n")
  .append("   - good_example: \"").append(goodExample).append("\"\n")
  .append("   - anti_pattern: \"").append(antiPattern).append("\"\n")
  .append("</AGENT_BRAIN_INSIGHT>\n\n");
```

`goodExample` được chọn từ hai nguồn:
1. Khối `if/else` khổng lồ ngay trong file Java (dòng 2301–2490) — mỗi nhánh là một câu trả lời hoàn chỉnh viết sẵn bằng tiếng Việt.
2. `AgentSkillDto.goodExample()` — cột `good_example` trong bảng `agent_skills`, cũng là câu trả lời mẫu hoàn chỉnh.

Ví dụ nguyên văn trong DB cho skill `discovery_empathy_and_scope`:

> `Dạ đi tập nặng về mà cơ khớp còn đang căng mỏi chắc người mình oải lắm ạ. Để em chọn đúng liệu trình thả lỏng vừa vặn nhất, hiện tại mình đang nhức mỏi nhiều ở vùng cổ vai gáy, lưng hay muốn chăm sóc giãn cơ toàn thân luôn ạ?`

Đây là câu mẫu cho **người đi tập gym**, không liên quan gì tới `hoa mắt ù tai`. Khi skill này được kích hoạt (T3, T5) và các điều kiện loại trừ không thoả, `goodExample` của skill thay thẳng cho câu mẫu mặc định (`CleanAgentCatalogAndPromptBuilder.java:2464–2472`) và đi vào prompt. Model nhận được cấu trúc "đồng cảm + hỏi vùng nào" và **áp khuôn**: T3 hỏi "vừa đủ hay chuyên sâu", T9 hỏi "vùng nào". Không phải model ngu; nó đang làm đúng bài mẫu.

### 2.3. Kế hoạch chỉ là nhãn

`CleanAgentOrchestratorService.java:5886–5890`:

```java
String selectedSpeechAct = llmOutputUnavailable ? "LLM_OUTPUT_UNAVAILABLE"
    : (!reflection.responsePlan().strategy().isBlank()
        ? reflection.responsePlan().strategy()
        : ...);
```

`selectedSpeechAct` — thứ được log ra và được coi là "hành vi bot đã thực hiện" — **chính là chuỗi `strategy` do LLM tự viết trong cùng một lần sinh**. Không có bước nào kiểm tra:

- `objective = GENERAL_PRICE_INQUIRY` nhưng `strategy = Warm Acknowledgment` — có hợp lý không?
- `turn_intent = SERVICE_RECOMMENDATION` nhưng `next_action = NO_ADDITIONAL_ACTION` — có mâu thuẫn không?
- `priority = SERVICE_RECOVERY` nhưng khách đang hỏi "còn gói nào khác không" — có đúng không?

Log T13 cho thấy đúng sự vô lý đó:

```
turn_intent="SERVICE_RECOMMENDATION" ... priority=SERVICE_RECOVERY,
response_plan={objective="SERVICE_RECOMMENDATION", strategy="Active Listening", next_action="NO_ADDITIONAL_ACTION"}
```

Ba trường nói ba chuyện khác nhau. Không ai bắt lỗi. Referee chỉ kiểm sự thật/an toàn (`checkAciV2TruthAuthorityAndSafety`), và theo đúng thiết kế, nó **không ghi đè chiến lược**:

> `Không ghi đè ngược nghĩa hành vi lời nói (speech act / strategy) của LLM trong chế độ LLM_COGNITIVE_V2` — `CleanAgentOutputRefereeAndSanitizer.java:7397–7400`

Đây là quyết định kiến trúc, không phải bug. Nhưng hệ quả là **không có ai chịu trách nhiệm về chiến lược bán hàng**. Model vừa đá bóng vừa thổi còi.

### 2.4. Guard: cái máy viết câu

`CleanAgentOutputRefereeAndSanitizer.java:6504–6544`. Nếu lượt bot không nhắc lại triệu chứng nào của khách, guard chèn ngay một câu:

```java
String clause = buildSymptomAcknowledgementClause(safeSelf, safeCust, safeSymptoms);
...
merged = ap.text() + " " + clause;
```

Và nếu lượt đó chưa có câu hỏi, guard nối thêm:

```java
out.set(firstRewrittenIdx, new ReplyBubble(
    rw.text() + " " + buildSymptomAnchoredDiscoveryQuestion(...), rw.typingDelayMs()));
```

Đây là "Fix-A" của đợt P3 — được thêm vào để chữa lỗi bot không bám lời khách. Nó chữa triệu chứng bằng cách **làm đúng cái bệnh**: thay vì dạy model nói cụ thể, hệ thống đọc hộ model một câu cứng.

**Đây là lý do trực tiếp khiến bot "học thuộc":** 4/8 lượt có cùng một câu do Java viết. Không model nào có thể tự nhiên khi bị chèn câu vào giữa.

---

## 3. Sáu nguyên nhân gốc, giải thích cơ chế

### 3.1. Hệ thống đang giải bài toán "tuân thủ", không giải bài toán "bán hàng"

Toàn bộ pipeline được thiết kế để **không nói sai**: grounding giá, không hứa chữa bệnh, không lộ SKU, không bịa khuyến mãi, không nói "trị liệu". Những ràng buộc này rất tốt và đã hoạt động (giá trong transcript đúng, không bịa).

Nhưng **không có nơi nào định nghĩa "một lượt tư vấn tốt trông như thế nào"**. Không có:

- mục tiêu của lượt (khách phải biết thêm gì / phải tiến thêm bước nào),
- thang bậc bán hàng,
- định nghĩa "đã đủ thông tin để đề xuất chưa",
- định nghĩa "khi nào được phép hỏi, khi nào phải trả lời",
- định nghĩa "một lần chốt thành công".

Kết quả: bot tối ưu cho **an toàn**, và an toàn = câu ngắn, trung tính, không cam kết, luôn kết bằng câu hỏi để khỏi phải quyết.

### 3.2. Kiến trúc "prompt khổng lồ + regex" thay cho mô hình hành vi

Khối lượng code nói lên tất cả:

| File | Dòng | Kích thước | `Pattern.compile` |
|---|---:|---:|---:|
| `CleanAgentOutputRefereeAndSanitizer.java` | 7.397 | 445 KB | **113** |
| `CleanAgentOrchestratorService.java` | 6.937 | 401 KB | — |
| `CleanAgentCatalogAndPromptBuilder.java` | 6.054 | 390 KB | — |
| `AdaptiveConversationIntelligence.java` | 5.640 | 315 KB | — |
| **Tổng 4 class** | **26.028** | **~1.55 MB** | — |

Bên trong là một cây `if/else` khổng lồ để phân loại ý định và chọn `recommended_action` (`AdaptiveConversationIntelligence.java:5786–5984`), một cây khác để chọn skill theo "STAGE" (`orchestrator:3886–4060`), và 113 regex để kiểm đầu ra.

Vấn đề không phải là "nhiều code". Vấn đề là **mỗi quy tắc mới đều là một câu trả lời cứng, và mỗi câu trả lời cứng làm bot giống máy hơn**. Sau 42 migration và 12 phiên bản prompt trong khoảng 1 tháng, hệ thống đã trở thành một cái cây quyết định đội lốt LLM.

Minh chứng: 4 skill trong 6 skill hiện có là **script** (cột `tactical_instructions` + `good_example`), không phải nguyên tắc.

### 3.3. Trạng thái cảm xúc dính, không có lối thoát

Đây là lỗi nghiêm trọng nhất về mặt hành vi.

**a) `conversation_goal` bị chiếm dụng bởi cảm xúc nhất thời.** `AgentBrainService.java:2033–2041`:

```java
String proposedGoal = reflection.conversationGoal();
boolean isEstablishedGoal = mem.draft2ConversationGoal != null
        && !"EXPLORE_SERVICES".equalsIgnoreCase(mem.draft2ConversationGoal);
if (proposedGoal != null && !isSideTopicOrOperationalGoalString(proposedGoal)) {
    if (!isEstablishedGoal || hasReviseOnNeed) {
        mem.draft2ConversationGoal = proposedGoal;
    }
}
```

T7 khách nói `Hỏi lại lắm thế`. Model đề xuất goal `GIẢI THOÁT ÁP LỰC VÀ TƯ VẤN DỪNG HỎI`. Goal này được lưu. Từ T8 trở đi `isEstablishedGoal = true` và **không có fact `need_scope` mới**, nên goal **không bao giờ đổi nữa**.

Log chứng minh: T9, T11, T13, T15 đều `conversation_goal="GIẢI THOÁT ÁP LỰC VÀ TƯ VẤN DỪNG HỎI"`.

Nghĩa là: **một câu phàn nàn về nhịp hỏi đã trở thành mục tiêu của cả phiên tư vấn**. Bot không còn mục tiêu bán hàng. Nó có mục tiêu "đừng làm khách khó chịu" — và cách duy nhất để đạt mục tiêu đó là im lặng.

**b) `SERVICE_RECOVERY` xoá sạch công cụ bán hàng.** Khi stage là `COMPLAINT_OR_DISTRESS` / `HUMAN_HANDOFF` / `STAGE_5_META_COMMUNICATION_REPAIR`, `orchestrator:3889–3896` loại bỏ **tất cả** skill: booking, discovery, price objection, sales, closing. Không còn gì để bán.

**c) Không có chuyển trạng thái "sửa xong → quay lại tư vấn".** Không có biến `repairDone` / `recoveryTurns`. Không có luật "sau 1 lượt xin lỗi, lượt sau phải đề xuất cụ thể". Vì vậy bot ở trong `SERVICE_RECOVERY` vĩnh viễn, và mỗi lượt lại xin lỗi — điều càng làm khách khó chịu hơn.

**d) `emotion` là nhãn, không phải trạng thái.** `EmotionalState[primary=FRUSTRATED, confidence=0.75]` — cùng một confidence 0.75 cho mọi lượt, không có cường độ, không có xu hướng, không có "đã được xoa dịu". Bot không phân biệt được "khách bực lần 1" và "khách bực lần 4".

### 3.4. Không có tầng bán hàng: 6 skill EQ, 0 skill chốt

Ngày xưa từng có. `V16__elite_sales_negotiation_psychology_skills.sql` seed các skill:

- `SALES_MICRO_COMMITMENT_CLOSE` — "Kích hoạt khi khách đã nắm thông tin, có tín hiệu quan tâm…"
- `SALES_APPOINTMENT_HOOK_CLOSE` — "Bán Lịch Hẹn & Khống Chế Khung Giờ A/B Ngắn Gọn"

Rồi `V24__canonical_eq_agent_skills_and_nang_sen_space.sql:134`:

```sql
DELETE FROM agent_skills;
```

và thay bằng **6 skill EQ**. Hiện tại `agent_skills = 6`:

| code | category |
|---|---|
| `discovery_empathy_and_scope` | PSYCHOLOGY |
| `price_objection_sensory_value` | PSYCHOLOGY |
| `soft_economical_bridge_and_compare` | SALES_STRATEGY |
| `warm_nod_to_gentle_scheduling` | COMMUNICATION |
| `booking_careful_preparation` | SALES_STRATEGY |
| `graceful_pause_keep_warmth` | COMMUNICATION |

Không có skill nào dạy **cách mời chốt**. Hai skill đặt lịch thì bị chặn cứng:

```java
if (!readyToBook && isBookingOrSchedulingSkillCode(skCode)) {
    break;   // orchestrator:6678
}
```

`readyToBook` chỉ true khi `booking_readiness == READY_TO_BOOK` hoặc `priority == BOOKING_COMPLETION` (orchestrator:6659–6665). Nhưng `booking_readiness` chỉ đổi khi **khách tự nói tín hiệu mua**. Và prompt cấm CTA cho tới khi `buying_signal >= MODERATE` (`aci_v2_system_prompt.md:22`).

Đây là **deadlock logic hoàn chỉnh**:

> Muốn chốt → cần `READY_TO_BOOK`.
> Muốn `READY_TO_BOOK` → cần tín hiệu mua từ khách.
> Muốn có tín hiệu mua → phải đề xuất gói + mời chốt.
> Nhưng mời chốt bị cấm/ bị gỡ skill khi chưa `READY_TO_BOOK`.

Kết quả trong transcript: **0/8 lượt có một lời mời đặt lịch**. `booking_readiness` đứng nguyên `EXPLORING` từ T1 đến T15.

Thêm hai chi tiết:

- `persona_config` của page có `"closing_action": null` và `"cta_template": null`. Không có định nghĩa nào về hành động chốt.
- Bảng `sample_dialogues` có **0 dòng**. Hệ thống chưa từng thấy một cuộc hội thoại bán hàng thật nào của Nàng Sen. Nó chỉ có 6 script do người viết code nghĩ ra.

### 3.5. Mù thông tin: embedding giả + retrieval không lọc

Log mỗi lượt:

```
embed-serve OK provider_model=stub batch_size=1 dim=384
stage=retrieve vector_candidates=57 fulltext_candidates=46 candidate_k=80 ... applied_rules=0
stage=rerank fused_total=51 returned=7 rrf_k=60
```

- `provider_model=stub`: `StubEmbeddingClient` là **vector hash giả** + một từ điển 5 concept cứng. Comment trong code ghi rõ: *"KHÔNG dùng cho chất lượng ngữ nghĩa thật"*.
- Toàn page có **65 chunk**. `candidate_k=80` > 65 → nhánh vector trả về **57 chunk mỗi lượt, bất kể khách hỏi "giá" hay "hoa mắt ù tai"**. Con số `vector_candidates=57` không đổi qua cả 8 lượt.
- Nghĩa là: **nhánh vector không lọc gì cả**. Toàn bộ công việc chọn ngữ cảnh do BM25 làm. Nếu BM25 trượt, model nhận chunk rác.

Khi ngữ cảnh không liên quan, model có hai lựa chọn: bịa (bị referee chặn) hoặc **bám vào luật trong prompt** (an toàn). Nó chọn cách hai. Đó là một lý do nữa khiến câu trả lời nghe như đọc điều lệ.

### 3.6. Tối ưu sai chỗ: lượt khó nhất bị cho ít "não" nhất

`CleanAgentOrchestratorService.java:6807–6838`:

```java
if (!multiBubbleBurst && !hasPendingCommitments
        && (isCommunicationStyleComplaintMessage(msg) || isExplicitConversationRefusalStopMessage(msg))) {
    return ThinkingMode.FAST_NO_THINK;      // ← phàn nàn về cách nói → KHÔNG SUY NGHĨ
}
...
if (... || PrimaryActionRouter.isFrustrationMessage(msg) || isConcreteServiceIncidentMessage(msg)
        || isConfirmedCustomerPriceObjection(msg, true)) {
    return ThinkingMode.OBJECTION_REFRAME_THINK;
}
return ThinkingMode.FAST_NO_THINK;          // ← mặc định cũng KHÔNG SUY NGHĨ
```

Đây là quyết định ngược đời: **khi khách phàn nàn về cách tư vấn — thời điểm cần suy nghĩ nhất — bot được chuyển sang chế độ nhanh nhất, không suy luận.** Lý do có thể là để giảm latency (đã có lượt 31s), nhưng cái giá là chất lượng.

Thực tế log cho thấy **cả 8 lượt đều `FAST_NO_THINK`, `reasoningEffort=none`**, kể cả lượt khách nổi nóng. `buildAciReasoningBudget` đặt `maxTokens=2500`, `effort="none"` cho mọi lượt không deep. Model phải vừa phân tích cảm xúc, vừa lập kế hoạch, vừa viết 2 bong bóng, vừa xuất một JSON 20 trường — trong 500 token, không được nghĩ.

### 3.7. Bảy lỗi dữ liệu nhỏ nhưng đầu độc giọng

**(a) Triệu chứng không được tách nguyên tử.** `splitAtomicHealthExpressions` chỉ tách theo `, ; / + và kèm cùng` (`AdaptiveConversationIntelligence.java:1559–1585`). Chuỗi `hoa mắt ù tai mệt mỏi` **không có dấu phẩy** → là **một** atom. Lượt sau khách nói `ù tai mệt mỏi` → atom mới. Lượt sau nữa `mệt mỏi hoa mắt ù tai` → atom mới nữa. Kết quả: 3 fact gần trùng, và bot đọc lại cả danh sách: `ù tai, mệt mỏi và mệt`.

**(b) Từ điển triệu chứng thiếu đúng hai triệu chứng quan trọng.** `persona_config.symptom_keywords` có `đau, mỏi, nhức, căng, tê, đau đầu, khó ngủ, mất ngủ, mệt mỏi, stress, căng thẳng` — **không có `hoa mắt`, `ù tai`, `chóng mặt`**. Vì vậy hệ thống chỉ "nghe" được `mệt mỏi`, còn hai dấu hiệu đáng lo nhất thì mất.

**(c) Câu chào lấn át câu trả lời.** T1 khách chỉ gõ `giá`, bot mở bằng 2 câu chào cố định từ `opening_bubbles` rồi mới vào giá.

**(d) Bẫy "chỉ có 2 gói".** Prompt I.3(A) yêu cầu *"Bubble 2 nêu 1–2 gói đại diện"* (`aci_v2_system_prompt.md:9`), nhưng **không yêu cầu nói rõ "đây là gói tiêu biểu, bên em còn gói khác"**. Khách hiểu là chỉ có 2 gói, và đến T15 thì phàn nàn đúng như vậy. Đây là lỗi do prompt, không phải do khách.

**(e) Xin lỗi đổ lỗi cho khách.** `Em xin lỗi vì đã khiến mình hiểu lầm ạ.` Người nói câu này đang ngầm nói "tại bạn hiểu lầm". Đúng ra phải là "em giới thiệu chưa đủ rõ".

**(f) Hứa ngừng bán.** `Em sẽ không hỏi hay gợi ý thêm nữa.` Khách phàn nàn *nhịp hỏi*, không yêu cầu *ngừng tư vấn*. Câu này tự huỷ phiên bán hàng — và ngay sau đó bot vẫn hỏi tiếp (T9, T11, T13, T15), tức là **vừa hứa sai vừa phá lời hứa**.

**(g) Từ vựng sai.** `bực bần` không phải từ tiếng Việt. `trị liệu` lọt qua vì prompt chỉ cấm `điều trị` (`aci_v2_system_prompt.md:34`).

---

## 4. Vì sao "càng sửa càng máy móc"

Đây là phần quan trọng để không lặp lại sai lầm.

Mỗi khi có phản hồi xấu, phản xạ của hệ thống hiện tại là **thêm một lớp chặn**:

```
khách phàn nàn bot không bám triệu chứng
   → thêm buildSymptomAcknowledgementClause()   (Java viết câu)
khách phàn nàn bot hỏi nhiều
   → thêm luật "? = 0" + gỡ hết skill
khách phàn nàn bot nói vòng vo
   → thêm regex phát hiện "vòng vo" + thêm template trả lời
khách phàn nàn bot thảo mai
   → cấm mọi câu cảm thán
```

Mỗi lớp chặn là một câu cứng mới. Sau 42 migration, bot được ghép từ vài trăm câu cứng, và **không còn chỗ cho ngôn ngữ tự nhiên**. Đây là vòng lặp whack-a-mole: mỗi lần vá lại làm bề mặt cứng hơn.

Song song, **bộ đo đang đo sai thứ**. Báo cáo `ACI_V3_CONSULTATIVE_QUALITY_UPGRADE_REPORT.md` cho 87.9/100 với 7 tiêu chí:

1. Trả lời trực tiếp
2. Độ chính xác giá/catalog
3. Nhận diện ý định & phản ứng tiêu cực
4. Xử lý phản hồi tiêu cực / xin lỗi
5. Handoff sức khỏe
6. Luồng đối thoại & CTA
7. Không rỉ thông tin nội bộ

Đây gần như toàn bộ là **tiêu chí tuân thủ**. Không có tiêu chí nào đo:

- câu trả lời có lặp lại câu cũ không,
- có hỏi lại câu đã hỏi không,
- có đề xuất được một gói cụ thể không,
- có mời chốt không,
- có sửa đúng lỗi không,
- khách có phải nhắc lại yêu cầu lần thứ hai không.

Vì vậy bộ eval cho điểm cao trong khi transcript thật là một thảm hoạ. **Bạn đã tối ưu đúng thứ bạn đo, và thứ bạn đo không phải thứ khách quan tâm.**

Thêm một chi tiết về observability: `computeSanitizerMutationRate` (`orchestrator:7185`) đo bằng **common prefix + common suffix**, không phải edit distance. Chỉ cần đổi một chữ ở đầu câu là tỉ lệ nhảy lên ~0.9. Vì vậy con số `sanitizerMutationRate=0.807` **không có nghĩa là 80% nội dung bị viết lại**. Đừng dùng nó làm chỉ số chất lượng.

---

## 5. Cách khắc phục

Chia 4 đợt. P0 để cắt cơn trong 48 giờ, P1 để có động cơ bán hàng, P2 để có giọng người, P3 để hạ tầng không phá chất lượng.

### 5.0. Nguyên tắc: tách 4 lớp, chỉ một lớp được "cứng"

| Lớp | Nhiệm vụ | Được phép cứng? |
|---|---|---|
| **Perception** | Trích fact: triệu chứng, vùng, ngân sách, chi nhánh, tín hiệu mua | Có (regex + LLM, có kiểm chứng) |
| **Strategy** | Chọn **nước đi** trong thang bậc bán hàng | **Có — deterministic, nhỏ, có test** |
| **Expression** | Viết câu | **Không. Chỉ LLM + ví dụ giọng thật** |
| **Guard** | Chặn sai sự thật / mất an toàn / lộ dữ liệu | Có, nhưng **không được viết câu thay khách** |

Nguyên tắc bất di bất dịch: **guard được phép nói "sai", không được phép nói "đây là câu đúng".** Mọi hình phạt phải quay về LLM kèm chỉ dẫn, không thay bằng template.

### 5.1. P0 — 48 giờ: tắt cái máy đọc thuộc

**P0-1. Xoá 3 hàm template trong referee.**
`buildSymptomAcknowledgementClause`, `buildSymptomAnchoredDiscoveryQuestion`, `buildHonestCorrectionApologyText` (`CleanAgentOutputRefereeAndSanitizer.java:6570–6595`) — bỏ lời gọi ở `:6478–6544`. Thay bằng validator:

```java
// thay vì chèn câu, chỉ đánh dấu vi phạm
if (!replyMentionsAnyCustomerContent(finalBubbles, customerMessage)) {
    violations.add("NO_CUSTOMER_SPECIFIC_REFERENCE");
}
```

Repair prompt chỉ dẫn: *"Câu trả lời chưa dùng lại từ nào của khách. Viết lại, mở bằng cách ghi nhận đúng điều khách vừa nói, dùng nguyên văn từ của khách."*

**P0-2. Bỏ `good_example` khỏi prompt.**
`CleanAgentCatalogAndPromptBuilder.java:2490`. Thay bằng khối `<VOICE>` ngắn:

```text
<VOICE>
- 1–3 bong bóng, mỗi bong bóng ≤ 45 từ.
- Mở bằng cách ghi nhận CỤ THỂ điều khách vừa nói (dùng lại từ của khách).
- CẤM mở bằng: "Em hiểu", "Em ghi nhận", "Dạ em xin lỗi", "Em rất tiếc".
- Mỗi lượt phải có ít nhất 1 trong 2 thứ: (a) một thông tin mới, (b) một bước tiến (đề xuất / giữ chỗ / mời chọn).
- Tối đa 1 câu hỏi. Chỉ hỏi khi thiếu thông tin mà không hỏi thì không thể tiến bước tiếp theo.
- Không lặp lại bất kỳ câu nào đã nói trong 3 lượt gần nhất.
</VOICE>
```

**P0-3. Chặn lặp ở cấp câu, không phải cấp bong bóng.**
`detectCleanCrossTurnRepetition` hiện chỉ bắt khi **≥90% cả bong bóng hoặc cả lượt** trùng (`:4033`). Đổi thành: tách câu, so từng câu với 3 lượt bot gần nhất, Jaccard ≥ 0.85 và ≥ 6 từ → vi phạm `REPEATED_SENTENCE`.

**P0-4. Bật hình phạt lặp ở tầng sampling.**
Hiện chỉ gửi `temperature` và `top_p` (`OpenAiCompatibleLlmClient.java:283–306`). Thêm:

```java
body.put("frequency_penalty", 0.5);
body.put("presence_penalty", 0.3);
```

Và nâng `temperature` 0.35 → **0.65**. Nhiệt độ 0.35 + bài mẫu + history chứa câu cũ = công thức của sự lặp.

**P0-5. Bỏ nhánh "phàn nàn → FAST_NO_THINK".**
`orchestrator:6823–6828`. Đổi thành `OBJECTION_REFRAME_THINK` (hoặc DEEP). Lượt khó phải được nghĩ.

**P0-6. Sửa tách triệu chứng.**
`splitAtomicHealthExpressions`: tách thêm theo **khoảng trắng + từ điển triệu chứng**, và chuẩn hoá theo concept:

```text
hoa mắt / chóng mặt / choáng váng  → concept:dizziness
ù tai / ù tai kéo dài            → concept:tinnitus
mệt mỏi / mệt / uể oải           → concept:fatigue
```

Dedupe theo concept, tối đa 4, và **không đọc lại toàn bộ danh sách** — chỉ nhắc 1–2 cái liên quan nhất.

**P0-7. Bổ sung từ điển triệu chứng vào `persona_config`.**
Thêm `hoa mắt, chóng mặt, choáng váng, ù tai, buồn nôn, tê tay, tê chân, mất thăng bằng`.

**P0-8. Sửa khung giá để không tạo bẫy "chỉ có 2 gói".**
Đổi prompt I.3(A):

> *"Bubble 2: nêu 1–2 gói tiêu biểu, **mở đầu bằng 'bên em có nhiều gói, đây là hai gói tiêu biểu'**, kèm giá và thời lượng."*

**P0-9. Cấm 2 câu và sửa mẫu xin lỗi.**

- Cấm: `Em sẽ không hỏi hay gợi ý thêm nữa` → thay bằng `Em dừng hỏi để mình đỡ mệt; khi nào mình cần cứ nói em`.
- Cấm: `khiến mình hiểu lầm` → mẫu đúng: `Em xin lỗi vì đã giới thiệu chưa đủ rõ`.
- Cấm: `trị liệu` (chỉ dùng "chăm sóc", "thư giãn", "phục hồi").

### 5.2. P1 — 1–2 tuần: lắp động cơ bán hàng

**P1-1. Thay 10 "strategy" bằng state machine 7 trạng thái.**

```
NEW → CONNECTED → DIAGNOSED → RECOMMENDED → OBJECTION → READY → BOOKED
                                        ↘ LOST
```

| Trạng thái | Nước đi bắt buộc | Cấm |
|---|---|---|
| NEW | Chào ngắn + 1 câu qualify (nhu cầu **hoặc** chi nhánh) | Dump bảng giá; quá 2 bong bóng |
| CONNECTED | Ghi nhận cụ thể + 1 câu chẩn đoán (thời gian / mức độ / vùng) | Hỏi lại điều đã biết |
| DIAGNOSED | Đề xuất **đúng 1 gói** + giá + lý do gắn triệu chứng + 1 câu chọn | Đưa 2 gói không cùng nhóm |
| RECOMMENDED | Giá trị + **soft close** (2 lựa chọn thời gian) trong 1–2 lượt | Hỏi thêm nhu cầu |
| OBJECTION | Thang: làm rõ → reframe → phương án nhẹ hơn → tạm dừng | Lặp lại bậc đã dùng |
| READY | Chốt chi nhánh + giờ + SĐT | Bán thêm |
| BOOKED | Xác nhận, không hỏi lại | Hỏi lại giờ |

State do **Java** giữ, không do LLM. LLM chỉ nhận `state` + `move` và viết câu.

**P1-2. Luật nhịp (quan trọng nhất).**

- Tối đa **1 câu hỏi / 2 lượt**.
- **2 lượt liên tiếp có câu hỏi → lượt thứ 3 bắt buộc "lượt cho giá trị"**: trả lời, đề xuất, hoặc chốt; **0 dấu `?`**.
- Sau **2 lượt** không nhắc tới gói/giá → lượt tiếp phải nhắc.
- Ít nhất **1 lần mời chốt** trước lượt thứ 6 nếu khách đã có bất kỳ tín hiệu quan tâm nào (hỏi giá, hỏi gói, hỏi khác biệt).

**P1-3. Nghi thức sửa sai 3 bước, tối đa 1 lượt.**

```
Bước 1 (1 câu): nhận đúng lỗi cụ thể, sở hữu, không điều kiện.
Bước 2 (1 câu): sửa thông tin / đưa thông tin đúng.
Bước 3 (1 câu): đề xuất bước tiếp theo (gói + giờ), hoặc im lặng tôn trọng nếu khách yêu cầu dừng.
```

Sau lượt đó **bắt buộc thoát `SERVICE_RECOVERY`**, quay về state trước đó. Thêm biến `recoveryTurnUsed`.

**P1-4. Goal không được bị cảm xúc chiếm.**
`AgentBrainService:2033`: chỉ cho đổi `conversation_goal` khi có **fact mới về nhu cầu** (`need_scope`, `self_reported_symptom`, `available_time`, `preferred_branch`). Cấm tuyệt đối các goal dạng "dừng bán / dừng hỏi / giải toả áp lực". Nếu model đề xuất, map về `EXPLORE_SERVICES` + ghi `repair_note`.

**P1-5. Mở khoá chốt.**
Cho phép skill đặt lịch từ state `RECOMMENDED`, không chỉ `READY_TO_BOOK`. Thay điều kiện `:6678` bằng:

```java
if (!canAttemptClose && isBookingOrSchedulingSkillCode(skCode)) break;
// canAttemptClose = state >= RECOMMENDED || buyingSignal >= WEAK
```

**P1-6. Dùng ưu đãi thật làm đòn chốt.**
KB có `Ưu đãi mua liệu trình từ 5 buổi — giảm 10%`. Bot chưa một lần nhắc. Thêm luật: khi khách do dự giá **hoặc** hỏi gói lần 2, được phép nêu ưu đãi kèm phép tính mỗi buổi — đây là lý do để chốt mà không phá giá.

**P1-7. Hỏi chi nhánh ở lượt 2–3.**
Giá HN/SG khác nhau (1.270.000đ là SG). Không hỏi chi nhánh = báo giá có thể sai + mất một bước tiến tự nhiên của sale.

### 5.3. P2 — 3–4 tuần: lấy lại giọng người

**P2-1. Thu 100–300 hội thoại thật của nhân viên sale Nàng Sen.**
Đây là tài sản ROI cao nhất và hiện đang bằng 0 (`sample_dialogues = 0`). Chọn 30 đoạn "giọng vàng" theo 6 tình huống: chào, khám phá, báo giá, chê đắt, so sánh, chốt. Đưa vào `sample_dialogues` và dùng làm few-shot.

**P2-2. Viết `style_guide` thay cho tính từ.**
`tone_style` hiện là *"Dịu dàng, tận tâm, thấu cảm sâu sắc và tạo cảm giác an tâm tuyệt đối"* — tính từ, không dạy được gì. Thay bằng 10 luật cụ thể:

```text
- Câu 8–20 từ. Tối đa 2 câu/bong bóng.
- Dùng "mình" cho khách, "em" cho bot. Không dùng "quý khách".
- 1 câu cảm thán tối đa mỗi lượt; không dùng emoji quá 1 lần/2 lượt.
- Mở bằng từ của khách. Không mở bằng "Dạ".
- Luôn có quan điểm: "với tình trạng này em nghiêng về gói X".
- Không hỏi "mình thấy thế nào" — hỏi "mình muốn A hay B".
- Khi khách do dự: nêu 1 lựa chọn nhẹ hơn + 1 ưu đãi thật, không ép.
- Khi khách bực: nhận lỗi 1 câu, sửa 1 câu, đề xuất 1 câu. Không xin lỗi 2 lần.
- Không dùng: "Em hiểu", "Em ghi nhận", "trị liệu", "giải pháp toàn diện".
- Kết thúc bằng hành động, không bằng lời cảm ơn.
```

**P2-3. Tách 2 pass cho lượt quan trọng.**
Hiện 1 lần gọi phải làm 3 việc. Đổi thành:

- **Pass 1 — Planner** (JSON nhỏ, ~200 token): `state`, `move`, `facts_used[]`, `offer`, `question` (hoặc null). Không viết câu.
- **Pass 2 — Writer**: nhận plan + 3 ví dụ giọng + 10 luật voice. Không nhận 14.5k luật.

Referee kiểm plan trước khi viết: nếu `move` mâu thuẫn `state` → trả lại planner. Đây là chỗ đặt "chiến lược" thật.

**P2-4. Giảm prompt từ 14.5k → ~4k ký tự.**
Chuyển các luật "cấm X" thành validator trong code. Prompt chỉ giữ: vai, 10 luật voice, thang bậc, 3 ví dụ. Mỗi luật rời khỏi prompt là một cơ hội để model nói tự nhiên.

**P2-5. Tóm tắt lịch sử.**
Hiện nhét nguyên văn 8 lượt bot cũ (tự copy). Đổi: 6 lượt gần nhất nguyên văn + tóm tắt 1 dòng cho phần còn lại, và **không đưa nguyên văn câu mở đầu của bot vào history** (hoặc đánh dấu "đã dùng").

### 5.4. P3 — song song: hạ tầng không phá chất lượng

**P3-1. Embedding thật.** `doc-service-py` đã có `sentence-transformers`. Hoặc tối thiểu: tắt nhánh vector, dùng BM25 + cross-encoder. Stub hash hiện tại chỉ làm nhiễu RRF.

**P3-2. Retrieval có lọc.** Đặt `candidate_k` theo ngưỡng similarity, không lấy hết 65 chunk. Với corpus nhỏ, dùng BM25 + rerank là đủ.

**P3-3. Ngân sách latency 8 giây.** Hiện trung bình ~10.9s, cao nhất 31.6s. Mục tiêu: TTFB < 3s (stream bong bóng 1), full < 8s. Cắt prompt, chạy retrieval song song, dùng model nhỏ cho lượt đơn giản, đặt hard timeout 12s với fallback đã chuẩn bị sẵn (không phải fallback 25s).

**P3-4. Telemetry mới.** Bỏ `sanitizerMutationRate` khỏi dashboard chất lượng (nó đo prefix/suffix, gây hiểu nhầm). Thay bằng:

| Chỉ số | Định nghĩa | Mục tiêu |
|---|---|---|
| `repeat_sentence_rate` | % lượt có câu trùng 3 lượt trước | < 5% |
| `canned_phrase_rate` | % lượt chứa câu template | < 2% |
| `question_rate` | % lượt kết bằng `?` | 30–45% |
| `value_density` | % lượt có thông tin mới | > 80% |
| `close_attempt_rate` | % hội thoại có ≥1 lời mời chốt | > 90% |
| `recovery_resume_rate` | sau phàn nàn, lượt sau có đề xuất | > 80% |
| `ttfb_p95` | thời gian tới bong bóng đầu | < 3s |

**P3-5. Eval mới.** Giữ 12 scenario cũ, thêm 8 scenario đúng những chỗ đã vỡ:

1. `haizzz` + câu hỏi sản phẩm (T13)
2. triệu chứng không có vùng cơ thể (T3)
3. phàn nàn rồi hỏi tiếp (T15)
4. khách hỏi "khác gì nhau" 2 lần
5. khách do dự giá 2 lần
6. hội thoại 8 lượt không có tín hiệu mua — **phải có ít nhất 1 lời mời chốt**
7. khách hỏi "còn gói nào khác" — phải trả lời trong 1 bong bóng, không xin lỗi
8. khách nói "mệt" — không được hỏi thêm

Chấm bằng **hành vi**, không bằng LLM-judge: đếm số câu hỏi, số câu lặp, số lần nhắc gói, số lần mời chốt.

---

## 6. Đối thoại mục tiêu — cùng 8 lượt đó

Đây là thứ một sale thật sẽ nói. Không phải để copy, mà để làm tiêu chuẩn.

**T1 — `giá`**
> Dạ bên em có mấy nhóm: dưỡng sinh gội đầu 60' 239k, massage 60' 399k, và gói phục hồi 100' 580k. Mình đang mỏi nhiều ở đâu, hay muốn thư giãn cả người ạ?

*(nêu dải giá, không tạo bẫy "chỉ 2 gói", hỏi đúng 1 câu)*

**T3 — `hoa mắt ù tai mệt mỏi`**
> Hoa mắt kèm ù tai rồi mệt cả người thì khó chịu lắm ạ. Mình bị mấy hôm nay rồi, hay lâu lâu mới lại ạ?

*(ghi nhận đủ 3 triệu chứng, hỏi thời gian — thông tin thật sự cần để tư vấn an toàn)*

**T5 — `Nó khác j nhau`**
> Khác ở phạm vi ạ: 239k là dưỡng sinh, gội đầu + cổ vai gáy, hợp khi mỏi vùng đầu cổ. 399k là massage toàn thân 60', hợp khi mệt rũ cả người. Với hoa mắt ù tai thì em nghiêng về 399k hơn, vì được thả lỏng toàn thân chứ không chỉ vùng cổ.

*(trả lời + có quan điểm + không hỏi)*

**T7 — `Hỏi lại lắm thế`**
> Dạ em hỏi hơi nhiều thật, em xin lỗi ạ. Em chốt lại luôn: gói 399k 60' toàn thân là hợp nhất với mình. Mình muốn em giữ chỗ chiều nay hay sáng mai ạ?

*(nhận lỗi 1 câu + giá trị + mời chốt)*

**T9 — `2 gói khác j nhau` (khách hỏi lại)**
> Dạ em nói gọn lại: 239k = gội đầu + cổ vai gáy; 399k = toàn thân 60'. Mình đang mệt cả người nên em vẫn nghiêng 399k. Nếu mình muốn thử nhẹ trước thì 239k cũng ổn ạ.

*(không hỏi, đưa lựa chọn)*

**T11 — khách nổi**
> Dạ em hiểu, em hỏi dồn làm mình mệt thêm. Gói toàn thân chăm cả người, không bỏ vùng nào — em không hỏi thêm nữa. Mình muốn em giữ chỗ lúc nào ạ?

*(không phòng thủ sản phẩm, không hỏi vùng, đề xuất)*

**T13 — `còn gói nào khác`**
> Dạ còn 2 gói: dưỡng sinh cao cấp 80' 339k (thêm chăm sóc da đầu) và phục hồi 100' 580k (ngâm bồn + xông hơi + massage sâu). Mình mệt nhiều thì 580k đáng thử nhất; đi 5 buổi được giảm 10% ạ.

*(trả lời + đề xuất + đòn ưu đãi)*

**T15 — phàn nàn gây hiểu lầm**
> Dạ em xin lỗi vì giới thiệu chưa đủ rõ — bên em có 6 gói, lúc đầu em chỉ nêu 2 gói tiêu biểu nên mình dễ hiểu là chỉ có 2 ạ. Để em gửi mình 3 gói hợp với hoa mắt/ù tai/mệt mỏi, mình xem gói nào rồi em giữ chỗ nhé.

*(sở hữu lỗi, sửa thông tin, tiến bước)*

---

## 7. Lộ trình 30 ngày

| Tuần | Việc | Kết quả đo được |
|---|---|---|
| **Tuần 1** | P0-1 → P0-9 | `repeat_sentence_rate` < 10%; 0 câu template Java; `question_rate` < 60% |
| **Tuần 2** | P1-1 → P1-4 (state machine + luật nhịp + recovery 1 lượt + goal) | `recovery_resume_rate` > 80%; 0 phiên bị kẹt `SERVICE_RECOVERY` |
| **Tuần 3** | P1-5 → P1-7 (mở khoá chốt + ưu đãi + chi nhánh), P2-1 (thu dữ liệu) | `close_attempt_rate` > 80%; có ≥ 1 lời mời chốt/phiên |
| **Tuần 4** | P2-2 → P2-5 (voice + 2-pass + prompt diet), P3-1 → P3-5 | `canned_phrase_rate` < 2%; `ttfb_p95` < 3s; eval mới pass |

**KPI cuối cùng nên theo dõi** (không phải điểm rubric):

1. Tỉ lệ hội thoại có ít nhất 1 lời mời đặt lịch.
2. Tỉ lệ khách trả lời sau lời mời (reply rate).
3. Số lượt trung bình để tới lời mời chốt đầu tiên.
4. Tỉ lệ khách phải nhắc lại cùng một yêu cầu.
5. Tỉ lệ hội thoại có câu lặp.
6. Tỉ lệ chuyển người thật.

---

## 8. Phụ lục — checklist file cần sửa

| Ưu tiên | File | Dòng / vùng | Việc |
|---|---|---|---|
| P0 | `CleanAgentOutputRefereeAndSanitizer.java` | 6478–6544, 6570–6595 | Bỏ chèn template; đổi sang validator |
| P0 | `CleanAgentCatalogAndPromptBuilder.java` | 2485–2492 | Bỏ `good_example`, thêm `<VOICE>` |
| P0 | `CleanAgentCatalogAndPromptBuilder.java` | 2301–2490 | Xoá kho câu mẫu cứng |
| P0 | `CleanAgentOutputRefereeAndSanitizer.java` | 4008–4090 | Chặn lặp ở cấp câu |
| P0 | `OpenAiCompatibleLlmClient.java` | 283–306 | Thêm frequency/presence penalty |
| P0 | `CleanAgentOrchestratorService.java` | 6823–6828 | Bỏ nhánh complaint → FAST |
| P0 | `AdaptiveConversationIntelligence.java` | 1559–1585 | Tách triệu chứng theo concept |
| P0 | `knowledge_pages.persona_config` | `symptom_keywords` | Thêm hoa mắt/ù tai/chóng mặt |
| P0 | `config/aci_v2_system_prompt.md` | 9, 25 | Framing "nhiều gói"; cấm 2 câu |
| P1 | `AgentBrainService.java` | 2033–2041 | Goal không bị cảm xúc chiếm |
| P1 | `CleanAgentOrchestratorService.java` | 6659–6680 | Mở khoá closing skill |
| P1 | `CleanAgentOrchestratorService.java` | 3886–3896 | Giữ 1 skill bán hàng khi recovery |
| P1 | `AdaptiveConversationIntelligence.java` | 5786–5984 | Thay if/else bằng state machine |
| P2 | `V43__...sql` (mới) | — | Seed 30 sample dialogue thật + style guide |
| P2 | `config/aci_v2_system_prompt.md` | toàn bộ | Giảm 14.5k → ~4k |
| P3 | `StubEmbeddingClient` / `RemoteEmbeddingClient` | — | Bật embedding thật hoặc tắt nhánh vector |
| P3 | `deploy` / LLM timeout | — | Hard timeout 12s, fallback chuẩn bị sẵn |

---

## 9. Kết luận

Bot không "ngu". Nó đang làm rất đúng một việc sai: **tuân thủ một bản đặc tả 14.500 ký tự, trong 500 token, không được suy nghĩ, với một bài giải mẫu trong prompt và một tầng guard viết hộ câu.**

Ba câu hỏi để tự kiểm tra trước khi thêm bất kỳ luật nào nữa:

1. Luật này dạy bot **nói gì**, hay chỉ dạy bot **không được nói gì**?
2. Nếu bỏ luật này, bot có nói dở hơn không — hay chỉ nói khác đi?
3. Thứ tôi đang đo có phải thứ khách cảm nhận không?

Nếu câu trả lời cho (3) là "không", thì mọi điểm số 87.9/100 đều vô nghĩa.

---

*Phân tích dựa trên transcript thật trong Postgres và mã nguồn tại HEAD `1cd09e4`. Các con số latency/token trích từ log runtime 19:28–19:45 ngày 2026-11-04.*
