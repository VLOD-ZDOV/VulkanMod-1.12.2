#!/usr/bin/env python3
"""Writes docs/FILE-MAP.md from the sources themselves.

A list of what each file does, kept beside the files rather than inside them,
drifts — this project has the scar to prove it. So nothing here is written by
hand. Every line comes out of the source it describes: the summary is the first
sentence of the class comment, the links are the other classes it names, and
the vanilla class a patch attaches to is read off its annotation.

That has a consequence worth stating plainly: a file with no class comment gets
no summary, and the map says so instead of guessing. An empty cell is a missing
comment, not a missing file.

Run it with ./gradlew fileMap, or directly.
"""

import os
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "src", "main", "java")
OUT = os.path.join(ROOT, "docs", "FILE-MAP.md")

# The halves of the mod, in the order somebody reading it for the first time
# should meet them.
AREAS = [
    ("net.vulkanmodnext.mixin",
     "Patches",
     "Where the mod attaches to the game. Every one of these is a place the "
     "game has to keep working without us."),
    ("net.vulkanmodnext.client",
     "The Minecraft side",
     "Runs on the game's own class loader with LWJGL 2. Knows what a block is, "
     "and nothing about Vulkan."),
    ("net.vulkanmodnext.vkimpl",
     "The Vulkan side",
     "Runs on its own class loader with LWJGL 3. Knows nothing about Minecraft "
     "beyond the vertex format and the bridge interface."),
    ("net.vulkanmodnext",
     "Shared",
     "The little that both halves are allowed to see."),
]

CLASS_DECL = re.compile(
    r"^(?:public\s+|final\s+|abstract\s+|)*"
    r"(class|interface|enum)\s+(\w+)", re.M)
MIXIN_TARGET = re.compile(r"@Mixin\s*\(\s*(?:value\s*=\s*)?\{?\s*([\w.]+)\.class")
MIXIN_TARGET_STRING = re.compile(r"targets\s*=\s*\"([^\"]+)\"")


def first_sentence(comment):
    """The opening sentence of a class comment, as prose.

    Deliberately the first sentence and not the first paragraph: these comments
    are long on purpose, and a map made of paragraphs is not a map.
    """
    text = []
    for line in comment.splitlines():
        line = line.strip()
        line = re.sub(r"^/\*\*+", "", line)
        line = re.sub(r"\*/$", "", line)
        line = re.sub(r"^\*\s?", "", line)
        if line.startswith("@"):
            break
        text.append(line)
    joined = " ".join(text)
    joined = re.sub(r"<h2>.*?</h2>", " ", joined, flags=re.S)
    joined = re.sub(r"<[^>]+>", " ", joined)
    joined = re.sub(r"\{@\w+\s+([^}]*)\}", r"\1", joined)
    joined = re.sub(r"\s+", " ", joined).strip()
    if not joined:
        return ""
    # Not every full stop ends a sentence: "1.12.2" and "e.g." are the ones
    # that actually occur in these comments.
    for match in re.finditer(r"\.(?=\s|$)", joined):
        at = match.start()
        if at > 0 and joined[at - 1].isdigit() and at + 2 < len(joined) \
                and joined[at + 1] == " " and joined[at + 2].islower():
            continue
        return joined[:at + 1]
    return joined


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def class_comment(text, name):
    """The comment block immediately above the declaration of `name`."""
    decl = re.search(r"(?:class|interface|enum)\s+" + re.escape(name) + r"\b", text)
    if not decl:
        return ""
    before = text[:decl.start()]
    closing = before.rfind("*/")
    if closing < 0:
        return ""
    opening = before.rfind("/**", 0, closing)
    if opening < 0:
        return ""
    # Only if nothing but modifiers and annotations sit between them. A
    # semicolon or a brace there means the comment belongs to something else.
    # Line comments and string literals are cut out first: an annotation
    # argument is allowed to contain a semicolon, and one of ours does.
    between = before[closing + 2:]
    between = re.sub(r"//[^\n]*", "", between)
    between = re.sub(r'"(?:\\.|[^"\\])*"', "", between)
    if re.search(r"[;}]", between):
        return ""
    return before[opening:closing + 2]


def collect():
    files = []
    for folder, _, names in os.walk(SOURCE):
        for name in sorted(names):
            if not name.endswith(".java"):
                continue
            path = os.path.join(folder, name)
            text = read(path)
            package = re.search(r"^package\s+([\w.]+);", text, re.M)
            simple = name[:-5]
            decl = CLASS_DECL.search(text)
            kind = decl.group(1) if decl else "class"
            target = None
            found = MIXIN_TARGET.search(text)
            if found:
                target = found.group(1).split(".")[-1]
            else:
                found = MIXIN_TARGET_STRING.search(text)
                if found:
                    target = found.group(1).split(".")[-1]
            files.append({
                "name": simple,
                "path": os.path.relpath(path, ROOT),
                "package": package.group(1) if package else "",
                "kind": kind,
                "lines": text.count("\n") + 1,
                "summary": first_sentence(class_comment(text, simple)),
                "target": target,
                "text": text,
            })
    return files


def link(files):
    """Who names whom. A map, not a proof: a name in a comment counts too."""
    known = {entry["name"]: entry for entry in files}
    uses = defaultdict(set)
    used_by = defaultdict(set)
    for entry in files:
        body = entry["text"]
        for word in set(re.findall(r"\b[A-Z]\w+\b", body)):
            if word == entry["name"] or word not in known:
                continue
            uses[entry["name"]].add(word)
            used_by[word].add(entry["name"])
    return uses, used_by


def area_of(package):
    for prefix, title, _ in AREAS:
        if package == prefix or package.startswith(prefix + "."):
            return title
    return "Shared"


def main():
    files = collect()
    if not files:
        print("no sources found under " + SOURCE, file=sys.stderr)
        return 1
    uses, used_by = link(files)

    by_area = defaultdict(list)
    for entry in files:
        by_area[area_of(entry["package"])].append(entry)

    out = []
    out.append("# What each file is\n")
    out.append("**Generated by `tools/file-map.py` — do not edit.** "
               "Run `./gradlew fileMap` after moving or adding a file.\n")
    out.append("Every summary below is the first sentence of that file's own class comment. "
               "A blank one means the file has no class comment, which is worth knowing on "
               "its own.\n")
    out.append("| | |\n|---|---|")
    out.append("| Files | %d |" % len(files))
    out.append("| Lines | %d |" % sum(entry["lines"] for entry in files))
    out.append("")

    hubs = sorted(files, key=lambda entry: -len(used_by[entry["name"]]))[:10]
    out.append("## Named by the most other files\n")
    out.append("The ones to read first, and the ones a change is most likely to reach.\n")
    out.append("| File | Named by | Lines |")
    out.append("|---|---:|---:|")
    for entry in hubs:
        out.append("| `%s` | %d | %d |"
                   % (entry["name"], len(used_by[entry["name"]]), entry["lines"]))
    out.append("")

    for _, title, blurb in AREAS:
        entries = by_area.get(title)
        if not entries:
            continue
        out.append("## %s\n" % title)
        out.append(blurb + "\n")
        if title == "Patches":
            out.append("| File | Patches | What it is for | Lines |")
            out.append("|---|---|---|---:|")
            for entry in sorted(entries, key=lambda e: e["name"]):
                out.append("| `%s` | `%s` | %s | %d |"
                           % (entry["name"], entry["target"] or "—",
                              entry["summary"], entry["lines"]))
        else:
            out.append("| File | What it is for | Named by | Lines |")
            out.append("|---|---|---:|---:|")
            for entry in sorted(entries, key=lambda e: e["name"]):
                out.append("| `%s` | %s | %d | %d |"
                           % (entry["name"], entry["summary"],
                              len(used_by[entry["name"]]), entry["lines"]))
        out.append("")

    missing = [entry["name"] for entry in files if not entry["summary"]]
    out.append("## Files with no class comment\n")
    if missing:
        out.append("%d of %d. Each is a file whose reason for existing is written down "
                   "nowhere.\n" % (len(missing), len(files)))
        out.append(", ".join("`%s`" % name for name in sorted(missing)))
    else:
        out.append("None.")
    out.append("")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as handle:
        handle.write("\n".join(out))
    print("wrote %s: %d files, %d without a class comment"
          % (os.path.relpath(OUT, ROOT), len(files), len(missing)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
