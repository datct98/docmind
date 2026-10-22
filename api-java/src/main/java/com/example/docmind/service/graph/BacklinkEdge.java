package com.example.docmind.service.graph;

import java.util.UUID;

/**
 * Biểu diễn một liên kết backlink hai chiều giữa 2 chunk trong Knowledge Graph theo chuẩn Obsidian.
 */
public record BacklinkEdge(
        UUID targetChunkId,
        String targetRole,       // PRICE_TABLE | FAQ | PROCESS | PROMO | GENERAL
        LinkType linkType,       // SERVICE_CODE | PRICE_POINT | EXPLICIT_ENTITY | PROMO_POLICY
        String matchKey,         // e.g. "NS01", "670000", "PROMO_POLICY"
        double weight            // Trọng số liên kết (0.0 - 1.0)
) {
    public enum LinkType {
        SERVICE_CODE,
        PRICE_POINT,
        EXPLICIT_ENTITY,
        PROMO_POLICY
    }
}
