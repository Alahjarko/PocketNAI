"""Inspect the actual packaged manifest; no device/emulator or application launch."""
import argparse
import struct
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path


def read_manifest(path):
    if not zipfile.is_zipfile(path):
        return ET.parse(path).getroot()
    # Read typed binary attributes directly. apkanalyzer prints an empty string as
    # @string/0x25 in this build, even though the APK's string-pool entry is empty.
    with zipfile.ZipFile(path) as apk:
        data = apk.read("AndroidManifest.xml")
    u16 = lambda at: struct.unpack_from("<H", data, at)[0]
    u32 = lambda at: struct.unpack_from("<I", data, at)[0]
    assert u16(0) == 3, "Not an Android binary XML document"
    strings, stack, root = [], [], None
    pos = u16(2)
    while pos < len(data):
        kind, header, size = struct.unpack_from("<HHI", data, pos)
        assert size >= header and size > 0 and pos + size <= len(data), "Invalid XML chunk"
        if kind == 1:  # ResStringPool
            count, flags, start = u32(pos + 8), u32(pos + 16), u32(pos + 20)
            for index in range(count):
                at = pos + start + u32(pos + header + index * 4)
                if flags & 0x100:
                    def length(offset):
                        n = data[offset]
                        return (((n & 127) << 8) | data[offset + 1], offset + 2) if n & 128 else (n, offset + 1)
                    _, at = length(at)
                    length_bytes, at = length(at)
                    strings.append(data[at:at + length_bytes].decode("utf-8"))
                else:
                    length_chars = u16(at)
                    at += 2
                    if length_chars & 0x8000:
                        length_chars = ((length_chars & 0x7fff) << 16) | u16(at)
                        at += 2
                    strings.append(data[at:at + length_chars * 2].decode("utf-16le"))
        elif kind == 0x102:  # ResXMLTree_startElement
            element = ET.Element(strings[u32(pos + 20)])
            for index in range(u16(pos + 28)):
                at = pos + 16 + u16(pos + 24) + index * u16(pos + 26)
                namespace, name, raw = u32(at), u32(at + 4), u32(at + 8)
                value_type, value = data[at + 15], u32(at + 16)
                if raw != 0xffffffff:
                    text = strings[raw]
                elif value_type == 3:
                    text = strings[value]
                elif value_type == 18:
                    text = "true" if value else "false"
                elif value_type == 1:
                    text = "@null" if value == 0 else f"@0x{value:08x}"
                else:
                    text = str(value)
                key = strings[name] if namespace == 0xffffffff else "{" + strings[namespace] + "}" + strings[name]
                element.set(key, text)
            if stack:
                stack[-1].append(element)
            else:
                root = element
            stack.append(element)
        elif kind == 0x103:
            stack.pop()
        pos += size
    assert root is not None and root.tag == "manifest", "No manifest root"
    return root


def verify(path, expected_code=None, xml_output=None):
    root = read_manifest(path)
    if xml_output:
        ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
        Path(xml_output).parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(root).write(xml_output, encoding="utf-8", xml_declaration=True)
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
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest")
    parser.add_argument("--version-code")
    parser.add_argument("--xml-output")
    args = parser.parse_args()
    verify(args.manifest, args.version_code, args.xml_output)
