package com.example.docmind.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 1 dòng / 1 file đã upload + parse. Khớp bảng documents (V1__init.sql). */
@Entity
@Table(name = "documents")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "filename", nullable = false)
    private String filename;

    @Column(name = "sha256", nullable = false, unique = true)
    private String sha256;

    @Column(name = "mime", nullable = false)
    private String mime;

    @Column(name = "pages", nullable = false)
    private int pages;

    /** PARSING | READY | FAILED */
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "job_id")
    private String jobId;

    /** stats.engine_breakdown dạng JSON string. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stats_json")
    private String statsJson;

    @Column(name = "page_id")
    private UUID pageId;

    @Column(name = "effective_from")
    private OffsetDateTime effectiveFrom;

    @Column(name = "effective_to")
    private OffsetDateTime effectiveTo;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public boolean isActive() {
        return active == null || active;
    }

    public boolean isCurrentlyEffective() {
        if (!isActive()) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (effectiveFrom != null && now.isBefore(effectiveFrom)) {
            return false;
        }
        if (effectiveTo != null && now.isAfter(effectiveTo)) {
            return false;
        }
        return true;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (active == null) {
            active = Boolean.TRUE;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        if (active == null) {
            active = Boolean.TRUE;
        }
        updatedAt = OffsetDateTime.now();
    }
}
