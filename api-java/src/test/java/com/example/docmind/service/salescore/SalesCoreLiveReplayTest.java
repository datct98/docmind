package com.example.docmind.service.salescore;

import com.example.docmind.config.LlmProperties;
import com.example.docmind.service.llm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static com.example.docmind.service.salescore.SalesContracts.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in provider evaluation. Synthetic customers/catalog only; never calls inbox, DB or Facebook. */
@EnabledIfSystemProperty(named = "sales.live", matches = "true")
class SalesCoreLiveReplayTest {
    @Test void replayBothIndustriesThroughProductionCore() throws Exception {
        Map<String,String> env = new HashMap<>();
        // Compose mounts repository .env at /app/.env; application gives that file priority.
        for (Path path : List.of(Path.of(".env"), Path.of("../.env"))) if (Files.exists(path)) {
            for (String line : Files.readAllLines(path)) {
                int equals = line.indexOf('=');
                if (equals <= 0 || line.strip().startsWith("#")) continue;
                String value = line.substring(equals + 1).strip();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))))
                    value = value.substring(1, value.length()-1);
                env.put(line.substring(0, equals).strip(), value);
            }
        }
        env.putAll(System.getenv());
        assertThat(env.get("LLM_API_KEY")).as("Configure existing provider credential locally").isNotBlank();
        LlmProperties props = new LlmProperties();
        props.setProvider(env.getOrDefault("LLM_PROVIDER", "custom"));
        props.setBaseUrl(env.get("LLM_BASE_URL")); props.setApiKey(env.get("LLM_API_KEY")); props.setModel(env.get("LLM_MODEL"));
        props.setTimeoutSeconds(35);
        ObjectMapper json = new ObjectMapper();
        var primary = new OpenAiCompatibleLlmClient(props, json);
        var calls = Collections.synchronizedList(new ArrayList<Map<String,Object>>());
        LlmClient recorded = record(primary, calls);
        if (env.get("LLM_FALLBACK_API_KEY") != null && !env.get("LLM_FALLBACK_API_KEY").isBlank()) {
            var fallbackProps = new LlmProperties();
            fallbackProps.setProvider(env.getOrDefault("LLM_FALLBACK_PROVIDER", "custom"));
            fallbackProps.setBaseUrl(env.get("LLM_FALLBACK_BASE_URL"));
            fallbackProps.setApiKey(env.get("LLM_FALLBACK_API_KEY"));
            fallbackProps.setModel(env.get("LLM_FALLBACK_MODEL"));
            fallbackProps.setTimeoutSeconds(35);
            recorded = new ResilientFallbackLlmClient(recorded, record(new OpenAiCompatibleLlmClient(fallbackProps, json), calls));
        }
        Path output = Path.of("../artifacts/sales-core-live");
        Files.createDirectories(output);
        List<Map<String,Object>> turns = new ArrayList<>();
        String snapshotPath = System.getProperty("sales.snapshotInput", "");
        String singleInput = System.getProperty("sales.singleInput", "");
        String catalogKind = snapshotPath.isBlank() ? "SYNTHETIC_NOT_PRODUCTION" : "PAGE_SNAPSHOT_OFFLINE";
        String writerClient = System.getProperty("sales.writer", "PRIMARY");
        try (var core = new SalesTurnOrchestrator(recorded, new IndustryProfileRegistry(), 45000)) {
            core.setWriterClient(writerClient);
            core.setPlanningEnabled(true);
            core.setWriterReasoning(Boolean.parseBoolean(System.getProperty("sales.writerReasoning", "false")));
            if (!singleInput.isBlank()) {
                var input = json.readValue(Files.readString(Path.of(singleInput)), TurnInput.class);
                var result = Boolean.getBoolean("sales.paced") ? core.runPaced(input) : core.run(input);
                turns.add(Map.of("industry", input.industryCode(), "customer", input.message(), "result", result));
            } else if ("rental-v4".equals(System.getProperty("sales.scenario"))) {
                replay(core, "MOTORBIKE_RENTAL", List.of(new Evidence("rental-v4", "Synthetic business data; not a live rental company",
                        "Điểm nhận xe tại Hoàn Kiếm, Hà Nội. Honda Vision 220k/ngày; Wave 150k/ngày. Một ngày là 24 giờ. Cọc 1000k/xe, tách riêng tiền thuê. Có 2 mũ bảo hiểm mỗi xe. Khách được kiểm tra tình trạng xe trước khi nhận và ghi nhận hiện trạng vào biên bản bàn giao. Chưa có thông tin xe trống, giao nhận, giấy tờ bắt buộc hay hỗ trợ sự cố.")),
                        List.of("Giá thuê xe thế nào?", "Tôi đi hai người, thích xe ga, nhận ở Hoàn Kiếm. Thuê hai ngày.",
                                "Vision nhé. Bên kia rẻ hơn mà.", "Tôi không muốn đổi sang xe số. Chủ yếu sợ xe cũ hỏng giữa đường.",
                                "Thế tôi lấy Vision. Nhận thứ bảy 9h, trả thứ hai 9h, ngay Hoàn Kiếm.",
                                "Chưa cung cấp số điện thoại vội, tôi muốn hỏi thủ tục nhận xe trước."), turns, calls, json, output);
            } else if ("quality-v3".equals(System.getProperty("sales.scenario"))) {
                replay(core, "BEAUTY_SPA", List.of(new Evidence("spa-price", "Synthetic evaluation catalog",
                        "Hà Nội: Massage cơ bản toàn thân 60 phút 399k, gồm cổ vai gáy và tay chân. Dưỡng sinh cơ bản 60 phút 239k, gội đầu và cổ vai gáy, không gồm chân tay. Khách có thể trao đổi vùng muốn tập trung với kỹ thuật viên trước khi bắt đầu; chưa có dữ liệu lịch trống.")),
                        List.of("Mình ở Hà Nội, muốn thư giãn cả cổ vai gáy lẫn tay chân. Giá thế nào?",
                                "Mình không lo ngân sách, chỉ sợ làm qua loa",
                                "Lần trước vai gáy bị làm lướt qua, mình cần kỹ vùng đó",
                                "Vậy mình lấy gói toàn thân, mai lúc 18h, cần làm gì tiếp?"), turns, calls, json, output);
            } else if ("adaptive-v3".equals(System.getProperty("sales.scenario"))) {
                replay(core, "BEAUTY_SPA", List.of(new Evidence("spa-price", "Synthetic evaluation catalog",
                        "Hà Nội: Massage cơ bản toàn thân 60 phút 399k, gồm cổ vai gáy và tay chân. Dưỡng sinh cơ bản 60 phút 239k, gội đầu và cổ vai gáy, không gồm chân tay. Chưa có dữ liệu lịch trống.")),
                        List.of("Mình ở Hà Nội, muốn thư giãn cả cổ vai gáy lẫn tay chân. Tư vấn giá giúp mình.",
                                "Đắt", "Mình vẫn muốn làm đủ tay chân, đừng đổi sang gội đầu nhé", "Mình sẽ cân nhắc, đừng tư vấn thêm nữa"), turns, calls, json, output);
                replay(core, "MOTORBIKE_RENTAL", List.of(new Evidence("rental-price", "Synthetic evaluation catalog",
                        "Hà Nội: Honda Vision 220k/ngày; Wave 150k/ngày. Một ngày là 24 giờ. Cọc 1000k/xe, tách riêng tiền thuê. Mỗi xe kèm 2 mũ bảo hiểm. Chưa có dữ liệu tồn xe, phí giao nhận và phụ thu quá giờ.")),
                        List.of("Mình cần Vision ở Hà Nội, nhận sáng thứ bảy trả sáng thứ hai. Giá thế nào?",
                                "Bên kia báo rẻ hơn. Nhưng mình chỉ muốn Vision, đừng đổi sang xe số",
                                "Sửa lại nhé, mình nhận sáng chủ nhật, trả sáng thứ ba",
                                "Ok mình lấy Vision, nhận ở Hoàn Kiếm lúc 9h. Cần làm gì tiếp?"), turns, calls, json, output);
            } else if (!snapshotPath.isBlank()) {
                var template = json.readValue(Files.readString(Path.of(snapshotPath)), TurnInput.class);
                replay(core, template, "user-reported".equals(System.getProperty("sales.scenario"))
                        ? List.of("Giá", "tôi đau mỏi vai gáy nhức đầu khó ngủ", "HN", "Đắt")
                        : List.of("Giá", "cổ vai gáy chân tay", "tất cả", "Đắt", "HN", "Đắt"), turns, calls, json, output);
            } else {
            replay(core, "BEAUTY_SPA", List.of(
                    new Evidence("spa-price", "Synthetic evaluation catalog", "Hà Nội: Massage cơ bản toàn thân 60 phút 399k. Dưỡng sinh cơ bản 60 phút 239k, gội đầu và cổ vai gáy, không gồm chân tay. TP.HCM: Massage cơ bản toàn thân 60 phút 570k. Dưỡng sinh cơ bản 60 phút 260k, gội đầu và cổ vai gáy, không gồm chân tay.")),
                    List.of("Giá", "cổ vai gáy chân tay", "tất cả", "Đắt", "HN", "Đắt"), turns, calls, json, output);
            replay(core, "VEHICLE_RENTAL", List.of(
                    new Evidence("rental-price", "Synthetic evaluation catalog", "Hà Nội: Xe số 150k/ngày. Xe tay ga 220k/ngày. Một ngày tính 24 giờ. Tiền cọc 1000k/xe. Cả hai loại có 2 mũ bảo hiểm; chưa có dữ liệu tồn xe, phí giao nhận và phụ thu quá giờ.")),
                    List.of("Mình cần thuê xe đi hai người cuối tuần", "2 ngày, Hà Nội", "Xe tay ga nhé, giá sao?", "Đắt", "Có gì khoảng 150k/ngày không?", "Vậy lấy xe số. Mai 9h mình nhận, cần làm gì?"), turns, calls, json, output);
            }
        } finally {
            Files.writeString(output.resolve("replay.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("catalog", catalogKind, "modelConfigured", props.resolveModel(), "writerClient", writerClient, "turns", turns, "calls", calls)));
        }
        assertThat(turns).hasSize(!singleInput.isBlank() ? 1 : "rental-v4".equals(System.getProperty("sales.scenario")) ? 6 : "quality-v3".equals(System.getProperty("sales.scenario")) ? 4 : "adaptive-v3".equals(System.getProperty("sales.scenario")) ? 8 : snapshotPath.isBlank() ? 12 : "user-reported".equals(System.getProperty("sales.scenario")) ? 4 : 6);
        if ("rental-v4".equals(System.getProperty("sales.scenario"))) {
            assertThat(turns.stream().anyMatch(t -> ((Result)t.get("result")).handoff())).as("Ordinary consultation stays with the bot").isFalse();
            var first = (Result)turns.getFirst().get("result");
            assertThat(SalesDecisionValidator.monetaryNumbers(String.join(" ", first.texts()))).as("Unknown need/location cannot get an arbitrary quote").isEmpty();
            assertThat(first.attempts()).as("Discovery should avoid catalog/writer/reviewer calls").isEqualTo(1);
        }
        assertThat(turns.stream().filter(t -> ((Result)t.get("result")).fallback()).count()).as("No contract fallback in these rehearsals").isZero();
        assertThat(turns.stream().filter(t -> t.containsKey("continuation") && ((Result)t.get("continuation")).fallback()).count())
                .as("No failed continuation in these rehearsals").isZero();
    }
    private LlmClient record(OpenAiCompatibleLlmClient client, List<Map<String,Object>> calls) {
        return new LlmClient() {
            public String name() { return client.name(); }
            public String complete(String p) { return complete(p, ReasoningBudget.simple()); }
            public String complete(String p, ReasoningBudget b) {
                String raw = client.complete(p, b);
                var telemetry = client.getLastCallTelemetry();
                calls.add(Map.of("prompt", p, "raw", raw, "telemetry", telemetry == null ? Map.of() : telemetry));
                return raw;
            }
        };
    }
    private void replay(SalesTurnOrchestrator core, String industry, List<Evidence> evidence, List<String> customers,
                        List<Map<String,Object>> turns, List<Map<String,Object>> calls, ObjectMapper json, Path output) throws Exception {
        replay(core, new TurnInput("synthetic-" + industry, "Cửa hàng thử nghiệm", industry, "em", "mình", "tự nhiên, gọn",
                List.of(), "", evidence, List.of(), "", false), customers, turns, calls, json, output);
    }
    private void replay(SalesTurnOrchestrator core, TurnInput template, List<String> customers,
                        List<Map<String,Object>> turns, List<Map<String,Object>> calls, ObjectMapper json, Path output) throws Exception {
        List<Message> history = new ArrayList<>();
        SalesMemory.View memory = SalesMemory.View.empty();
        for (String message : customers) {
            memory = SalesMemory.advance(memory, message, false);
            var input = new TurnInput(template.pageId(), template.businessName(), template.industryCode(), template.selfPronoun(), template.customerPronoun(), template.voice(),
                    history, message, template.evidence(), template.businessPolicies(), "", template.contextTruncated(), memory);
            var result = Boolean.getBoolean("sales.paced") ? core.runPaced(input) : core.run(input);
            var turn = new LinkedHashMap<String,Object>();
            turn.put("industry", template.industryCode()); turn.put("customer", message); turn.put("result", result);
            turns.add(turn);
            if (result.reviewedMemory() != null) memory = result.reviewedMemory();
            turn.put("memory", memory);
            history.add(new Message("user", message)); result.texts().forEach(t -> history.add(new Message("assistant", t)));
            if (result.decision() != null && result.decision().followUp() != null) {
                var continuation = core.continueAfterQuote(input.withInteractionPlan(result.interactionPlan()).withMemory(memory), result.decision());
                turn.put("continuation", continuation);
                continuation.texts().forEach(t -> history.add(new Message("assistant", t)));
            }
            Files.writeString(output.resolve("replay.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("catalog", "SYNTHETIC_NOT_PRODUCTION", "turns", turns, "calls", calls)));
            System.out.println("LIVE_REPLAY " + template.industryCode() + " turn=" + (history.stream().filter(m -> m.role().equals("user")).count() - 1) + " fallback=" + result.fallback() + " ms=" + result.elapsedMs());
        }
    }
}
