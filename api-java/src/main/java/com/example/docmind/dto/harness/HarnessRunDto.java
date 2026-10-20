package com.example.docmind.dto.harness;

import com.example.docmind.entity.HarnessRunEntity;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * DTO representing an optimization run record for the Studio evolution history.
 */
public record HarnessRunDto(
        @JsonProperty("id")
        String id,

        @JsonProperty("page_id")
        @JsonAlias({"pageId"})
        String pageId,

        @JsonProperty("customer_message")
        @JsonAlias({"customerMessage"})
        String customerMessage,

        @JsonProperty("base_response")
        @JsonAlias({"baseResponse"})
        String baseResponse,

        @JsonProperty("candidate_a")
        @JsonAlias({"candidateA"})
        String candidateA,

        @JsonProperty("candidate_b")
        @JsonAlias({"candidateB"})
        String candidateB,

        @JsonProperty("winner_candidate")
        @JsonAlias({"winnerCandidate"})
        String winnerCandidate,

        @JsonProperty("verdict")
        String verdict,

        @JsonProperty("score_before")
        @JsonAlias({"scoreBefore"})
        Double scoreBefore,

        @JsonProperty("score_after")
        @JsonAlias({"scoreAfter"})
        Double scoreAfter,

        @JsonProperty("delta_score")
        @JsonAlias({"deltaScore"})
        Double deltaScore,

        @JsonProperty("rationale")
        String rationale,

        @JsonProperty("created_at")
        @JsonAlias({"createdAt"})
        Instant createdAt
) {
    public static HarnessRunDto fromEntity(HarnessRunEntity entity) {
        if (entity == null) {
            return null;
        }
        return new HarnessRunDto(
                entity.getId(),
                entity.getPageId(),
                entity.getCustomerMessage(),
                entity.getBaseResponse(),
                entity.getCandidateA(),
                entity.getCandidateB(),
                entity.getWinnerCandidate(),
                entity.getVerdict(),
                entity.getScoreBefore(),
                entity.getScoreAfter(),
                entity.getDeltaScore(),
                entity.getRationale(),
                entity.getCreatedAt()
        );
    }
}
