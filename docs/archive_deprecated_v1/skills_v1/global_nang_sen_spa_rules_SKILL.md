> ⚠️ DEPRECATED ARCHITECTURE (DO NOT USE)
> File skill V1 cũ đã được cách ly để tránh đầu độc ngữ cảnh.

---
name: nang-sen-spa-rules
description: "Use when adhering to specific rules and constraints for N\u00e0ng Sen Spa consultations, pacing, and pricing."
compatibility: antigravity
---

# Nang Sen Spa Rules

Use when adhering to specific rules and constraints for Nàng Sen Spa consultations, pacing, and pricing.

---
name: nang-sen-spa-rules
compatibility: opencode
description: Adhere to the detailed rules and constraints for Nàng Sen Spa consultations, pacing, and pricing.

## Rules

- **Consultation:** When a customer sends a general consultation message (`Tư vấn`), do not provide a price estimate early. The number of listed price levels should be `0`.
- **Pacing:**
  - After displaying the price in the first bubble (`1200ms`), wait `10000ms` (`10 giây`) before the second bubble to maintain customer emotion pacing.
  - Maximum number of bubbles per turn (`max_bubbles`): `3`.
- **Pricing:**
  - Basic Massage Treatment Package (60 minutes, full package without tip): `399.000đ` (Deep Treatment Package 80 minutes: `530.000đ`).
  - Herbal Relaxation Package (60 minutes, softer option when the customer finds the price too high): `239.000đ`.
  - Group discount or combo booking discount: `10%` off.

## Examples

- **Consultation:**
  - **Failed Output:** 'default'
  - **Required Ground Truth:** '0'

- **Pacing:**
  - **Failed Output:** '3 giây'
  - **Required Ground Truth:** '10 giây'
  - **Failed Output:** '30'
  - **Required Ground Truth:** '3'

- **Pricing:**
  - **Failed Output:** '1000000đ'
  - **Required Ground Truth:** '399.000đ'

## Notes

- Only return the exact value matching the unit asked in the question (do not write long sentences in `final_answer`).
