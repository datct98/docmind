package com.example.docmind;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * DocMind api-java — Spring Boot 4 orchestrator.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class DocmindApiApplication {

    private static final Logger log = LoggerFactory.getLogger(DocmindApiApplication.class);

    /**
     * Các khóa cấu hình mạng nội bộ Docker Compose không được phép bị ghi đè bởi giá trị localhost trong .env
     * khi đang chạy bên trong container.
     */
    private static final Set<String> CONTAINER_NETWORK_PROTECTED_KEYS = Set.of(
            "SPRING_DATASOURCE_URL",
            "DOC_SERVICE_BASE_URL"
    );

    /**
     * Ánh xạ trực tiếp biến môi trường trong .env sang khóa cấu hình chuẩn của Spring Boot (systemProperties).
     * Trong Spring Boot, systemProperties (System.getProperties) có độ ưu tiên #1 (cao hơn systemEnvironment #2),
     * nhưng đòi hỏi tên khóa dạng chữ thường phân tách bằng dấu chấm/gạch ngang (vd: llm.provider, llm.api-key).
     * Nhờ ánh xạ này, khi sửa .env và chạy `docker restart docmind-api`, giá trị mới trong .env sẽ ghi đè
     * hoàn toàn các biến môi trường cũ bị lưu cứng trong container lúc tạo!
     */
    private static final java.util.Map<String, String> ENV_TO_SPRING_PROPERTY_MAP = java.util.Map.ofEntries(
            java.util.Map.entry("LLM_PROVIDER", "llm.provider"),
            java.util.Map.entry("LLM_MODEL", "llm.model"),
            java.util.Map.entry("LLM_BASE_URL", "llm.base-url"),
            java.util.Map.entry("LLM_API_KEY", "llm.api-key"),
            java.util.Map.entry("LLM_TEMPERATURE", "llm.temperature"),
            java.util.Map.entry("LLM_TIMEOUT_SECONDS", "llm.timeout-seconds"),
            java.util.Map.entry("LLM_REASONING_EFFORT", "llm.reasoning-effort"),
            java.util.Map.entry("LLM_MAX_TOKENS", "llm.max-tokens"),
            java.util.Map.entry("GEMINI_API_KEYS", "gemini.api.keys"),
            java.util.Map.entry("GEMINI_MODEL", "gemini.model"),
            java.util.Map.entry("GEMINI_BASE_URL", "gemini.base-url"),
            java.util.Map.entry("OLLAMA_ENABLED", "ollama.enabled"),
            java.util.Map.entry("OLLAMA_BASE_URL", "ollama.base-url"),
            java.util.Map.entry("OLLAMA_MODEL", "ollama.model"),
            java.util.Map.entry("OLLAMA_TEMPERATURE", "ollama.temperature"),
            java.util.Map.entry("OLLAMA_NUM_CTX", "ollama.num-ctx"),
            java.util.Map.entry("OLLAMA_NUM_PREDICT", "ollama.num-predict"),
            java.util.Map.entry("OLLAMA_READ_TIMEOUT_MS", "ollama.read-timeout-ms"),
            java.util.Map.entry("DOCMIND_CHAT_TOP_K", "docmind.chat.top-k"),
            java.util.Map.entry("DOCMIND_CHAT_CANDIDATE_K", "docmind.chat.candidate-k"),
            java.util.Map.entry("DOCMIND_CHAT_RRF_K", "docmind.chat.rrf-k"),
            java.util.Map.entry("DOCMIND_FANPAGE_VERIFY_TOKEN", "docmind.fanpage.verify-token"),
            java.util.Map.entry("DOCMIND_FANPAGE_APP_SECRET", "docmind.fanpage.app-secret")
    );

    public static void main(String[] args) {
        loadDotenv();
        SpringApplication.run(DocmindApiApplication.class, args);
    }

    public static void loadDotenv() {
        loadDotenv(
                Files.exists(Path.of("/.dockerenv")),
                Path.of(".", ".env"),
                Path.of("..", ".env"),
                Path.of("../..", ".env")
        );
    }

    public static void loadDotenv(boolean insideDocker, Path... candidatePaths) {
        Set<String> keysFromCloserFiles = new HashSet<>();
        for (Path path : candidatePaths) {
            if (path != null && Files.isRegularFile(path)) {
                Set<String> keysInCurrentFile = new HashSet<>();
                try {
                    for (String line : Files.readAllLines(path)) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                            continue;
                        }
                        int eq = trimmed.indexOf('=');
                        if (eq > 0) {
                            String key = trimmed.substring(0, eq).trim();
                            if (key.startsWith("export ")) {
                                key = key.substring(7).trim();
                            }
                            String value = trimmed.substring(eq + 1).trim();
                            boolean quoted = value.length() >= 2
                                    && ((value.startsWith("\"") && value.endsWith("\""))
                                        || (value.startsWith("'") && value.endsWith("'")));
                            if (quoted) {
                                value = value.substring(1, value.length() - 1);
                            } else {
                                int inlineComment = value.indexOf(" #");
                                if (inlineComment >= 0) {
                                    value = value.substring(0, inlineComment).trim();
                                }
                            }
                            // Chỉ bỏ qua nếu biến đã được định nghĩa ở file .env gần hơn;
                            // trong cùng 1 file .env, dòng bên dưới (vd: khối Preset được bỏ comment) được phép ghi đè dòng bên trên.
                            if (keysFromCloserFiles.contains(key)) {
                                continue;
                            }
                            keysInCurrentFile.add(key);

                            // Bảo vệ URL kết nối nội bộ mạng Docker (postgres:5432, doc-service-py:8091)
                            if (insideDocker && CONTAINER_NETWORK_PROTECTED_KEYS.contains(key) && System.getenv(key) != null) {
                                continue;
                            }

                            // Nếu chạy trong Docker mà .env cấu hình LLM/Ollama trỏ localhost -> tự động ánh xạ sang host.docker.internal
                            if (insideDocker && ("OLLAMA_BASE_URL".equals(key) || "LLM_BASE_URL".equals(key))) {
                                value = value
                                        .replaceAll("://(?:localhost|127\\.0\\.0\\.1)(?=[:/]|$)", "://host.docker.internal");
                            }

                            // 1. Ghi vào System.setProperty theo tên biến gốc (phục vụ ${KEY} trong application.properties)
                            System.setProperty(key, value);

                            // 2. Ghi vào System.setProperty theo tên chuẩn Spring Boot (vd: llm.provider, llm.api-key)
                            // để vượt qua System.getenv() cũ của Docker container khi chạy `docker restart`
                            String springProp = ENV_TO_SPRING_PROPERTY_MAP.get(key);
                            if (springProp != null) {
                                System.setProperty(springProp, value);
                            }
                        }
                    }
                    keysFromCloserFiles.addAll(keysInCurrentFile);
                    log.info("Loaded .env (hot-reload priority) from: {}", path.toAbsolutePath().normalize());
                } catch (Exception e) {
                    log.warn("Lỗi đọc .env tại {}: {}", path, e.getMessage());
                }
            }
        }
    }
}
