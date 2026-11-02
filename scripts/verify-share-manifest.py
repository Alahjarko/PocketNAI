"""Inspect the actual packaged manifest; no device/emulator or application launch."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def verify(path, expected_code=None):
    root = ET.parse(path).getroot()
    ns = "{http://schemas.android.com/apk/res/android}"
    package = root.get("package", "net.pocketnai")

    def name(activity):
        value = activity.get(ns + "name", "")
        return package + value if value.startswith(".") else value

    activities = {name(a): a for a in root.findall("./application/activity")}
    main = activities["net.pocketnai.ui.MainActivity"]
    share = activities["net.pocketnai.ui.ShareImageActivity"]
    assert main.get(ns + "launchMode") in ("singleTask", "2"), "Main must reuse its task"
    assert main.get(ns + "documentLaunchMode") in ("never", "3"), "Main must reject document tasks"
    for key in ("noHistory", "excludeFromRecents"):
        assert main.get(ns + key, "false") == "false", f"Main must not inherit {key}"
        assert share.get(ns + key) == "true", f"Share relay must set {key}"
    # AAPT encodes an explicitly empty affinity as a null reference; apkanalyzer renders @null.
    affinity = share.get(ns + "taskAffinity")
    assert affinity in ("", "@null"), f"Share relay must not claim the main task affinity: {affinity!r}"
    assert share.get(ns + "exported") == "true", "System shares must be able to reach the relay"
    send_targets = []
    for activity_name, activity in activities.items():
        for intent in activity.findall("intent-filter"):
            actions = {a.get(ns + "name") for a in intent.findall("action")}
            if "android.intent.action.SEND" in actions:
                send_targets.append(activity_name)
                assert any(d.get(ns + "mimeType") == "image/*" for d in intent.findall("data"))
    assert send_targets == ["net.pocketnai.ui.ShareImageActivity"], "Only the relay may receive image shares"
    if expected_code is not None:
        assert root.get(ns + "versionCode") == expected_code, "APK versionCode differs from release tag"
        assert root.get(ns + "versionName") == f"0.1.{expected_code}", "Release must not use a dev versionName"
    print(f"Share task contract verified: {Path(path).name}; affinity={affinity!r}; "
          f"versionCode={root.get(ns + 'versionCode')}; versionName={root.get(ns + 'versionName')}")


if __name__ == "__main__":
    verify(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
