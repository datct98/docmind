package com.example.docmind.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Chunk đơn vị retrieval, Java dựng từ blocks (canonical-schema §3).
 *
 * <p>LƯU Ý: cột {@code embedding vector(384)} và {@code tsv tsvector} KHÔNG map qua
 * JPA (Hibernate không biết type pgvector). Chunk được INSERT bằng native SQL trong
 * {@code ChunkRepository} để cast đúng ::vector / to_tsvector(...). Entity này chỉ
 * dùng cho đọc các cột thường (id, document_id, heading_path, source_block_ids,
 * page, bbox, text).
 */
@Entity
@Table(name = "chunks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "heading_path", nullable = false)
    private String[] headingPath;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "source_block_ids", nullable = false)
    private UUID[] sourceBlockIds;

    @Column(name = "page", nullable = false)
    private int page;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "bbox", nullable = false)
    private double[] bbox;

    @Column(name = "text", nullable = false)
    private String text;

    @Column(name = "page_id")
    private UUID pageId;
}
