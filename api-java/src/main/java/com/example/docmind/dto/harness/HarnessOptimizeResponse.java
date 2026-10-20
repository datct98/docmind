package com.example.docmind.dto.harness;

import com.example.docmind.dto.LearnedRuleDto;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response payload emitted after completing the Propose–Probe–Anonymized Select cycle.
 */
public record HarnessOptimizeResponse(
        @JsonProperty("run_id")
        @JsonAlias({"runId", "id"})
        String runId,

        @JsonProperty("verdict")
        String verdict, // "ADOPTED" or "KEEP_BASE"

        @JsonProperty("rationale")
        @JsonAlias({"selection_rationale", "selectionRationale"})
        String rationale,

        @JsonProperty("candidate_a")
        @JsonAlias({"candidateA"})
        String candidateA,

        @JsonProperty("candidate_b")
        @JsonAlias({"candidateB"})
        String candidateB,

        @JsonProperty("candidate_a_label")
        @JsonAlias({"candidateALabel"})
        String candidateALabel, // "BASE" or "CANDIDATE"

        @JsonProperty("candidate_b_label")
        @JsonAlias({"candidateBLabel"})
        String candidateBLabel, // "CANDIDATE" or "BASE"

        @JsonProperty("winner_candidate")
        @JsonAlias({"winnerCandidate", "anonymized_winner", "anonymizedWinner"})
        String winnerCandidate, // "Candidate A", "Candidate B", "KEEP_BASE"

        @JsonProperty("improved_response")
        @JsonAlias({"improvedResponse", "improved_reply", "improvedReply", "winning_reply", "winningReply"})
        String improvedResponse,

        @JsonProperty("winning_bubbles")
        @JsonAlias({"winningBubbles"})
        List<String> winningBubbles,

        @JsonProperty("proposed_dimension")
        @JsonAlias({"proposedDimension", "dimension"})
        String proposedDimension, // "INV" or "DOM"

        @JsonProperty("proposed_cue")
        @JsonAlias({"proposedCue", "cue_signal", "cueSignal"})
        String proposedCue,

        @JsonProperty("proposed_lesson")
        @JsonAlias({"proposedLesson", "generalized_rule", "generalizedRule"})
        String proposedLesson,

        @JsonProperty("contrastive_evidence")
        @JsonAlias({"contrastiveEvidence"})
        String contrastiveEvidence,

        @JsonProperty("new_experience_rule")
        @JsonAlias({"newExperienceRule", "adopted_rule", "adoptedRule"})
        LearnedRuleDto newExperienceRule,

        @JsonProperty("score_before")
        @JsonAlias({"scoreBefore"})
        Double scoreBefore,

        @JsonProperty("score_after")
        @JsonAlias({"scoreAfter"})
        Double scoreAfter,

        @JsonProperty("delta_score")
        @JsonAlias({"deltaScore"})
        Double deltaScore
) {
    public String winningReply() {
        return improvedResponse;
    }

    public LearnedRuleDto adoptedRule() {
        return newExperienceRule;
    }

    public String selectionRationale() {
        return rationale;
    }
}
