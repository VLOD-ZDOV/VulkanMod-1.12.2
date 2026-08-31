package net.vulkanmod112.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Finds a setting by typing part of what it is called, or part of what it does.
 *
 * <h2>Why the descriptions are searched too</h2>
 *
 * There are six pages here and close to a hundred rows, and the name of a
 * setting is the worst thing to look for it by: somebody hunting for the reason
 * their grass stands still does not know that the row is called Foliage Sway.
 * They know the word "grass", and "grass" is in that row's description, which
 * is where half the answers in this menu live. A search that only read names
 * would find nothing for every question actually worth asking.
 *
 * <h2>Why a typo still finds it</h2>
 *
 * Because the alternative is an empty list, and an empty list is
 * indistinguishable from "this mod cannot do that". Three kinds of near-miss
 * are allowed, in order of how much they are trusted:
 *
 * <ul>
 *   <li>the letters typed appear in order but not together — "wtrfl" reaches
 *       Water Reflections;</li>
 *   <li>a word is one or two edits from a word in the row — an inserted,
 *       deleted, swapped or wrong letter;</li>
 *   <li>the same, but in the description rather than in the name.</li>
 * </ul>
 *
 * Every one of those scores below a plain match, so a real hit is never pushed
 * down the list by a guess. The edit distance stops at two on purpose: at three
 * a four-letter word matches almost anything, and a list of everything is the
 * same as no list.
 *
 * <h2>Why both languages</h2>
 *
 * The rows are translated and the keys are not, so a Russian player typing
 * "вода" and an English one typing "water" are asking the same question of
 * different strings. Both are searched, which costs one more pass over text
 * that is already in memory and means neither of them has to know which
 * language the mod was written in.
 */
public final class OptionSearch {

    /** Longer than this and the list stops being a list. */
    private static final int MAX_RESULTS = 40;
    /** Below this a match is a coincidence rather than an answer. */
    private static final int FLOOR = 20;

    private OptionSearch() {
    }

    /** One row and how well it answered, so the list can be ordered. */
    private static final class Hit {
        final VOption option;
        final int score;

        Hit(VOption option, int score) {
            this.option = option;
            this.score = score;
        }
    }

    /**
     * The rows matching {@code query}, as a page that draws like any other.
     *
     * A page rather than a special mode: the screen already knows how to draw
     * one, scroll it, hover it and click it, and none of that wants a second
     * implementation that can drift from the first.
     *
     * @return null when the query is too short to mean anything
     */
    public static VOptionPage page(VOptionPage[] pages, String query) {
        String q = normalise(query);
        if (q.length() < 2) {
            return null;
        }
        List<Hit> hits = new ArrayList<Hit>();
        for (VOptionPage page : pages) {
            for (VOptionBlock block : page.blocks) {
                for (VOption option : block.options) {
                    int score = score(option, q);
                    if (score >= FLOOR) {
                        hits.add(new Hit(option, score));
                    }
                }
            }
        }
        Collections.sort(hits, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                return b.score - a.score;
            }
        });
        int keep = Math.min(hits.size(), MAX_RESULTS);
        VOption[] found = new VOption[keep];
        for (int i = 0; i < keep; i++) {
            found[i] = hits.get(i).option;
        }
        // A fixed heading rather than one with the count in it: a heading is a
        // translation key, and a key that changes with the answer is a key no
        // language file can ever hold. How many there are is already on screen.
        String heading = found.length == 0 ? "Nothing found" : "Search results";
        return new VOptionPage("Search", new VOptionBlock(heading, found));
    }

    /**
     * How well one row answers the query, or zero.
     *
     * The name is worth several times the description on purpose. A word in a
     * description is often incidental — "water" appears in the tooltip of half
     * the rows on the surfaces page — while a word in a name is what the row
     * is.
     */
    private static int score(VOption option, String q) {
        int best = 0;
        best = Math.max(best, inName(normalise(option.englishName()), q));
        best = Math.max(best, inName(normalise(option.name()), q));
        best = Math.max(best, inText(normalise(option.englishTooltip()), q) / 4);
        best = Math.max(best, inText(normalise(option.tooltip()), q) / 4);
        best = Math.max(best, inValues(option, q));
        return best;
    }

    /**
     * What a row can be set to, not only what it is called.
     *
     * Reported as "the time switch is not in the search": it is, and it is
     * called Time Control — but somebody looking for it looks for the answer
     * rather than the question, and types "fixed" or "frozen", which are the
     * words on the row and nowhere in its name or its description. Same for
     * "off", which is the whole of what half these rows do.
     *
     * Scored below a name and above a description. A value is a short, chosen
     * word rather than incidental prose, so it deserves more than a tooltip
     * hit; but a row whose *name* matches is still the better answer.
     */
    // Package-private for the test: everything it touches is pure English
    // text, while score() above goes through the translation table and so
    // needs a running game.
    static int inValues(VOption option, String q) {
        int best = 0;
        if (option instanceof VCyclingOption) {
            for (String value : ((VCyclingOption) option).englishValues()) {
                best = Math.max(best, inName(normalise(value), q) / 2);
            }
        } else if (option instanceof VRangeOption) {
            String off = ((VRangeOption) option).englishMinText();
            if (off != null) {
                best = Math.max(best, inName(normalise(off), q) / 2);
            }
        }
        return best;
    }

    private static int inName(String name, String q) {
        if (name.isEmpty()) {
            return 0;
        }
        if (name.startsWith(q)) {
            return 120;
        }
        if (name.contains(q)) {
            return 100;
        }
        int words = wordScore(name, q);
        if (words > 0) {
            return words;
        }
        return subsequence(name, q) ? 40 : 0;
    }

    private static int inText(String text, String q) {
        if (text.isEmpty()) {
            return 0;
        }
        if (text.contains(q)) {
            return 100;
        }
        return wordScore(text, q);
    }

    /**
     * The best any single word of {@code text} does against the query, allowing
     * one or two edits.
     *
     * Short words are held to a stricter standard than long ones: two edits
     * turn "fog" into a great many other three-letter words, and one edit
     * already covers the mistakes people actually make when typing one.
     */
    private static int wordScore(String text, String q) {
        int best = 0;
        int from = 0;
        while (from <= text.length()) {
            int space = text.indexOf(' ', from);
            int end = space < 0 ? text.length() : space;
            if (end > from) {
                String word = text.substring(from, end);
                int allowed = word.length() >= 6 ? 2 : (word.length() >= 4 ? 1 : 0);
                if (allowed > 0 && Math.abs(word.length() - q.length()) <= allowed) {
                    int d = editDistance(word, q, allowed);
                    if (d <= allowed) {
                        best = Math.max(best, d == 1 ? 70 : 50);
                    }
                }
                if (word.startsWith(q) && q.length() >= 3) {
                    best = Math.max(best, 80);
                }
            }
            if (space < 0) {
                break;
            }
            from = space + 1;
        }
        return best;
    }

    /** Are the letters of {@code q} present in order, with gaps allowed. */
    private static boolean subsequence(String text, String q) {
        int at = 0;
        for (int i = 0; i < text.length() && at < q.length(); i++) {
            if (text.charAt(i) == q.charAt(at)) {
                at++;
            }
        }
        return at == q.length();
    }

    /**
     * Levenshtein distance, abandoned once it passes {@code limit}.
     *
     * Two rows of integers rather than a full table: the answer only ever needs
     * the row above, and the rows here are a couple of dozen characters at the
     * outside. Giving up early matters more than it looks — this runs over
     * every word of every description on every keystroke.
     */
    private static int editDistance(String a, String b, int limit) {
        int n = b.length();
        int[] prev = new int[n + 1];
        int[] cur = new int[n + 1];
        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowBest = cur[0];
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowBest = Math.min(rowBest, cur[j]);
            }
            if (rowBest > limit) {
                return limit + 1;
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[n];
    }

    /**
     * Down to letters, digits and single spaces.
     *
     * Case and punctuation are noise here — nobody types the em dash in a
     * tooltip — and lowercasing is done without a locale on purpose: a Turkish
     * locale lowercases I to a dotless ı, which would stop "Ice" matching
     * "ice" for exactly the players least able to guess why.
     */
    private static String normalise(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
                space = false;
            } else if (!space && out.length() > 0) {
                out.append(' ');
                space = true;
            }
        }
        return out.toString().trim();
    }
}
