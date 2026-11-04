# -*- coding: utf-8 -*-
"""SBR-01 FIX-2 before/after pipe analysis (baseline results/ vs results_fix2_targeted/)."""
import json, re, sys
from pathlib import Path

ROOT = Path(r"d:\Project\digital-spirituality\docmind\benchmark\sbr01")
BEFORE = ROOT / "results"
AFTER = ROOT / (sys.argv[1] if len(sys.argv) > 1 else "results_fix2_targeted")
CASES = ["CASE-01", "CASE-04", "CASE-06", "CASE-09", "CASE-11", "CASE-13", "CASE-19"]
HIST_LINE_RE = re.compile(r"^\[T\d+\] Bot: .*$", re.M)


def hist_pipe_count(prompt_text):
    lines = HIST_LINE_RE.findall(prompt_text)
    if not lines:
        return None
    return sum(l.count(" | ") for l in lines)


def analyse(base_dir):
    stats = {"turns": 0, "rawTurnsWithPipe": 0, "postRefereeTurnsWithPipe": 0, "finalTurnsWithPipe": 0,
             "promptsWithHistoryPipeDelimiter": 0, "historyPipeOccurrences": 0, "promptsWithBotHistoryLines": 0}
    rows = []
    for cid in CASES:
        d = json.loads((base_dir / "cases" / f"{cid}_replay_telemetry.json").read_text(encoding="utf-8"))
        for run in d["runs"]:
            for t in run["turns"]:
                stats["turns"] += 1
                op = t["outputPipeline"]
                raw = " ".join(op["raw_llm_output"]["bubbles"])
                post = " ".join(op["post_referee_output"]["bubbles"])
                fin = " ".join(op["final_sent_output"]["bubbles"])
                pf = Path(t["inputAndContext"]["finalDynamicPromptFile"]).name
                ptxt = (base_dir / "prompts" / pf).read_text(encoding="utf-8")
                hp = hist_pipe_count(ptxt)
                if hp is not None and t["inputAndContext"].get("conversationHistoryCount", 0):
                    stats["promptsWithBotHistoryLines"] += 1
                    stats["historyPipeOccurrences"] += hp
                    if hp > 0:
                        stats["promptsWithHistoryPipeDelimiter"] += 1
                stats["rawTurnsWithPipe"] += "|" in raw
                stats["postRefereeTurnsWithPipe"] += "|" in post
                stats["finalTurnsWithPipe"] += "|" in fin
                rows.append({"caseId": cid, "run": run["runIndex"], "turnId": t["turnId"], "historyPipe": hp,
                             "rawPipe": "|" in raw, "finalPipe": "|" in fin, "raw": op["raw_llm_output"]["bubbles"],
                             "final": op["final_sent_output"]["bubbles"]})
    return stats, rows


b, brows = analyse(BEFORE)
a, arows = analyse(AFTER)
out = {"before_baseline_results": b, "after": a, "afterDir": str(AFTER), "cases": CASES,
       "afterTurnsWithRawPipe": [r for r in arows if r["rawPipe"]]}
(AFTER / "sbr01_fix2_pipe_before_after.json").write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
md = ["# SBR-01 FIX-2 — pipe delimiter before/after (generated)", "",
      f"Cases: {', '.join(CASES)} — N=3 each. Before = baseline `results/` (HEAD 1cd09e4 code); After = `{AFTER.name}`.", "",
      "| Metric | Before | After |", "|---|---:|---:|"]
for k in b:
    md.append(f"| {k} | {b[k]} | {a[k]} |")
md += ["", "- `historyPipeOccurrences` counts `' | '` inside the `[Tn] Bot:` history lines of the prompt actually sent (delimiter in prompt).",
       "- `rawTurnsWithPipe` counts model-generated `|` in `raw_llm_output` (imitation), distinct from the prompt delimiter.", ""]
if out["afterTurnsWithRawPipe"]:
    md.append("## After: turns where the model still produced '|'")
    for r in out["afterTurnsWithRawPipe"]:
        md.append(f"- {r['caseId']} run{r['run']} {r['turnId']} historyPipe={r['historyPipe']}: raw={r['raw']} final={r['final']}")
(AFTER / "SBR01_FIX2_PIPE_BEFORE_AFTER.md").write_text("\n".join(md), encoding="utf-8")
print(json.dumps({"before": b, "after": a}, ensure_ascii=False))
