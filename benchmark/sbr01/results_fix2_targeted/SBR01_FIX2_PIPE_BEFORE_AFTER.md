# SBR-01 FIX-2 — pipe delimiter before/after (generated)

Cases: CASE-01, CASE-04, CASE-06, CASE-09, CASE-11, CASE-13, CASE-19 — N=3 each. Before = baseline `results/` (HEAD 1cd09e4 code); After = `results_fix2_targeted`.

| Metric | Before | After |
|---|---:|---:|
| turns | 135 | 135 |
| rawTurnsWithPipe | 29 | 0 |
| postRefereeTurnsWithPipe | 29 | 0 |
| finalTurnsWithPipe | 29 | 0 |
| promptsWithHistoryPipeDelimiter | 81 | 0 |
| historyPipeOccurrences | 87 | 0 |
| promptsWithBotHistoryLines | 114 | 114 |

- `historyPipeOccurrences` counts `' | '` inside the `[Tn] Bot:` history lines of the prompt actually sent (delimiter in prompt).
- `rawTurnsWithPipe` counts model-generated `|` in `raw_llm_output` (imitation), distinct from the prompt delimiter.
