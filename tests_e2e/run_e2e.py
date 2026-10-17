#!/usr/bin/env python3
"""DocMind E2E Test Suite Runner.

Executable zero-dependency standalone runner supporting:
- All test tiers (Tier 1: Feature Coverage, Tier 2: Boundary/Corner, Tier 3: Cross-Feature, Tier 4: Sales Scenarios)
- Feature filtering (--feature F1..F19)
- Tier filtering (--tier 1..4)
- Live vs. Contract mode (--mode live|contract)
- Pytest and unittest compatibility
"""

import argparse
import importlib
import inspect
import os
import sys
import time
import traceback
from typing import Any, Callable, Dict, List, Optional, Tuple

# Add repository root to sys.path so tests_e2e package can be imported
REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from tests_e2e.harness.client import DocMindE2EClient


def parse_args():
    parser = argparse.ArgumentParser(description="DocMind E2E Test Suite Runner")
    parser.add_argument(
        "--tier",
        type=str,
        choices=["1", "2", "3", "4", "5", "all"],
        default="all",
        help="Test tier to execute (1, 2, 3, 4, 5, or all)",
    )
    parser.add_argument(
        "--feature",
        type=str,
        default=None,
        help="Feature code to filter by (e.g. F1, F11, F19)",
    )
    parser.add_argument(
        "--mode",
        type=str,
        choices=["contract", "live", "ollama"],
        default="contract",
        help="Execution mode (contract simulator, ollama local LLM, or live services)",
    )
    parser.add_argument(
        "-v", "--verbose",
        action="store_true",
        help="Enable verbose output",
    )
    return parser.parse_args()


class TestResult:
    def __init__(self, name: str, tier: str, passed: bool, duration: float, error: Optional[str] = None):
        self.name = name
        self.tier = tier
        self.passed = passed
        self.duration = duration
        self.error = error


def run_test_method(test_instance: Any, method_name: str, client: DocMindE2EClient) -> Tuple[bool, Optional[str]]:
    method = getattr(test_instance, method_name)
    sig = inspect.signature(method)
    kwargs = {}
    if "client" in sig.parameters:
        kwargs["client"] = DocMindE2EClient(
            mode=client.mode,
            api_java_url=client.api_java_url,
            doc_service_url=client.doc_service_url,
            ollama_base_url=client.ollama_base_url,
            ollama_model=client.ollama_model,
        )

    try:
        method(**kwargs)
        return True, None
    except Exception as e:
        err_msg = traceback.format_exc()
        return False, err_msg


def collect_and_run_module(
    module_name: str,
    tier_label: str,
    feature_filter: Optional[str],
    client: DocMindE2EClient,
    verbose: bool,
) -> List[TestResult]:
    results: List[TestResult] = []
    try:
        mod = importlib.import_module(module_name)
    except Exception as e:
        print(f"Failed to import {module_name}: {e}")
        return results

    # Discover Test* classes
    for attr_name in dir(mod):
        if not attr_name.startswith("Test"):
            continue
        cls = getattr(mod, attr_name)
        if not isinstance(cls, type):
            continue

        instance = cls()
        test_methods = [m for m in dir(instance) if m.startswith("test_")]

        for method_name in sorted(test_methods):
            # Apply feature filter if specified (e.g. "F1" in "test_f1_...")
            if feature_filter:
                f_prefix = f"_{feature_filter.lower()}_"
                if f_prefix not in method_name.lower():
                    continue

            start_t = time.perf_counter()
            passed, err = run_test_method(instance, method_name, client)
            duration = time.perf_counter() - start_t

            res = TestResult(
                name=f"{attr_name}::{method_name}",
                tier=tier_label,
                passed=passed,
                duration=duration,
                error=err,
            )
            results.append(res)

            status_str = "PASS" if passed else "FAIL"
            if verbose or not passed:
                print(f"[{tier_label}] {res.name} ... {status_str} ({duration*1000:.1f}ms)")
                if not passed:
                    print(f"    ERROR: {err}")

    return results


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8")

    args = parse_args()
    client = DocMindE2EClient(mode=args.mode)

    print("================================================================================")
    print("                      DocMind E2E Test Suite Runner                             ")
    print(f" Mode: {args.mode.upper()} | Tier: {args.tier.upper()} | Feature Filter: {args.feature or 'None'}")
    print("================================================================================")

    modules_to_run = []
    if args.tier in ["1", "all"]:
        modules_to_run.append(("tests_e2e.test_tier1_feature_coverage", "Tier 1: Feature Coverage"))
    if args.tier in ["2", "all"]:
        modules_to_run.append(("tests_e2e.test_tier2_boundary_corner", "Tier 2: Boundary/Corner"))
    if args.tier in ["3", "all"]:
        modules_to_run.append(("tests_e2e.test_tier3_cross_feature", "Tier 3: Cross-Feature"))
    if args.tier in ["4", "all"]:
        modules_to_run.append(("tests_e2e.test_tier4_sales_scenarios", "Tier 4: Sales Scenarios"))
    if args.tier in ["5", "all"]:
        modules_to_run.append(("tests_e2e.test_tier5_ollama_integration", "Tier 5: Ollama qwen2.5 Live"))


    all_results: List[TestResult] = []
    start_total = time.perf_counter()

    for mod_name, tier_label in modules_to_run:
        if not args.verbose:
            print(f"Running {tier_label} ... ", end="", flush=True)
        tier_results = collect_and_run_module(
            mod_name,
            tier_label,
            args.feature,
            client,
            args.verbose,
        )
        all_results.extend(tier_results)
        if not args.verbose:
            tier_passed = sum(1 for r in tier_results if r.passed)
            print(f"{tier_passed}/{len(tier_results)} passed")

    total_duration = time.perf_counter() - start_total
    total_count = len(all_results)
    passed_count = sum(1 for r in all_results if r.passed)
    failed_count = total_count - passed_count

    print("\n================================================================================")
    print("                                SUMMARY REPORT                                  ")
    print("================================================================================")
    tier_groups: Dict[str, List[TestResult]] = {}
    for r in all_results:
        tier_groups.setdefault(r.tier, []).append(r)

    print(f"{'Tier':<30} | {'Total':<8} | {'Passed':<8} | {'Failed':<8} | {'Duration':<10}")
    print("-" * 75)
    for t_name, t_res in tier_groups.items():
        t_tot = len(t_res)
        t_pas = sum(1 for r in t_res if r.passed)
        t_fai = t_tot - t_pas
        t_dur = sum(r.duration for r in t_res)
        print(f"{t_name:<30} | {t_tot:<8} | {t_pas:<8} | {t_fai:<8} | {t_dur*1000:<8.1f}ms")

    print("-" * 75)
    print(f"{'TOTAL':<30} | {total_count:<8} | {passed_count:<8} | {failed_count:<8} | {total_duration*1000:<8.1f}ms")
    print("================================================================================")

    if failed_count == 0 and total_count > 0:
        print("  RESULT: ALL TESTS PASSED (100% SUCCESS RATE)")
        print("================================================================================")
        sys.exit(0)
    else:
        print(f"  RESULT: {failed_count} TEST(S) FAILED")
        print("================================================================================")
        sys.exit(1)


if __name__ == "__main__":
    main()
