package com.example.docmind.service.graph;

import com.example.docmind.dto.ChunkMetadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory Backlink Graph cho một tập tài liệu / Page theo kiến trúc MetadataCache của Obsidian.
 * Cung cấp các chỉ mục đảo (inverted index) cho truy vấn O(1) theo mã gói, giá tiền, vai trò và chi nhánh.
 */
public class BacklinkGraph {

    private final Map<UUID, ChunkGraphNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> serviceCodeIndex = new ConcurrentHashMap<>();
    private final Map<Long, Set<UUID>> priceIndex = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> roleIndex = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> branchIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Set<BacklinkEdge>> adjacencyList = new ConcurrentHashMap<>();

    public void addNode(ChunkGraphNode node) {
        if (node == null || node.chunkId() == null) {
            return;
        }
        nodes.put(node.chunkId(), node);

        // Index vai trò
        if (node.metadata() != null && node.metadata().chunkRole() != null) {
            String role = node.metadata().chunkRole().toUpperCase(Locale.ROOT);
            roleIndex.computeIfAbsent(role, k -> ConcurrentHashMap.newKeySet()).add(node.chunkId());
        }

        // Index mã dịch vụ
        if (node.serviceCodes() != null) {
            for (String code : node.serviceCodes()) {
                if (code != null && !code.isBlank()) {
                    serviceCodeIndex.computeIfAbsent(code.toUpperCase(Locale.ROOT), k -> ConcurrentHashMap.newKeySet())
                            .add(node.chunkId());
                }
            }
        }

        // Index giá tiền
        if (node.prices() != null) {
            for (Long price : node.prices()) {
                if (price != null && price > 0) {
                    priceIndex.computeIfAbsent(price, k -> ConcurrentHashMap.newKeySet())
                            .add(node.chunkId());
                }
            }
        }

        // Index chi nhánh
        if (node.branchTags() != null) {
            for (String branch : node.branchTags()) {
                if (branch != null && !branch.isBlank()) {
                    branchIndex.computeIfAbsent(branch.toLowerCase(Locale.ROOT), k -> ConcurrentHashMap.newKeySet())
                            .add(node.chunkId());
                }
            }
        }
    }

    /**
     * Dựng toàn bộ các cạnh liên kết hai chiều (bidirectional edges) giữa các chunk sau khi nạp nodes.
     */
    public void computeEdges() {
        adjacencyList.clear();

        for (ChunkGraphNode node : nodes.values()) {
            UUID sourceId = node.chunkId();
            Set<BacklinkEdge> edges = ConcurrentHashMap.newKeySet();

            // 1. Shared service codes (PRICE_TABLE <-> FAQ, PROCESS, PROMO)
            if (node.serviceCodes() != null) {
                for (String code : node.serviceCodes()) {
                    String normCode = code.toUpperCase(Locale.ROOT);
                    Set<UUID> peers = serviceCodeIndex.get(normCode);
                    if (peers != null) {
                        for (UUID peerId : peers) {
                            if (!peerId.equals(sourceId)) {
                                ChunkGraphNode peer = nodes.get(peerId);
                                if (peer != null) {
                                    String role = peer.metadata() != null ? peer.metadata().chunkRole() : ChunkMetadata.ROLE_GENERAL;
                                    edges.add(new BacklinkEdge(
                                            peerId,
                                            role,
                                            BacklinkEdge.LinkType.SERVICE_CODE,
                                            normCode,
                                            1.0
                                    ));
                                }
                            }
                        }
                    }
                }
            }

            // 2. Shared price points (PRICE_TABLE <-> FAQ so sánh giá)
            if (node.prices() != null) {
                for (Long price : node.prices()) {
                    Set<UUID> peers = priceIndex.get(price);
                    if (peers != null) {
                        for (UUID peerId : peers) {
                            if (!peerId.equals(sourceId)) {
                                ChunkGraphNode peer = nodes.get(peerId);
                                if (peer != null) {
                                    String role = peer.metadata() != null ? peer.metadata().chunkRole() : ChunkMetadata.ROLE_GENERAL;
                                    edges.add(new BacklinkEdge(
                                            peerId,
                                            role,
                                            BacklinkEdge.LinkType.PRICE_POINT,
                                            String.valueOf(price),
                                            0.85
                                    ));
                                }
                            }
                        }
                    }
                }
            }

            // 3. PROMO chính sách ưu đãi liên kết hai chiều tới các bảng giá
            if (node.metadata() != null && node.metadata().isPromo()) {
                Set<UUID> priceTableIds = roleIndex.getOrDefault(ChunkMetadata.ROLE_PRICE_TABLE, Set.of());
                for (UUID ptId : priceTableIds) {
                    if (!ptId.equals(sourceId)) {
                        edges.add(new BacklinkEdge(
                                ptId,
                                ChunkMetadata.ROLE_PRICE_TABLE,
                                BacklinkEdge.LinkType.PROMO_POLICY,
                                "PROMO_POLICY",
                                0.75
                        ));
                        // Bidirectional link từ PRICE_TABLE về PROMO
                        adjacencyList.computeIfAbsent(ptId, k -> ConcurrentHashMap.newKeySet())
                                .add(new BacklinkEdge(
                                        sourceId,
                                        ChunkMetadata.ROLE_PROMO,
                                        BacklinkEdge.LinkType.PROMO_POLICY,
                                        "PROMO_POLICY",
                                        0.75
                                ));
                    }
                }
            }

            if (!edges.isEmpty()) {
                adjacencyList.computeIfAbsent(sourceId, k -> ConcurrentHashMap.newKeySet()).addAll(edges);
            }
        }
    }

    /**
     * Tìm kiếm các chunk ứng viên mở rộng (FAQ, PROCESS, PROMO) liên kết với tập chunk nguồn hoặc thực thể câu hỏi.
     */
    public List<ChunkGraphNode> findExpansionCandidates(
            Collection<UUID> sourceChunkIds,
            Collection<Long> queryPrices,
            Collection<String> queryServiceCodes,
            Set<String> targetRoles,
            int maxResults) {

        if (maxResults <= 0 || nodes.isEmpty()) {
            return List.of();
        }

        Set<UUID> excludeIds = sourceChunkIds != null ? new LinkedHashSet<>(sourceChunkIds) : Set.of();
        Set<String> safeRoles = targetRoles != null ? targetRoles : Set.of(ChunkMetadata.ROLE_FAQ, ChunkMetadata.ROLE_PROCESS, ChunkMetadata.ROLE_PROMO);
        Map<UUID, Double> candidateScores = new LinkedHashMap<>();

        // 1. Duyệt qua backlinks từ các chunk nguồn đã lấy được
        if (sourceChunkIds != null) {
            for (UUID sId : sourceChunkIds) {
                Set<BacklinkEdge> edges = adjacencyList.get(sId);
                if (edges != null) {
                    for (BacklinkEdge edge : edges) {
                        UUID targetId = edge.targetChunkId();
                        if (!excludeIds.contains(targetId) && safeRoles.contains(edge.targetRole())) {
                            candidateScores.merge(targetId, edge.weight(), Double::sum);
                        }
                    }
                }
            }
        }

        // 2. Tăng cường ứng viên khớp trực tiếp với giá tiền trong câu hỏi (VD: 670k, 780k)
        if (queryPrices != null) {
            for (Long qPrice : queryPrices) {
                Set<UUID> chunkIds = priceIndex.get(qPrice);
                if (chunkIds != null) {
                    for (UUID cId : chunkIds) {
                        if (!excludeIds.contains(cId)) {
                            ChunkGraphNode n = nodes.get(cId);
                            if (n != null && n.metadata() != null && safeRoles.contains(n.metadata().chunkRole())) {
                                candidateScores.merge(cId, 1.25, Double::sum);
                            }
                        }
                    }
                }
            }
        }

        // 3. Tăng cường ứng viên khớp mã dịch vụ trong câu hỏi (VD: NS01)
        if (queryServiceCodes != null) {
            for (String qCode : queryServiceCodes) {
                Set<UUID> chunkIds = serviceCodeIndex.get(qCode.toUpperCase(Locale.ROOT));
                if (chunkIds != null) {
                    for (UUID cId : chunkIds) {
                        if (!excludeIds.contains(cId)) {
                            ChunkGraphNode n = nodes.get(cId);
                            if (n != null && n.metadata() != null && safeRoles.contains(n.metadata().chunkRole())) {
                                candidateScores.merge(cId, 1.50, Double::sum);
                            }
                        }
                    }
                }
            }
        }

        if (candidateScores.isEmpty()) {
            return List.of();
        }

        return candidateScores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(maxResults)
                .map(e -> nodes.get(e.getKey()))
                .filter(Objects::nonNull)
                .toList();
    }

    public int size() {
        return nodes.size();
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public Map<UUID, ChunkGraphNode> getNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    public Set<BacklinkEdge> getBacklinks(UUID chunkId) {
        return adjacencyList.getOrDefault(chunkId, Set.of());
    }

    public List<ChunkGraphNode> getNodesByRole(String role) {
        if (role == null) return List.of();
        Set<UUID> ids = roleIndex.get(role.toUpperCase(Locale.ROOT));
        if (ids == null || ids.isEmpty()) return List.of();
        List<ChunkGraphNode> list = new ArrayList<>();
        for (UUID id : ids) {
            ChunkGraphNode n = nodes.get(id);
            if (n != null) list.add(n);
        }
        return list;
    }

    public List<Long> getAllPricesSorted() {
        List<Long> prices = new ArrayList<>(priceIndex.keySet());
        Collections.sort(prices);
        return prices;
    }

    public Map<String, Set<UUID>> getServiceCodeIndex() {
        return Collections.unmodifiableMap(serviceCodeIndex);
    }

    public Map<Long, Set<UUID>> getPriceIndex() {
        return Collections.unmodifiableMap(priceIndex);
    }

    public Map<String, Set<UUID>> getRoleIndex() {
        return Collections.unmodifiableMap(roleIndex);
    }

    public Map<String, Set<UUID>> getBranchIndex() {
        return Collections.unmodifiableMap(branchIndex);
    }

    public Map<UUID, Set<BacklinkEdge>> getAdjacencyList() {
        return Collections.unmodifiableMap(adjacencyList);
    }
}
