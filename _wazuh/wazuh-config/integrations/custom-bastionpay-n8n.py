#!/var/ossec/framework/python/bin/python3
"""Forward selected Wazuh alerts to the BastionPay n8n webhook."""

import json
import sys
import urllib.error
import urllib.request


def main() -> int:
    if len(sys.argv) < 4:
        print("custom-bastionpay-n8n: expected alert file, API key, and webhook URL", file=sys.stderr)
        return 2

    alert_path, api_key, webhook_url = sys.argv[1:4]
    try:
        with open(alert_path, "r", encoding="utf-8") as alert_file:
            alert = json.load(alert_file)
    except (OSError, json.JSONDecodeError) as exc:
        print(f"custom-bastionpay-n8n: cannot read Wazuh alert JSON: {exc}", file=sys.stderr)
        return 3

    payload = json.dumps({"source": "wazuh", "alert": alert}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        webhook_url,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "X-Wazuh-Webhook-Key": api_key,
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            if not 200 <= response.status < 300:
                print(f"custom-bastionpay-n8n: webhook returned HTTP {response.status}", file=sys.stderr)
                return 4
    except (urllib.error.URLError, TimeoutError) as exc:
        print(f"custom-bastionpay-n8n: webhook request failed: {exc}", file=sys.stderr)
        return 5

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
