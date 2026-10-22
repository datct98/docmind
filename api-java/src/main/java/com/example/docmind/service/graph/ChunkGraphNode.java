package com.example.docmind.service.graph;

import com.example.docmind.dto.ChunkMetadata;
import com.example.docmind.repository.RetrievedChunk;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Node đại diện cho một chunk trong in-memory Knowledge Graph.
 */
public record ChunkGraphNode(
        UUID chunkId,
        UUID documentId,
        UUID pageId,
        List<String> headingPath,
        List<UUID> sourceBlockIds,
        int page,
        double[] bbox,
        String text,
        ChunkMetadata metadata,
        Set<Long> prices,
        Set<String> serviceCodes,
        Set<String> branchTags
) {
    public RetrievedChunk toRetrievedChunk(double score) {
        return new RetrievedChunk(
                chunkId,
                documentId,
                headingPath != null ? headingPath : List.of(),
                sourceBlockIds != null ? sourceBlockIds : List.of(),
                page,
                bbox != null ? bbox : new double[0],
                text != null ? text : "",
                score
        );
    }
}
