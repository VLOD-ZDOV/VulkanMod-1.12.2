#!/usr/bin/env python3
"""Lifts named members out of a 1.12.2 source file, comments and all.

Some of the Vulkan half is not a class that travels whole. `VulkanContextImpl`
is the clearest case: half of it brings up Vulkan — instance, device, queues,
the choice of card — and half of it is a facade over a renderer that does not
exist here yet. Copying the whole thing would drag the renderer with it;
retyping the half that is wanted would lose the comments, and in this project
the comments are the part that took longest to earn.

So the wanted members are named, and lifted verbatim: the declaration, its
body, and the comment block immediately above it.

Usage: tools/extract-members.py <source.java> <member> [<member>...]
Prints the members to stdout, in the order asked for.
"""

import io
import os
import re
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE_DIR = os.path.join(os.path.dirname(HERE),
                          "src", "main", "java", "net", "vulkanmodnext", "vkimpl")


def comment_above(lines, at):
    """The javadoc or line comments directly above, if any."""
    start = at
    while start > 0:
        previous = lines[start - 1].strip()
        if previous.endswith("*/") or previous.startswith("//"):
            # Walk to the top of the block.
            start -= 1
            if previous.endswith("*/"):
                while start > 0 and not lines[start].strip().startswith("/*"):
                    start -= 1
            continue
        if previous.startswith("@"):
            start -= 1
            continue
        break
    return start


def lift(text, member):
    lines = text.splitlines()
    # A declaration, not a call: the name followed by '(' for a method, or by
    # '=' or ';' for a field, at one level of indentation.
    # Exactly one level of indentation and no more: at four spaces this is a
    # member of the class, at eight it is a line inside somebody's body, and
    # the first attempt at this happily returned a call site.
    pattern = re.compile(r"^ {4}(?! )[^/*@].*\b" + re.escape(member) + r"\s*[(=;]")
    for i, line in enumerate(lines):
        if not pattern.match(line):
            continue
        start = comment_above(lines, i)
        after = line.split(member, 1)[1].lstrip()
        if not after.startswith("("):
            # A field. It may well run over several lines — the first attempt
            # here assumed one, fell through to brace matching, and swallowed
            # the two methods that followed it into the declaration.
            for j in range(i, len(lines)):
                if lines[j].rstrip().endswith(";"):
                    return "\n".join(lines[start:j + 1])
            return None
        # Brace matching from the declaration.
        depth = 0
        seen = False
        for j in range(i, len(lines)):
            depth += lines[j].count("{") - lines[j].count("}")
            if "{" in lines[j]:
                seen = True
            if seen and depth <= 0:
                return "\n".join(lines[start:j + 1])
        break
    return None


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    path = argv[0]
    if not os.path.isabs(path):
        path = os.path.join(SOURCE_DIR, path)
    text = io.open(path, encoding="utf-8").read()
    missing = []
    out = []
    for member in argv[1:]:
        lifted = lift(text, member)
        if lifted is None:
            missing.append(member)
        else:
            out.append(lifted)
    if missing:
        print("not found: " + ", ".join(missing), file=sys.stderr)
        return 1
    print("\n\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
