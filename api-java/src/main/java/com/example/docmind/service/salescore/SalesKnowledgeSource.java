package com.example.docmind.service.salescore;

import com.example.docmind.entity.*;
import com.example.docmind.repository.*;
import com.example.docmind.service.retrieval.RetrievalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static com.example.docmind.service.salescore.SalesContracts.*;

/** Page ownership/validity is checked before any retrieval, including caller-supplied document ids. */
@Service
public class SalesKnowledgeSource {
    private final KnowledgePageRepository pages;
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final RetrievalService retrieval;
    private final Path profileDirectory;
    private final ObjectMapper json = new ObjectMapper();
    public SalesKnowledgeSource(KnowledgePageRepository pages, DocumentRepository documents, ChunkRepository chunks,
            RetrievalService retrieval, @Value("${sales.core.page-directory:config/sales/pages}") String directory) {
        this.pages = pages; this.documents = documents; this.chunks = chunks; this.retrieval = retrieval;
        profileDirectory = Path.of(directory).toAbsolutePath().normalize();
    }
    public TurnInput load(UUID pageId, List<UUID> requestedDocuments, List<Message> history, String message, String quoted) {
        if (pageId == null) throw new IllegalArgumentException("Page is required");
        KnowledgePageEntity page = pages.findById(pageId).orElseThrow(() -> new IllegalArgumentException("Unknown Page"));
        var allowed = documents.findByPageIdOrderByCreatedAtDesc(pageId).stream()
                .filter(d -> pageId.equals(d.getPageId()) && "READY".equalsIgnoreCase(d.getStatus()) && d.isCurrentlyEffective())
                .map(DocumentEntity::getId).toList();
        List<UUID> scope = allowed;
        if (requestedDocuments != null && !requestedDocuments.isEmpty()) {
            if (!allowed.containsAll(requestedDocuments)) throw new IllegalArgumentException("Document is outside active Page scope");
            scope = requestedDocuments.stream().distinct().toList();
        }
        // Read explicit persona fields only: legacy template inference must not insert another industry's instructions.
        JsonNode persona = json.readTree(page.getPersonaConfigJson() == null ? "{}" : page.getPersonaConfigJson());
        PageSalesProfile profile = readPageProfile(pageId);
        LinkedHashMap<String, Evidence> candidates = new LinkedHashMap<>();
        var today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        for (var offer : profile.offers()) if (offer.effective(today)) {
            if (candidates.put("offer:" + offer.id(), new Evidence("offer:" + offer.id(), "Page catalog", offer.asEvidence())) != null)
                throw new IllegalArgumentException("Duplicate offer id");
        }
        for (int i = 0; i < profile.businessPolicies().size(); i++) {
            String id = "policy:" + i;
            candidates.put(id, new Evidence(id, "Page policy", profile.businessPolicies().get(i)));
        }
        final List<UUID> requestedScope = scope;
        List<ChunkEntity> snapshot = scope.isEmpty() ? List.of() : chunks.findByDocumentIdIn(scope,
                PageRequest.of(0, 256, Sort.by("id")));
        // Pricing references survive a short reply ('yes', a location, an objection) and do not depend on vector quality.
        snapshot.stream().filter(c -> referencePriority(c) < 3).sorted(Comparator.comparingInt(SalesKnowledgeSource::referencePriority))
                .forEach(c -> putChunk(candidates, c, requestedScope));
        if (!scope.isEmpty() && retrieval != null) {
            String context = history.stream().skip(Math.max(0, history.size() - 6)).map(Message::content)
                    .reduce("", (a, b) -> a + "\n" + b) + "\n" + message;
            if (context.length() > 3500) context = context.substring(context.length() - 3500);
            var found = retrieval.retrieveWithRules(scope, context, 10, List.of(), pageId);
            if (found != null && found.chunks() != null) for (var item : found.chunks()) {
                var c = item.chunk();
                if (scope.contains(c.documentId())) candidates.putIfAbsent(c.chunkId().toString(),
                        new Evidence(c.chunkId().toString(), String.join(" / ", c.headingPath()), c.text()));
            }
        }
        snapshot.forEach(c -> putChunk(candidates, c, requestedScope));
        int used = 0;
        boolean truncated = snapshot.size() >= 256;
        List<Evidence> evidence = new ArrayList<>();
        for (Evidence e : candidates.values()) {
            // Keep whole evidence pieces. Never cut a price row/condition in half silently.
            if (e.text() == null || e.text().isBlank()) continue;
            if (used + e.text().length() > 18000 || e.text().length() > 8000) { truncated = true; continue; }
            evidence.add(e); used += e.text().length();
        }
        List<Message> boundedHistory = new ArrayList<>();
        int historyChars = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m == null || m.content() == null || m.role() == null) continue;
            String role = switch (m.role().toLowerCase(Locale.ROOT)) {
                case "user", "customer" -> "user";
                case "assistant", "ai_bot", "human_sale" -> "assistant";
                default -> null;
            };
            if (role == null) continue;
            if (historyChars + m.content().length() > 14000 || boundedHistory.size() >= 40) { truncated = true; break; }
            boundedHistory.addFirst(new Message(role, m.content())); historyChars += m.content().length();
        }
        return new TurnInput(pageId.toString(), page.getName(), choose(profile.industryCode(), value(persona, "industry_code", "GENERIC")),
                value(persona, "self_pronoun", "em"), value(persona, "default_customer_pronoun", "mình"),
                choose(profile.voice(), value(persona, "tone_style", "ấm áp, rõ ràng, tự nhiên")), boundedHistory, message,
                evidence, List.of(), quoted, truncated);
    }
    private PageSalesProfile readPageProfile(UUID page) {
        Path file = profileDirectory.resolve(page + ".json");
        if (!Files.exists(file)) return new PageSalesProfile(null, null, List.of(), List.of());
        try {
            if (Files.size(file) > 150000) throw new IllegalArgumentException("Page profile too large");
            return json.readValue(Files.readString(file), PageSalesProfile.class);
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read Page sales profile", e); }
    }
    private static int referencePriority(ChunkEntity c) {
        String meta = c.getChunkMetadataJson() == null ? "" : c.getChunkMetadataJson();
        String text = c.getText() == null ? "" : c.getText();
        if (meta.contains("\"PRICE\"") || text.matches("(?isu).*\\|\\s*(?:giá|price|đơn giá)\\s*\\|.*")) return 0;
        if (meta.contains("\"POLICY\"")) return 1;
        return text.contains("|") ? 2 : 3;
    }
    private static void putChunk(Map<String, Evidence> map, ChunkEntity c, List<UUID> allowed) {
        if (!allowed.contains(c.getDocumentId()) || c.getId() == null) return;
        map.putIfAbsent(c.getId().toString(), new Evidence(c.getId().toString(),
                c.getHeadingPath() == null ? "Knowledge" : String.join(" / ", c.getHeadingPath()), c.getText()));
    }
    private static String value(JsonNode n, String field, String fallback) {
        return n != null && n.hasNonNull(field) ? choose(n.get(field).asText(), fallback) : fallback;
    }
    private static String choose(String first, String fallback) { return first == null || first.isBlank() ? fallback : first; }
}
