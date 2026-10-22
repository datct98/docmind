package com.example.docmind.repository;

import java.util.List;
import java.util.UUID;

/** Thao tác native cho pgvector / tsvector mà JPA chuẩn không biểu diễn được. */
public interface ChunkRepositoryCustom {

    /**
     * INSERT 1 chunk bằng native SQL để cast đúng ::vector và to_tsvector('simple', text).
     *
     * @param embedding vector dim 384 (null nếu embedding model chưa sẵn — vẫn index full-text).
     */
    void insertChunk(UUID id,
                     UUID documentId,
                     String[] headingPath,
                     UUID[] sourceBlockIds,
                     int page,
                     double[] bbox,
                     String text,
                     float[] embedding);

    void insertChunk(UUID id,
                     UUID documentId,
                     UUID pageId,
                     String[] headingPath,
                     UUID[] sourceBlockIds,
                     int page,
                     double[] bbox,
                     String text,
                     float[] embedding);

    /** Đồng bộ page_id của toàn bộ chunks (vector + full-text) khi tài liệu chuyển Page. */
    int updatePageIdByDocumentId(UUID documentId, UUID pageId);

    /** Arm 1: vector search theo cosine distance (embedding <=> query), score = 1 - distance. */
    List<RetrievedChunk> searchByVector(List<UUID> documentIds, float[] queryEmbedding, int limit);

    /** Arm 1 (cô lập cứng theo page_id ở tầng SQL): chỉ truy vấn chunks thuộc đúng pageId. */
    List<RetrievedChunk> searchByVector(List<UUID> documentIds, float[] queryEmbedding, int limit, UUID pageId);

    /** Arm 2: full-text search (tsv @@ plainto_tsquery), score = ts_rank. */
    List<RetrievedChunk> searchByFullText(List<UUID> documentIds, String query, int limit);

    /** Arm 2 (cô lập cứng theo page_id ở tầng SQL): chỉ truy vấn chunks thuộc đúng pageId. */
    List<RetrievedChunk> searchByFullText(List<UUID> documentIds, String query, int limit, UUID pageId);
}
