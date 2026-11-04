package com.example.docmind.service.bot;

import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.FanpageBotRequest;
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
                      List<String> oldFinalBubbles, JsonNode mutations, List<FanpageBotRequest.ConversationTurn> history) {
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
                    List<FanpageBotRequest.ConversationTurn> hist = new ArrayList<>();
                    for (JsonNode h : t.path("inputAndContext").path("conversationHistory")) {
                        String role = h.path("role").asString();
                        hist.add(new FanpageBotRequest.ConversationTurn(role, h.path("content").asString()));
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
                            op.path("javaBubbleMutations"), hist));
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

    // ================================================================================================
    // FIX-3: remove forced punctuation mutation
    // ================================================================================================

    private static final Pattern TERMINAL_PUNCT = Pattern.compile("[.?!？]+$");

    /** Sentences whose text (without terminal punctuation) is identical before/after but whose terminal mark changed. */
    static List<String> punctuationOnlyMutations(List<String> before, List<String> after) {
        Map<String, String> beforeTerminal = new LinkedHashMap<>();
        for (String b : before) {
            for (String s : b.split("(?<=[.!?？])\\s+")) {
                String st = s.strip();
                if (st.isEmpty()) continue;
                String key = normalizeSpaces(TERMINAL_PUNCT.matcher(st).replaceAll("")).toLowerCase(Locale.ROOT);
                java.util.regex.Matcher tm = TERMINAL_PUNCT.matcher(st);
                beforeTerminal.put(key, tm.find() ? tm.group() : "");
            }
        }
        List<String> mutated = new ArrayList<>();
        for (String a : after) {
            for (String s : a.split("(?<=[.!?？])\\s+")) {
                String st = s.strip();
                if (st.isEmpty()) continue;
                String key = normalizeSpaces(TERMINAL_PUNCT.matcher(st).replaceAll("")).toLowerCase(Locale.ROOT);
                java.util.regex.Matcher tm = TERMINAL_PUNCT.matcher(st);
                String term = tm.find() ? tm.group() : "";
                if (beforeTerminal.containsKey(key) && !beforeTerminal.get(key).equals(term)) {
                    mutated.add(beforeTerminal.get(key) + "->" + term + " :: " + st);
                }
            }
        }
        return mutated;
    }

    @Test
    @EnabledIfSystemProperty(named = "sbr01.offlineReplay", matches = "true")
    @DisplayName("SBR-01 FIX-3: offline replay of capitalize/normalize step and P37B2 discipline over stored mutation inputs")
    void offlineReplayFix3() throws Exception {
        PagePersonaConfig persona = loadPersona();
        String careActor = persona.resolvedCareActor();
        List<StoredTurn> turns = loadStoredTurns();
        assertThat(turns).hasSize(279);

        int normalizeSteps = 0, normalizeOldPunct = 0, normalizeNewPunct = 0;
        int p37Steps = 0, p37OldPunct = 0, p37NewPunct = 0;
        List<Map<String, Object>> records = new ArrayList<>();
        for (StoredTurn t : turns) {
            List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();
            // rebuild history order-insensitively is not possible from assistant-only list; use stored conversationHistory
            history.addAll(t.history());
            for (JsonNode m : t.mutations()) {
                String cm = m.path("classAndMethod").asString();
                List<String> before = texts(m.path("beforeBubbles"));
                List<String> oldAfter = texts(m.path("afterBubbles"));
                List<String> oldPunct = punctuationOnlyMutations(before, oldAfter);
                List<String> newAfter;
                String step;
                if (cm.contains("normalizeDeclarativeQuestionMarks")) {
                    step = "NORMALIZE_DECLARATIVE_QUESTION_MARKS (now capitalizeFirstLetter only)";
                    normalizeSteps++;
                    newAfter = before.stream().map(x -> BubbleTextSanitizer.capitalizeFirstLetter(x.strip())).toList();
                    List<String> newPunct = punctuationOnlyMutations(before, newAfter);
                    normalizeOldPunct += oldPunct.isEmpty() ? 0 : 1;
                    normalizeNewPunct += newPunct.isEmpty() ? 0 : 1;
                    records.add(record(t, step, before, oldAfter, newAfter, oldPunct, newPunct));
                } else if (cm.contains("enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace")) {
                    step = "P37B2_TURN_ACTION_DISCIPLINE";
                    p37Steps++;
                    newAfter = texts(CleanAgentOutputRefereeAndSanitizer.enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace(
                            bubbles(before), t.customerMessage(), history, null, List.of(), "em", "mình", careActor));
                    List<String> newPunct = punctuationOnlyMutations(before, newAfter);
                    p37OldPunct += oldPunct.isEmpty() ? 0 : 1;
                    p37NewPunct += newPunct.isEmpty() ? 0 : 1;
                    records.add(record(t, step, before, oldAfter, newAfter, oldPunct, newPunct));
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fixId", "FIX-3");
        summary.put("gitHead", System.getProperty("sbr01.gitHead", "UNSPECIFIED"));
        summary.put("llmCallsMade", 0);
        summary.put("totalStoredTurns", turns.size());
        summary.put("recordedNormalizeSteps", normalizeSteps);
        summary.put("normalizeStepsWithPunctuationMutation_old", normalizeOldPunct);
        summary.put("normalizeStepsWithPunctuationMutation_new", normalizeNewPunct);
        summary.put("recordedP37B2Steps", p37Steps);
        summary.put("p37B2StepsWithPunctuationMutation_old", p37OldPunct);
        summary.put("p37B2StepsWithPunctuationMutation_new", p37NewPunct);
        summary.put("limitations", List.of(
                "Only turns where the baseline harness recorded a mutation for these steps carry a step input; other turns had no mutation from these steps.",
                "P37B2 is replayed without live bookingState/eligibleCatalog/prebuilt plan (plan rebuilt from customer message + stored history); non-punctuation behaviour of P37B2 may differ from live.",
                "Metric counts only sentences whose text is unchanged while the terminal mark changed (punctuation-only mutation)."));
        summary.put("steps", records);
        Path outDir = repoRoot().resolve("benchmark/sbr01/results_fix3_offline");
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("sbr01_fix3_offline_replay.json"), om.writeValueAsString(summary), StandardCharsets.UTF_8);

        StringBuilder md = new StringBuilder("# SBR-01 FIX-3 — Offline punctuation replay (generated)\n\n");
        md.append("- Git HEAD: `").append(summary.get("gitHead")).append("` — LLM calls: 0\n");
        md.append("- normalize step: recorded ").append(normalizeSteps).append(", with punctuation mutation old/new = ")
                .append(normalizeOldPunct).append("/").append(normalizeNewPunct).append("\n");
        md.append("- P37B2 step: recorded ").append(p37Steps).append(", with punctuation mutation old/new = ")
                .append(p37OldPunct).append("/").append(p37NewPunct).append("\n\n");
        md.append("| Case | Run | Turn | Step | Old punct mutations | New punct mutations | Old after | New after |\n|---|---:|---|---|---|---|---|---|\n");
        for (Map<String, Object> r : records) {
            md.append("| ").append(r.get("caseId")).append(" | ").append(r.get("runIndex")).append(" | ").append(r.get("turnId"))
                    .append(" | ").append(r.get("step")).append(" | ").append(esc(r.get("oldPunctuationMutations")))
                    .append(" | ").append(esc(r.get("newPunctuationMutations"))).append(" | ").append(esc(r.get("oldAfter")))
                    .append(" | ").append(esc(r.get("newAfter"))).append(" |\n");
        }
        md.append("\n## Limitations\n\n");
        for (Object l : (List<?>) summary.get("limitations")) {
            md.append("- ").append(l).append("\n");
        }
        Files.writeString(outDir.resolve("SBR01_FIX3_OFFLINE_REPLAY.md"), md.toString(), StandardCharsets.UTF_8);

        assertThat(normalizeNewPunct).isZero();
        assertThat(p37NewPunct).isZero();
    }

    private static Map<String, Object> record(StoredTurn t, String step, List<String> before, List<String> oldAfter,
                                              List<String> newAfter, List<String> oldPunct, List<String> newPunct) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("caseId", t.caseId());
        r.put("runIndex", t.runIndex());
        r.put("turnId", t.turnId());
        r.put("customerMessage", t.customerMessage());
        r.put("step", step);
        r.put("raw_llm_output", t.rawBubbles());
        r.put("post_referee_output", t.postRefereeBubbles());
        r.put("final_sent_output_old", t.oldFinalBubbles());
        r.put("before", before);
        r.put("oldAfter", oldAfter);
        r.put("newAfter", newAfter);
        r.put("oldPunctuationMutations", oldPunct);
        r.put("newPunctuationMutations", newPunct);
        return r;
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
