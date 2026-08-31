#!/usr/bin/env python3
"""Generates the port's settings table from the 1.12.2 mod's own declarations.

A hundred and seven settings, each with a category, a default, a range and a
sentence describing it. Retyping them would be a hundred and seven chances to
get a default wrong, and a wrong default is invisible: the setting simply
behaves differently than the one the player knows from the other version.

So they are lifted, with their words. What cannot be lifted is which of them
actually does anything here — that is a fact about this port, not about the
declarations — so it is carried in a list in this file and printed as part of
each entry.
"""

import io
import os
import re
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(os.path.dirname(HERE), "src", "main", "java",
                      "net", "vulkanmodnext", "client", "VulkanConfig.java")
OUT = os.path.join(HERE, "src", "main", "java", "net", "vulkanmodnext", "client",
                   "Settings.java")

# What is actually wired up in the port today. Everything else is declared,
# remembered and honestly labelled as not yet doing anything — see Settings.
LIVE = {
    "terrainEnabled",
    "compactVertices",
    "groupQuadFacings",
    "cullingEnabled",
    "depthBlitEnabled",
    "flatBlockColours",
    "motionOverWorld",
    "hdrFrame",
    "sceneOcclusion",
    "showAccumulation",
    "showCreatureLight",
    "showMaterials",
    "showMotion",
    "showOcclusion",
    "showReflections",
    "framesInFlight",
    "geometryBudgetMiB",
    "vulkanDevice",
    "atlasPixelsSeen",
}

# Defaults that differ here, each with the reason. On 1.12.2 the terrain switch
# is on, because the terrain draw there is finished and measured. Here it is the
# newest thing in the port and has never been shown to draw a correct frame, so
# it starts off: a mod that replaces the world's rendering by default, before
# anybody has seen it work, is a mod that gets uninstalled.
OVERRIDE_DEFAULT = {
    "terrainEnabled": "false",
}

# Settings the port has wired up and can steer. Everything else is remembered
# and honestly labelled as doing nothing yet.

INT = re.compile(
    r'config\.getInt\(\s*"(\w+)",\s*CATEGORY_(\w+),\s*(\w+),\s*(-?\d+),\s*(-?\d+),\s*'
    r'("(?:[^"\\]|\\.)*")', re.S)
BOOL = re.compile(
    r'config\.getBoolean\(\s*"(\w+)",\s*CATEGORY_(\w+),\s*(\w+),\s*'
    r'("(?:[^"\\]|\\.)*")', re.S)
DEFAULT = re.compile(r'static final (?:int|boolean) (DEF_\w+)\s*=\s*([^;]+);')


def main():
    text = io.open(SOURCE, encoding="utf-8").read()
    defaults = {name: value.strip() for name, value in DEFAULT.findall(text)}

    rows = []
    for key, category, default, low, high, words in INT.findall(text):
        value = OVERRIDE_DEFAULT.get(key, defaults.get(default, "0"))
        rows.append((category, key, "int", value, low, high, words))
    for key, category, default, words in BOOL.findall(text):
        value = OVERRIDE_DEFAULT.get(key, defaults.get(default, "false"))
        rows.append((category, key, "bool", value, "0", "1", words))

    order = {"GENERAL": 0, "QUALITY": 1, "OPTIMIZATION": 2, "ADVANCED": 3}
    rows.sort(key=lambda r: (order.get(r[0], 9), r[1]))

    out = ['''package net.vulkanmodnext.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every setting the mod has, declared once.
 *
 * <h2>Generated, and why</h2>
 *
 * This file is written by {@code tools/lift-settings.py} out of the 1.12.2
 * mod's own declarations — the keys, the categories, the defaults, the ranges
 * and the sentences describing them. Retyping a hundred and seven settings
 * would be a hundred and seven chances to get a default wrong, and a wrong
 * default is the invisible kind of wrong: the setting works, and behaves
 * differently from the one the player already knows.
 *
 * <h2>Declared is not the same as working</h2>
 *
 * Most of these do nothing here yet, because the part of the mod they steer has
 * not been ported. They are listed anyway, and each one says which it is. That
 * is the whole point of showing them: a menu that hides what is missing tells a
 * player the port is further along than it is, and a menu whose switches
 * silently do nothing is worse than one that has none.
 */
public final class Settings {

    public enum Category { GENERAL, QUALITY, OPTIMIZATION, ADVANCED }

    /** One setting: what it is called, what it may hold, and whether it bites. */
    public static final class Setting {

        public final String key;
        public final Category category;
        public final boolean bool;
        public final int min;
        public final int max;
        public final int fallback;
        public final String description;
        /** False when this is declared and remembered but steers nothing yet. */
        public final boolean live;

        Setting(String key, Category category, boolean bool, int min, int max, int fallback,
                boolean live, String description) {
            this.key = key;
            this.category = category;
            this.bool = bool;
            this.min = min;
            this.max = max;
            this.fallback = fallback;
            this.live = live;
            this.description = description;
        }

        /** A readable name, made from the key rather than kept beside it. */
        public String title() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                if (i == 0) {
                    out.append(Character.toUpperCase(c));
                } else if (Character.isUpperCase(c)) {
                    out.append(' ').append(c);
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }

    private static final List<Setting> ALL = new ArrayList<>();

    private static void add(String key, Category category, boolean bool, int min, int max,
                            int fallback, boolean live, String description) {
        ALL.add(new Setting(key, category, bool, min, max, fallback, live, description));
    }

    public static List<Setting> all() {
        return Collections.unmodifiableList(ALL);
    }

    public static List<Setting> of(Category category) {
        List<Setting> out = new ArrayList<>();
        for (Setting setting : ALL) {
            if (setting.category == category) {
                out.add(setting);
            }
        }
        return out;
    }

    /** How many of them actually steer something in this port. */
    public static int liveCount() {
        int n = 0;
        for (Setting setting : ALL) {
            if (setting.live) {
                n++;
            }
        }
        return n;
    }

    private Settings() {
    }

    static {''']

    for category, key, kind, default, low, high, words in rows:
        if kind == "bool":
            value = "1" if default.strip() == "true" else "0"
            out.append('        add("%s", Category.%s, true, 0, 1, %s, %s,\n                %s);'
                       % (key, category, value, str(key in LIVE).lower(), words))
        else:
            out.append('        add("%s", Category.%s, false, %s, %s, %s, %s,\n                %s);'
                       % (key, category, low, high, default, str(key in LIVE).lower(), words))

    out.append("    }")
    out.append("}")
    io.open(OUT, "w", encoding="utf-8").write("\n".join(out) + "\n")
    print("wrote %s: %d settings, %d of them live"
          % (os.path.relpath(OUT, HERE), len(rows), sum(1 for r in rows if r[1] in LIVE)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
