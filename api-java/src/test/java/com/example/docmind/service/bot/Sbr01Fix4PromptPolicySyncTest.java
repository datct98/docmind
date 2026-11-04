package com.example.docmind.service.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Sbr01Fix4PromptPolicySyncTest {

    @Test
    @DisplayName("FIX-4: External config/aci_v2_system_prompt.md and in-code fallback are 100% synchronized at version aci-v2.13-sbr01-fix4-policy")
    void externalPromptAndInCodeFallbackAreStrictlySynchronized() throws Exception {
        CleanAgentCatalogAndPromptBuilder.setExternalAciPromptPathForTest(null);
        String loadedTemplate = CleanAgentCatalogAndPromptBuilder.loadValidatedAciSystemPromptTemplate();

        assertThat(CleanAgentCatalogAndPromptBuilder.getActiveAciPromptVersion())
                .isEqualTo("aci-v2.13-sbr01-fix4-policy");
        assertThat(CleanAgentCatalogAndPromptBuilder.getActiveAciPromptSource())
                .startsWith("EXTERNAL_FILE:");
        assertThat(CleanAgentCatalogAndPromptBuilder.IN_CODE_ACI_V2_PROMPT_VERSION)
                .isEqualTo("aci-v2.13-sbr01-fix4-policy");

        Path externalPath = Path.of("..", "config", "aci_v2_system_prompt.md").normalize();
        if (!Files.exists(externalPath)) {
            externalPath = Path.of("config", "aci_v2_system_prompt.md").normalize();
        }
        assertThat(Files.exists(externalPath)).isTrue();

        String rawExternal = Files.readString(externalPath, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(rawExternal).startsWith("<!-- prompt_version: aci-v2.13-sbr01-fix4-policy -->\n");
        String strippedExternal = rawExternal.replaceFirst("^<!--\\s*prompt_version:[^>]+-->\\s*", "").strip();
        String inCode = CleanAgentCatalogAndPromptBuilder.getInCodeAciPromptTemplate().replace("\r\n", "\n").strip();

        assertThat(strippedExternal).isEqualTo(inCode);
        assertThat(loadedTemplate.replace("\r\n", "\n").strip()).isEqualTo(inCode);
    }

    @Test
    @DisplayName("FIX-4: In-code fallback activates cleanly when external prompt path is missing and preserves all 6 policy rules")
    void inCodeFallbackPreservesAllSixPolicyRules() {
        try {
            CleanAgentCatalogAndPromptBuilder.setExternalAciPromptPathForTest(
                    Path.of("nonexistent/missing_prompt_file.md"));
            String fallbackTemplate = CleanAgentCatalogAndPromptBuilder.loadValidatedAciSystemPromptTemplate();

            assertThat(CleanAgentCatalogAndPromptBuilder.getActiveAciPromptSource())
                    .isEqualTo("IN_CODE_FALLBACK");
            assertThat(CleanAgentCatalogAndPromptBuilder.getActiveAciPromptVersion())
                    .isEqualTo("aci-v2.13-sbr01-fix4-policy");

            // 1. Do not ask body region ("vùng nào") as default discovery question
            assertThat(fallbackTemplate).contains("CẤM mặc định hỏi \"vùng nào\"");
            assertThat(fallbackTemplate).doesNotContain("hỏi ưu tiên vùng nào hay buổi vừa đủ/chuyên sâu (0 đưa ngay tên/giá 2 gói");

            // 2. Representative packages vs full catalog framing & listing other packages on T13/T15
            assertThat(fallbackTemplate).contains("nói rõ đây là vài gói tiêu biểu, bên {{SELF_PRONOUN}} còn các gói khác (CẤM nói/ngụ ý chỉ có 1–2 gói");
            assertThat(fallbackTemplate).contains("Hỏi \"còn gói nào khác\" / phàn nàn tưởng chỉ có 1–2 gói: xác nhận ngắn bên {{SELF_PRONOUN}} có nhiều gói rồi nêu ngay 2–4 gói khác trong Catalog");
            assertThat(fallbackTemplate).contains("So sánh (\"khác gì nhau\", \"rốt cuộc 2 gói khác gì\"): so sánh ĐÚNG các gói vừa nêu ở lượt trước");

            // 3. Ban canned "sẽ không gợi ý thêm nữa" / "sẽ không hỏi thêm gì nữa"
            assertThat(fallbackTemplate).contains("CẤM câu khuôn *\"{{SELF_PRONOUN}} sẽ không gợi ý (thêm) nữa\"*, *\"{{SELF_PRONOUN}} sẽ không hỏi thêm gì nữa\"*");

            // 4, 5, 6. Medical safety boundary NO_SYMPTOM_TREATMENT_CLAIM + continue answering price/scope/duration + do not refuse relaxation consultation
            assertThat(fallbackTemplate).contains("`NO_SYMPTOM_TREATMENT_CLAIM`: CẤM gắn dịch vụ với điều trị/giảm/cải thiện/xử lý triệu chứng sức khỏe");
            assertThat(fallbackTemplate).contains("CẤM nói gói \"phù hợp với\"/\"để giảm\" các triệu chứng đó");
            assertThat(fallbackTemplate).contains("Vẫn trả lời trực tiếp giá, phạm vi, thời lượng; KHÔNG từ chối tư vấn dịch vụ thư giãn chỉ vì khách nói mệt mỏi.");
        } finally {
            CleanAgentCatalogAndPromptBuilder.setExternalAciPromptPathForTest(null);
            CleanAgentCatalogAndPromptBuilder.loadValidatedAciSystemPromptTemplate();
        }
    }
}
