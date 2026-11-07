"""Read-only Page preflight. Never logs credentials or accepts a name as binding proof."""
import json
import pathlib
import subprocess
import urllib.error
import urllib.request


def main():
    container = json.loads(subprocess.check_output(
        ["docker", "inspect", "docmind-api"], encoding="utf-8"))[0]
    env = dict(value.split("=", 1) for value in container["Config"]["Env"])
    target = env.get("DOCMIND_FANPAGE_DEFAULT_PAGE_ID", "")
    token = env.get("DOCMIND_FANPAGE_PAGE_ACCESS_TOKEN", "")
    result = {"configured_default": target, "allowlist": env.get("SALES_CORE_PAGE_ALLOWLIST"),
              "binding_status": "UNVERIFIED", "destructive_allowed": False}
    if token:
        request = urllib.request.Request("https://graph.facebook.com/v21.0/me?fields=id,name",
                                         headers={"Authorization": "Bearer " + token})
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                result["token_page_identity"] = json.load(response)
        except urllib.error.HTTPError as error:
            result["graph_http_status"] = error.code
            try:
                details = json.loads(error.read()).get("error", {})
                result["graph_error_code"] = details.get("code")
                result["graph_error_subcode"] = details.get("error_subcode")
            except (ValueError, AttributeError):
                pass
        except (urllib.error.URLError, TimeoutError):
            result["graph_error"] = "NETWORK_OR_TIMEOUT"
    else:
        result["graph_error"] = "MISSING_PAGE_TOKEN"
    # Numeric Page IDs are currently discarded by resolveEffectivePageId. A valid
    # /me response alone does not prove the webhook subscription's knowledge UUID.
    result["reason"] = "Runtime has a default UUID, not a persisted numeric Messenger Page binding; validate callback scope before deletion."
    destination = pathlib.Path("artifacts/v2-pr0/page-binding.json")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
