package com.example.docmind.service.salescore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import static com.example.docmind.service.salescore.SalesContracts.*;

/** New industries are data, not dispatch branches. Invalid configured packs fail visibly. */
@Component
public class IndustryProfileRegistry {
    private final Path root;
    private final ObjectMapper json = new ObjectMapper();
    public IndustryProfileRegistry() { this("config/sales/industries"); }
    @Autowired
    public IndustryProfileRegistry(@Value("${sales.core.industry-directory:config/sales/industries}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }
    public IndustryProfile load(String requested) {
        String code = requested == null || requested.isBlank() ? "GENERIC" : requested.toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid industry code");
        try {
            Path file = root.resolve(code + ".json");
            IndustryProfile profile;
            if (Files.exists(file)) {
                profile = json.readValue(Files.readString(file), IndustryProfile.class);
            } else {
                var stream = getClass().getResourceAsStream("/sales/industries/" + code + ".json");
                if (stream == null) {
                    if (!code.equals("GENERIC")) return load("GENERIC");
                    throw new IllegalStateException("Missing GENERIC profile");
                }
                try (stream) { profile = json.readValue(stream.readAllBytes(), IndustryProfile.class); }
            }
            if (!code.equals(profile.code()) || profile.decisionCriteria() == null
                    || profile.discoveryHints() == null || profile.boundaries() == null) {
                throw new IllegalArgumentException("Invalid industry profile: " + code);
            }
            return profile;
        } catch (IOException e) { throw new IllegalStateException("Cannot read industry profile " + code, e); }
    }
}
