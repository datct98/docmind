package com.example.docmind.entity;

/**
 * Orthogonal Harness Dimensions from Google Research Harness² (arXiv:2610.14224):
 * <ul>
 *   <li>{@link #INV}: Invariant Sales/Psychology/Communication Procedures (How the agent works).</li>
 *   <li>{@link #DOM}: Verified Page/Workspace Domain Facts (What a practitioner knows).</li>
 * </ul>
 */
public enum HarnessDimension {
    INV,
    DOM;

    public static HarnessDimension fromString(String value) {
        if (value == null || value.isBlank()) {
            return DOM;
        }
        try {
            return HarnessDimension.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DOM;
        }
    }
}
