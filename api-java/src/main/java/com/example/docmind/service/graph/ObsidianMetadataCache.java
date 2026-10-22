package com.example.docmind.service.graph;

import com.example.docmind.dto.ChunkMetadata;
import com.example.docmind.entity.ChunkEntity;
import com.example.docmind.repository.ChunkRepository;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.metadata.ChunkMetadataExtractor;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service quản lý Knowledge Graph và Backlinks theo chuẩn Obsidian MetadataCache.
 * Giữ in-memory graph phân vùng theo pageId hoặc danh sách documentIds để phục vụ
 * mở rộng retrieval và tổng hợp Map of Content (MOC).
 */
@Service
public class ObsidianMetadataCache {

    private static final Logger log = LoggerFactory.getLogger(ObsidianMetadataCache.class);

    private final ChunkRepository chunkRepository;
    private final Map<String, BacklinkGraph> graphCache = new ConcurrentHashMap<>();

    public ObsidianMetadataCache() {
        this(null);
    }

    @Autowired
    public ObsidianMetadataCache(@Autowired(required = false) ChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    /**
     * Đăng ký trực tiếp một BacklinkGraph vào cache cho một Page (hỗ trợ kiểm thử độc lập).
     */
    public void registerGraphForPage(UUID pageId, BacklinkGraph graph) {
        if (pageId != null && graph != null) {
            graphCache.put("page:" + pageId, graph);
        }
    }

    /**
     * Lấy hoặc dựng mới BacklinkGraph cho một Fanpage (pageId).
     */
    public BacklinkGraph getGraphForPage(UUID pageId) {
        if (pageId == null) {
            return new BacklinkGraph();
        }
        String key = "page:" + pageId;
        return graphCache.computeIfAbsent(key, k -> {
            log.info("stage=metadata-cache-build scope=page page_id={}", pageId);
            List<ChunkEntity> chunks = chunkRepository != null ? chunkRepository.findByPageId(pageId) : List.of();
            return buildGraphFromEntities(chunks);
        });
    }

    /**
     * Lấy hoặc dựng mới BacklinkGraph cho danh sách tài liệu (documentIds).
     */
    public BacklinkGraph getGraphForDocuments(List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return new BacklinkGraph();
        }
        List<UUID> sorted = new ArrayList<>(documentIds);
        Collections.sort(sorted);
        String key = "docs:" + sorted.stream().map(UUID::toString).collect(Collectors.joining(","));
        return graphCache.computeIfAbsent(key, k -> {
            log.info("stage=metadata-cache-build scope=documents doc_count={}", sorted.size());
            List<ChunkEntity> chunks = chunkRepository != null ? chunkRepository.findByDocumentIdIn(sorted) : List.of();
            return buildGraphFromEntities(chunks);
        });
    }

    /**
     * Dựng BacklinkGraph từ danh sách ChunkEntity (hỗ trợ test độc lập không cần DB).
     */
    public BacklinkGraph buildGraphFromEntities(List<ChunkEntity> chunks) {
        BacklinkGraph graph = new BacklinkGraph();
        if (chunks == null || chunks.isEmpty()) {
            return graph;
        }

        for (ChunkEntity entity : chunks) {
            if (entity == null || entity.getId() == null) {
                continue;
            }
            List<String> headingPath = entity.getHeadingPath() != null
                    ? Arrays.asList(entity.getHeadingPath())
                    : List.of();
            List<UUID> sourceBlockIds = entity.getSourceBlockIds() != null
                    ? Arrays.asList(entity.getSourceBlockIds())
                    : List.of();
            String text = entity.getText() != null ? entity.getText() : "";

            ChunkMetadata meta = ChunkMetadataExtractor.extract(headingPath, text);
            Set<Long> prices = new LinkedHashSet<>(ChunkMetadataExtractor.extractAllPrices(text));
            Set<String> serviceCodes = new LinkedHashSet<>();
            if (meta.serviceCodes() != null) {
                serviceCodes.addAll(meta.serviceCodes());
            }
            serviceCodes.addAll(ChunkMetadataExtractor.extractServiceCodes(text));
            Set<String> branchTags = new LinkedHashSet<>(meta.branchTags() != null ? meta.branchTags() : List.of());

            ChunkGraphNode node = new ChunkGraphNode(
                    entity.getId(),
                    entity.getDocumentId(),
                    entity.getPageId(),
                    headingPath,
                    sourceBlockIds,
                    entity.getPage(),
                    entity.getBbox(),
                    text,
                    meta,
                    prices,
                    serviceCodes,
                    branchTags
            );
            graph.addNode(node);
        }

        graph.computeEdges();
        log.info("stage=metadata-cache-ready nodes={} prices={} codes={}",
                graph.size(), graph.getAllPricesSorted().size(), graph.getNodesByRole(ChunkMetadata.ROLE_PRICE_TABLE).size());
        return graph;
    }

    /**
     * Mở rộng danh sách top-K chunk bằng các liên kết backlink (FAQ, PROCESS, PROMO).
     *
     * @param primaryChunks danh sách top-K chunk từ RRF / MMR
     * @param question      câu hỏi khách hàng (dùng bóc tách thực thể giá / mã gói)
     * @param pageId        ID của Fanpage (nếu có)
     * @param documentIds   danh sách ID tài liệu (nếu có)
     * @param maxBacklinks  số chunk backlink tối đa được gắn thêm (mặc định 2-3)
     * @return danh sách chunk đã được mở rộng và bảo toàn thứ tự xếp hạng
     */
    public List<ReciprocalRankFusion.FusedChunk> expandBacklinks(
            List<ReciprocalRankFusion.FusedChunk> primaryChunks,
            String question,
            UUID pageId,
            List<UUID> documentIds,
            int maxBacklinks) {

        if (primaryChunks == null || primaryChunks.isEmpty() || maxBacklinks <= 0) {
            return primaryChunks == null ? List.of() : primaryChunks;
        }

        BacklinkGraph graph = (pageId != null) ? getGraphForPage(pageId) : getGraphForDocuments(documentIds);
        if (graph == null || graph.isEmpty()) {
            return primaryChunks;
        }

        Set<UUID> existingChunkIds = new LinkedHashSet<>();
        Set<String> existingSignatures = new LinkedHashSet<>();
        double lowestScore = 0.50;

        for (ReciprocalRankFusion.FusedChunk fc : primaryChunks) {
            if (fc != null && fc.chunk() != null) {
                existingChunkIds.add(fc.chunk().chunkId());
                if (fc.chunk().text() != null) {
                    existingSignatures.add(fc.chunk().text().replaceAll("\\s+", " ").strip());
                }
                lowestScore = Math.min(lowestScore, fc.score());
            }
        }

        List<Long> queryPrices = question != null ? ChunkMetadataExtractor.extractAllPrices(question) : List.of();
        List<String> queryCodes = question != null ? ChunkMetadataExtractor.extractServiceCodes(question) : List.of();
        Set<String> targetRoles = Set.of(ChunkMetadata.ROLE_FAQ, ChunkMetadata.ROLE_PROCESS, ChunkMetadata.ROLE_PROMO);

        List<ChunkGraphNode> candidateNodes = graph.findExpansionCandidates(
                existingChunkIds,
                queryPrices,
                queryCodes,
                targetRoles,
                maxBacklinks
        );

        if (candidateNodes.isEmpty()) {
            return primaryChunks;
        }

        List<ReciprocalRankFusion.FusedChunk> expanded = new ArrayList<>(primaryChunks);
        double backlinkScore = Math.max(0.01, lowestScore * 0.90);
        int added = 0;

        for (ChunkGraphNode node : candidateNodes) {
            String sig = node.text() != null ? node.text().replaceAll("\\s+", " ").strip() : "";
            if (!existingSignatures.contains(sig) && added < maxBacklinks) {
                RetrievedChunk rc = node.toRetrievedChunk(backlinkScore);
                expanded.add(new ReciprocalRankFusion.FusedChunk(rc, backlinkScore));
                existingSignatures.add(sig);
                added++;
                log.info("stage=backlink-expanded chunk_id={} role={} match_prices={} match_codes={}",
                        node.chunkId(), node.metadata() != null ? node.metadata().chunkRole() : "UNKNOWN",
                        node.prices(), node.serviceCodes());
            }
        }

        return expanded;
    }

    /** Xóa cache khi page cập nhật tài liệu. */
    public void invalidatePage(UUID pageId) {
        if (pageId != null) {
            graphCache.remove("page:" + pageId);
            log.info("stage=metadata-cache-invalidate page_id={}", pageId);
        }
    }

    /** Xóa cache khi tài liệu bị xóa hoặc sửa. */
    public void invalidateDocument(UUID documentId) {
        graphCache.clear();
        log.info("stage=metadata-cache-invalidate document_id={}", documentId);
    }

    public void invalidateAll() {
        graphCache.clear();
        log.info("stage=metadata-cache-invalidate scope=all");
    }
}
