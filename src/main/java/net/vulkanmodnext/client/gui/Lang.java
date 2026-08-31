package net.vulkanmodnext.client.gui;

import net.minecraft.client.resources.I18n;

/**
 * Translates the settings screen, keyed off the English text already in the
 * code rather than off keys written beside it.
 *
 * The screen is built from about thirty options, each with a name, a paragraph
 * of description and sometimes a note about when it takes effect, plus page and
 * group titles and the chrome around them. Turning that into keys the usual way
 * means a second string next to every first one, and it means the code stops
 * saying what it puts on screen.
 *
 * Here the key is derived from the English: {@code "Dynamic Lights"} becomes
 * {@code vulkanmodnext.option.dynamic_lights}, and its description
 * {@code vulkanmodnext.tooltip.dynamic_lights}. The English stays in the source
 * as the argument it always was, and doubles as the fallback.
 *
 * <h2>What that buys, and what it costs</h2>
 *
 * A missing or misspelled key cannot produce a blank row or a raw key on
 * screen: nothing translated simply reads as English, which is what an
 * untranslated mod looks like anyway. A language file can therefore be partial,
 * and a new option is translatable without being translated.
 *
 * The cost is that renaming an option in English silently orphans its
 * translations. That is a real risk and the reason for
 * {@code -Dvulkanmodnext.dumpLang=true}: it writes out every key the screen
 * actually asks for, taken from the live objects rather than from parsing the
 * source, which is how the English file is generated in the first place and how
 * a rename is caught.
 */
public final class Lang {

    public static final String OPTION = "vulkanmodnext.option.";
    public static final String TOOLTIP = "vulkanmodnext.tooltip.";
    public static final String APPLIES = "vulkanmodnext.applies.";
    public static final String PAGE = "vulkanmodnext.page.";
    public static final String GROUP = "vulkanmodnext.group.";
    public static final String VALUE = "vulkanmodnext.value.";
    public static final String UNIT = "vulkanmodnext.unit.";
    public static final String COST = "vulkanmodnext.cost.";
    public static final String UI = "vulkanmodnext.ui.";

    /** What {@code Locale.formatMessage} returns instead of throwing. */
    private static final String FORMAT_ERROR = "Format error: ";

    private Lang() {
    }

    /**
     * @param prefix  one of the constants above
     * @param slugOf  the English text the key is derived from — the option's
     *                name for its description and its applies-when note, so
     *                that a paragraph is not turned into a key
     * @param english what to show when nothing is translated
     */
    public static String tr(String prefix, String slugOf, String english) {
        if (english == null) {
            return null;
        }
        String key = prefix + slug(slugOf);
        if (!I18n.hasKey(key)) {
            return english;
        }
        String translated = I18n.format(key);
        // A key present but empty would blank the row; treat it as absent.
        if (translated.isEmpty()) {
            return english;
        }
        // The game runs every translation through String.format, so a literal
        // percent sign has to be written %% in a language file — and when it is
        // not, the game hands back "Format error: " with the whole paragraph
        // stuck to it rather than throwing. Two of these descriptions quote a
        // percentage, so this is reachable by an ordinary translation mistake.
        // English is a better answer than that.
        return translated.startsWith(FORMAT_ERROR) ? english : translated;
    }

    public static String tr(String prefix, String english) {
        return tr(prefix, english, english);
    }

    /** The key a piece of English text maps to, without looking it up. */
    public static String key(String prefix, String slugOf) {
        return prefix + slug(slugOf);
    }

    /**
     * Lower case, runs of anything that is not a letter or digit collapsed to
     * one underscore, ends trimmed. {@code "Max Framerate"} to
     * {@code max_framerate}, {@code "/100 block"} to {@code 100_block}.
     */
    public static String slug(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean pendingSeparator = false;
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                if (pendingSeparator && out.length() > 0) {
                    out.append('_');
                }
                pendingSeparator = false;
                out.append(c);
            } else {
                pendingSeparator = true;
            }
        }
        return out.toString();
    }

    /**
     * The mod's key bindings, in pairs of key and English name.
     *
     * Written out literally because Minecraft names these itself — nothing here
     * derives them from English text the way every other string in this class
     * does, so the generator has to be told about them or they vanish from the
     * file it generates.
     */
    private static final String[] KEY_STRINGS = {
            "key.categories.vulkanmodnext", "VulkanModNext",
            "key.vulkanmodnext.zoom", "Zoom",
            "key.vulkanmodnext.settings", "VulkanModNext Settings",
            "key.vulkanmodnext.probe", "VulkanModNext Chunk Probe",
            "key.vulkanmodnext.nextProfile", "VulkanModNext Next Settings Profile",
    };

    /** Chrome that belongs to no option. */
    private static final String[] UI_STRINGS = {
            "Against this setting off. Blue gives back.",
            "Reset", "Cost", "CPU", "GPU", "VRAM", "Vulkan unavailable",
            "Profiles", "Save As", "Load", "Rename", "Delete", "Saved", "Loaded", "Renamed",
            "That name is taken", "Could not write that profile", "Could not read that profile",
            "No profiles yet — type a name below and press Save As",
            "Everything this mod owns, plus the game's own settings that decide the frame rate",
            "Search settings",
    };

    /**
     * The page and headings the search builds, which no page object carries.
     *
     * Everything else here is dumped by walking the live options, precisely so
     * that it cannot drift — but the search's results are assembled on the fly
     * and belong to no page, so walking finds nothing. Written out by hand is
     * the only way they reach a language file at all, and the alternative is
     * three rows of English in the middle of a translated screen.
     */
    private static final String[] SEARCH_STRINGS = {
            "Search", "Search results", "Nothing found",
    };

    /**
     * Writes every key the screen can ask for, with its English text, in the
     * format of a 1.12.2 language file.
     *
     * Taken from the live option objects rather than by reading the source, so
     * it cannot drift from what is actually drawn — which is the point, because
     * the keys are derived from English text and renaming an option in English
     * silently orphans its translations. Run with
     * {@code -Dvulkanmodnext.dumpLang=true} and compare.
     */
    public static String dump(VOptionPage[] pages) {
        StringBuilder out = new StringBuilder(16384);
        out.append("# Generated with -Dvulkanmodnext.dumpLang=true. Keys are derived from the\n");
        out.append("# English text in the source; see Lang.\n");
        // Key bindings are named by Minecraft's own key format, not by this
        // class's derived keys — so they have to be written here or the
        // generated file loses them. It did once: regenerating dropped both,
        // and Controls showed players the raw key string instead of a name.
        for (int i = 0; i < KEY_STRINGS.length; i += 2) {
            out.append(KEY_STRINGS[i]).append('=').append(KEY_STRINGS[i + 1]).append('\n');
        }
        for (String ui : UI_STRINGS) {
            emit(out, UI, ui, ui);
        }
        emit(out, PAGE, SEARCH_STRINGS[0], SEARCH_STRINGS[0]);
        emit(out, GROUP, SEARCH_STRINGS[1], SEARCH_STRINGS[1]);
        emit(out, GROUP, SEARCH_STRINGS[2], SEARCH_STRINGS[2]);
        for (VOption.Level level : VOption.Level.values()) {
            emit(out, COST, level.label, level.label);
        }
        for (VOptionPage page : pages) {
            out.append('\n');
            emit(out, PAGE, page.name, page.name);
            for (VOptionBlock block : page.blocks) {
                emit(out, GROUP, block.title, block.title);
                for (VOption option : block.options) {
                    String name = option.englishName();
                    emit(out, OPTION, name, name);
                    emit(out, TOOLTIP, name, option.englishTooltip());
                    emit(out, APPLIES, name, option.englishAppliesWhen());
                    if (option instanceof VRangeOption) {
                        VRangeOption range = (VRangeOption) option;
                        emit(out, UNIT, range.englishSuffix(), range.englishSuffix());
                        emit(out, VALUE, range.englishMinText(), range.englishMinText());
                    } else if (option instanceof VCyclingOption) {
                        for (String value : ((VCyclingOption) option).englishValues()) {
                            emit(out, VALUE, value, value);
                        }
                    } else if (option instanceof VActionOption) {
                        String button = ((VActionOption) option).englishButtonText();
                        emit(out, VALUE, button, button);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * The null check comes before the key is built, not after. A range with no
     * text for its minimum has none, and slugging that threw an exception
     * inside the screen's own construction — a dump that takes the screen down
     * with it is worse than no dump.
     */
    private static void emit(StringBuilder out, String prefix, String slugOf, String english) {
        if (english == null || english.isEmpty() || slugOf == null || slugOf.isEmpty()) {
            return;
        }
        String key = key(prefix, slugOf);
        if (key.endsWith(".")) {
            return;
        }
        if (out.indexOf("\n" + key + "=") >= 0 || out.indexOf(key + "=") == 0) {
            return;
        }
        // Written the way the game will read it back: a literal percent has to
        // be doubled, because every value goes through String.format.
        out.append(key).append('=')
                .append(english.replace("\n", " ").replace("%", "%%"))
                .append('\n');
    }
}
