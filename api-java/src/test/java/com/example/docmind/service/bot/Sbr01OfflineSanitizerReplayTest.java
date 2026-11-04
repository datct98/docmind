package com.example.docmind.service.bot;

import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.FanpageBotResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SBR-01 Batch 1A — offline (0 LLM call) replay of the CURRENT sanitizer code over the 279 stored baseline turns.
 *
 * <p>Input per turn comes from the stored baseline telemetry ({@code benchmark/sbr01/results/cases}). Exact
 * full-pipeline replay is not possible offline (the orchestrator needs live reflection/booking/catalog state), so
 * each FIX replays only the methods it changes, starting from the stored Java-gate input recorded by the harness
 * ({@code javaBubbleMutations[].beforeBubbles}). Turns without a recorded gate input are replayed from the stored
 * final output to prove the new code is a no-op there (idempotence check). Limitations are written into the report.</p>
 */
class Sbr01OfflineSanitizerReplayTest {

    private static final String FINAL_GATE_CODE = "FINAL_GATE_APOLOGY_OR_UNSOLICITED_PITCH_OR_COLON_MERGE";
    private static final Pattern JAVA_TEMPLATE_PATTERN = Pattern.compile(
            "(?iU)(ghi\\s+nhận\\s+đúng\\s+là|muốn\\s+được\\s+chăm\\s+sóc\\s+ưu\\s+tiên\\s+ở\\s+phần\\s+nào|"
                    + "khó\\s+chịu\\s+nhất\\s+ở\\s+điểm\\s+nào\\s+để)");
    private static final Set<String> VI_STOPWORDS = Set.of(
            "minh", "em", "anh", "chi", "a", "da", "la", "co", "khong", "k", "thi", "ma", "va", "cho", "cua", "nay",
            "the", "nhe", "nha", "voi", "o", "de", "duoc", "con", "cac", "nhung", "mot", "nao", "gi", "j", "lam", "roi");

    private final ObjectMapper om = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        return "api-java".equalsIgnoreCase(String.valueOf(cwd.getFileName())) ? cwd.getParent() : cwd;
    }

    record StoredTurn(String caseId, int runIndex, String turnId, String customerMessage,
                      List<String> priorAssistantTexts, List<String> rawBubbles, List<String> postRefereeBubbles,
                      List<String> oldFinalBubbles, JsonNode mutations) {
    }

    private List<StoredTurn> loadStoredTurns() throws Exception {
        Path casesDir = repoRoot().resolve("benchmark/sbr01/results/cases");
        List<Path> files;
        try (var s = Files.list(casesDir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith("_replay_telemetry.json")).sorted().toList();
        }
        List<StoredTurn> turns = new ArrayList<>();
        for (Path f : files) {
            JsonNode root = om.readTree(Files.readString(f, StandardCharsets.UTF_8));
            String caseId = root.path("caseId").asString();
            for (JsonNode run : root.path("runs")) {
                for (JsonNode t : run.path("turns")) {
                    List<String> prior = new ArrayList<>();
                    for (JsonNode h : t.path("inputAndContext").path("conversationHistory")) {
                        String role = h.path("role").asString();
                        if ("assistant".equalsIgnoreCase(role) || "bot".equalsIgnoreCase(role)) {
                            prior.add(h.path("content").asString());
                        }
                    }
                    JsonNode op = t.path("outputPipeline");
                    turns.add(new StoredTurn(caseId, run.path("runIndex").asInt(), t.path("turnId").asString(),
                            t.path("customerMessage").asString(), prior,
                            texts(op.path("raw_llm_output").path("bubbles")),
                            texts(op.path("post_referee_output").path("bubbles")),
                            texts(op.path("final_sent_output").path("bubbles")),
                            op.path("javaBubbleMutations")));
                }
            }
        }
        return turns;
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(n.asString());
        }
        return out;
    }

    private static List<FanpageBotResponse.ReplyBubble> bubbles(List<String> texts) {
        return texts.stream().map(t -> new FanpageBotResponse.ReplyBubble(t, 0)).toList();
    }

    private static List<String> texts(List<FanpageBotResponse.ReplyBubble> bubbles) {
        return bubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList();
    }

    private PagePersonaConfig loadPersona() throws Exception {
        JsonNode snap = om.readTree(Files.readString(
                repoRoot().resolve("benchmark/sbr01/fixtures/sbr01_kb_catalog_skills_snapshot.json"), StandardCharsets.UTF_8));
        JsonNode pc = snap.path("knowledgePages").get(0).path("persona_config");
        return om.readValue(pc.toString(), PagePersonaConfig.class);
    }

    static String fold(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replace('đ', 'd');
    }

    /** Lexical relevance: share of distinct content syllables of the customer message that appear in the reply. */
    static double customerRelevance(String customerMessage, String reply) {
        Set<String> c = new LinkedHashSet<>();
        for (String t : fold(customerMessage).split("[^a-z0-9]+")) {
            if (t.length() >= 2 && !VI_STOPWORDS.contains(t)) {
                c.add(t);
            }
        }
        if (c.isEmpty()) {
            return 1.0;
        }
        Set<String> r = new LinkedHashSet<>(List.of(fold(reply).split("[^a-z0-9]+")));
        return (double) c.stream().filter(r::contains).count() / c.size();
    }

    // ================================================================================================
    // FIX-1: remove Java-generated conversation templates
    // ================================================================================================

    @Test
    @EnabledIfSystemProperty(named = "sbr01.offlineReplay", matches = "true")
    @DisplayName("SBR-01 FIX-1: offline replay of grounded-apology + strip-pitch final gate over 279 stored turns")
    void offlineReplayFix1() throws Exception {
        PagePersonaConfig persona = loadPersona();
        List<String> taxonomy = persona.resolvedScopeTaxonomy();
        List<StoredTurn> turns = loadStoredTurns();
        assertThat(turns).hasSize(279);

        int withGateInput = 0, idempotenceChecked = 0, idempotenceMismatch = 0, changed = 0;
        int oldTemplateTurns = 0, newTemplateTurns = 0, relevanceDegraded = 0, relevanceBelowOldFinal = 0;
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        List<Map<String, Object>> changedTurns = new ArrayList<>();
        List<Map<String, Object>> idempotenceMismatches = new ArrayList<>();

        for (StoredTurn t : turns) {
            List<String> gateInput = null;
            for (JsonNode m : t.mutations()) {
                if (FINAL_GATE_CODE.equals(m.path("reasonOrViolationCode").asString())) {
                    gateInput = texts(m.path("beforeBubbles"));
                }
            }
            boolean hasGateInput = gateInput != null;
            List<String> input = hasGateInput ? gateInput : t.oldFinalBubbles();
            List<FanpageBotResponse.ReplyBubble> afterApology =
                    CleanAgentOutputRefereeAndSanitizer.enforceGroundedApologyAgainstHistoryInPlace(
                            bubbles(input), t.priorAssistantTexts(), List.of(), t.customerMessage(), "em", "mình");
            List<String> newFinal = texts(CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                    afterApology, t.customerMessage(), List.of(), taxonomy, "em", "mình"));

            String oldJoined = String.join(" ", t.oldFinalBubbles());
            String newJoined = String.join(" ", newFinal);
            boolean oldTpl = JAVA_TEMPLATE_PATTERN.matcher(oldJoined).find();
            boolean newTpl = JAVA_TEMPLATE_PATTERN.matcher(newJoined).find();
            if (oldTpl) oldTemplateTurns++;
            if (newTpl) newTemplateTurns++;

            if (hasGateInput) {
                withGateInput++;
            } else {
                idempotenceChecked++;
                if (!newFinal.equals(t.oldFinalBubbles())) {
                    idempotenceMismatch++;
                    idempotenceMismatches.add(Map.of("caseId", t.caseId(), "runIndex", t.runIndex(), "turnId", t.turnId(),
                            "oldFinal", t.oldFinalBubbles(), "newFinal", newFinal));
                }
            }
            if (!newFinal.equals(t.oldFinalBubbles())) {
                changed++;
                String rawJoined = String.join(" ", t.rawBubbles());
                double relRaw = customerRelevance(t.customerMessage(), rawJoined);
                double relOld = customerRelevance(t.customerMessage(), oldJoined);
                double relNew = customerRelevance(t.customerMessage(), newJoined);
                if (relNew + 1e-9 < relRaw) {
                    relevanceDegraded++;
                }
                if (relNew + 1e-9 < relOld) {
                    relevanceBelowOldFinal++;
                }
                String oldMinusTemplate = normalizeSpaces(removeTemplateSentences(oldJoined));
                String category;
                if (oldMinusTemplate.equals(normalizeSpaces(newJoined))) {
                    category = "TEMPLATE_SENTENCES_REMOVED_ONLY";
                } else if (String.join(" ", gateInput != null ? gateInput : List.of()).matches("(?s).*\\b(?:[A-Z]{2}_)?[A-Z]{2}\\d{2}\\b.*")
                        && !oldJoined.matches("(?s).*\\b(?:[A-Z]{2}_)?[A-Z]{2}\\d{2}\\b.*")) {
                    category = "REPLAY_SCOPE_ARTEFACT_SKU_STRIP_NOT_REPLAYED";
                } else if (containsAnyApology(newJoined) && newFinal.size() >= 1 && !oldTpl) {
                    category = "SENTENCE_LEVEL_FALSE_APOLOGY_REWRITE";
                } else {
                    category = "TEMPLATE_REMOVED_PLUS_SENTENCE_LEVEL_PRESERVATION";
                }
                categoryCounts.merge(category, 1, Integer::sum);
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("caseId", t.caseId());
                rec.put("runIndex", t.runIndex());
                rec.put("turnId", t.turnId());
                rec.put("customerMessage", t.customerMessage());
                rec.put("raw_llm_output", t.rawBubbles());
                rec.put("post_referee_output", t.postRefereeBubbles());
                rec.put("final_gate_input_recorded", gateInput);
                rec.put("final_sent_output_old", t.oldFinalBubbles());
                rec.put("final_sent_output_new_offline", newFinal);
                rec.put("oldHasJavaTemplate", oldTpl);
                rec.put("newHasJavaTemplate", newTpl);
                rec.put("customerRelevanceRaw", round(relRaw));
                rec.put("customerRelevanceOldFinal", round(relOld));
                rec.put("customerRelevanceNewFinal", round(relNew));
                rec.put("diffCategory", category);
                changedTurns.add(rec);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fixId", "FIX-1");
        summary.put("gitHead", System.getProperty("sbr01.gitHead", "UNSPECIFIED"));
        summary.put("llmCallsMade", 0);
        summary.put("replayScope", "enforceGroundedApologyAgainstHistoryInPlace(6-arg) + stripUnsolicitedPackagePitchOnNegativeReactionInPlace");
        summary.put("totalStoredTurns", turns.size());
        summary.put("turnsWithRecordedFinalGateInput", withGateInput);
        summary.put("turnsReplayedFromOldFinalForIdempotence", idempotenceChecked);
        summary.put("idempotenceMismatches", idempotenceMismatch);
        summary.put("turnsWhoseFinalChanged", changed);
        summary.put("oldFinalTurnsWithJavaTemplate", oldTemplateTurns);
        summary.put("newFinalTurnsWithJavaTemplate", newTemplateTurns);
        summary.put("changedTurnsWhereRelevanceBelowRawLlm", relevanceDegraded);
        summary.put("changedTurnsWhereRelevanceBelowOldFinal_templateEchoRemoved", relevanceBelowOldFinal);
        summary.put("changedTurnDiffCategories", categoryCounts);
        summary.put("limitations", List.of(
                "Gate input is the harness reconstruction recorded in javaBubbleMutations.beforeBubbles (post style-gate/normalize/P37B2), not a live capture.",
                "Live pipeline also runs enforceGroundedApology at an earlier stage (orchestrator ~5361); offline replay applies it once on the recorded gate input.",
                "knownSymptoms/pronouns are not needed anymore by the changed code; scope taxonomy is read from the persona snapshot."));
        summary.put("changedTurns", changedTurns);
        summary.put("idempotenceMismatchTurns", idempotenceMismatches);

        Path outDir = repoRoot().resolve("benchmark/sbr01/results_fix1_offline");
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("sbr01_fix1_offline_replay.json"), om.writeValueAsString(summary), StandardCharsets.UTF_8);

        StringBuilder md = new StringBuilder();
        md.append("# SBR-01 FIX-1 — Offline replay report (generated)\n\n");
        md.append("- Git HEAD: `").append(summary.get("gitHead")).append("` — LLM calls: 0\n");
        md.append("- Stored turns: ").append(turns.size()).append("; with recorded final-gate input: ").append(withGateInput)
                .append("; replayed from old final (idempotence): ").append(idempotenceChecked)
                .append(" (mismatches: ").append(idempotenceMismatch).append(")\n");
        md.append("- Turns whose final changed: ").append(changed).append("\n");
        md.append("- Turns with Java template text — old final: ").append(oldTemplateTurns).append(", new final: ").append(newTemplateTurns).append("\n");
        md.append("- Changed turns where lexical relevance to the customer message is below raw LLM output: ").append(relevanceDegraded).append("\n");
        md.append("- Changed turns where relevance is below the OLD final (i.e. the removed Java template was the only echo of the customer's words): ")
                .append(relevanceBelowOldFinal).append(" — candidates for FIX-4 prompt wording, NOT a reason to restore Java templates\n");
        md.append("- Diff categories: ").append(categoryCounts).append("\n\n");
        md.append("| Case | Run | Turn | Category | Customer | Old final | New final (offline) | Rel raw/old/new |\n|---|---:|---|---|---|---|---|---|\n");
        for (Map<String, Object> r : changedTurns) {
            md.append("| ").append(r.get("caseId")).append(" | ").append(r.get("runIndex")).append(" | ").append(r.get("turnId"))
                    .append(" | ").append(r.get("diffCategory"))
                    .append(" | ").append(esc(r.get("customerMessage"))).append(" | ").append(esc(r.get("final_sent_output_old")))
                    .append(" | ").append(esc(r.get("final_sent_output_new_offline"))).append(" | ")
                    .append(r.get("customerRelevanceRaw")).append("/").append(r.get("customerRelevanceOldFinal")).append("/")
                    .append(r.get("customerRelevanceNewFinal")).append(" |\n");
        }
        md.append("\n## Limitations\n\n");
        for (Object l : (List<?>) summary.get("limitations")) {
            md.append("- ").append(l).append("\n");
        }
        Files.writeString(outDir.resolve("SBR01_FIX1_OFFLINE_REPLAY.md"), md.toString(), StandardCharsets.UTF_8);

        assertThat(newTemplateTurns).as("no Java-generated symptom/discovery template may remain in new final").isZero();
        assertThat(idempotenceMismatch).as("new code must not change turns where the old final gate made no change").isZero();
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String removeTemplateSentences(String text) {
        List<String> kept = new ArrayList<>();
        for (String s : text.split("(?<=[.!?？])\\s+")) {
            if (!JAVA_TEMPLATE_PATTERN.matcher(s).find()) {
                kept.add(s);
            }
        }
        return String.join(" ", kept);
    }

    private static String normalizeSpaces(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    private static boolean containsAnyApology(String s) {
        return s != null && s.toLowerCase(Locale.ROOT).contains("xin lỗi");
    }

    private static String esc(Object o) {
        return String.valueOf(o).replace("|", "\\|").replace("\n", " ");
    }
}
