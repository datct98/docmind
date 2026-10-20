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

import java.time.Instant;

/**
 * JPA Entity mapping table {@code harness_optimization_runs} (Flyway V11).
 * Records each recursive contrastive optimization run (Propose–Probe–Anonymized Select).
 */
@Entity
@Table(name = "harness_optimization_runs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HarnessRunEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "page_id", length = 64)
    private String pageId;

    @Column(name = "customer_message", columnDefinition = "TEXT")
    private String customerMessage;

    @Column(name = "base_response", columnDefinition = "TEXT")
    private String baseResponse;

    @Column(name = "candidate_a", columnDefinition = "TEXT")
    private String candidateA;

    @Column(name = "candidate_b", columnDefinition = "TEXT")
    private String candidateB;

    @Column(name = "winner_candidate", length = 32)
    private String winnerCandidate; // "Candidate A", "Candidate B", "KEEP_BASE"

    @Column(name = "verdict", length = 32)
    private String verdict; // "ADOPTED" or "KEEP_BASE"

    @Column(name = "score_before")
    private Double scoreBefore;

    @Column(name = "score_after")
    private Double scoreAfter;

    @Column(name = "delta_score")
    private Double deltaScore;

    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
