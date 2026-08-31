package net.vulkanmodnext.client;

import net.vulkanmodnext.Tags;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a newer build of this mod exists, asked once and never again.
 *
 * <h2>Two places, both asked, and the order still matters</h2>
 *
 * Both are asked every time rather than the second only when the first fails,
 * and that is the difference between two sources and one with a spare. A build
 * can be on one and not yet on the other — published to the repository and not
 * uploaded to the page, or the reverse — and a check that stops at the first
 * answer reports the older of the two as the newest there is.
 *
 * So the highest version either of them names is the answer, and the link is
 * decided separately: it points at CurseForge whenever CurseForge has that
 * version, because a download there is worth something to whoever wrote this,
 * and at the repository's releases when it does not — whether because the file
 * is not up there yet or because the service did not answer at all. Sending
 * somebody to a page that has not got what they were just told about is a
 * worse outcome than losing the click.
 *
 * <h2>What leaves the machine</h2>
 *
 * Two GET requests with no query, no body and no identifier of any kind. The
 * only thing said about the caller is a user agent naming this mod and its
 * version, which GitHub refuses a request without. Nothing about the machine,
 * the player, the world or the other mods is collected, sent or logged. The
 * switch that turns this off is in the settings screen, and turning it off
 * means the requests are never made rather than made and discarded.
 *
 * <h2>Why it cannot hurt the game</h2>
 *
 * On a daemon thread with a four second ceiling on each half of each request,
 * started once, never retried. Every failure — no network, a service that has
 * changed its shape, a rate limit, a proxy that answers with a login page — is
 * the same failure: the answer stays unknown, the screen says nothing, and the
 * game does not learn about it. There is no state here worth a second attempt.
 */
public final class UpdateCheck {

    private static final String CURSEFORGE_PAGE =
            "https://www.curseforge.com/minecraft/mc-mods/vulkanmod-next";
    private static final String GITHUB_PAGE =
            "https://github.com/VLOD-ZDOV/VulkanMod-Next/releases";

    private static final String CURSEFORGE =
            "https://api.cfwidget.com/minecraft/mc-mods/vulkanmod-next";
    private static final String GITHUB =
            "https://api.github.com/repos/VLOD-ZDOV/VulkanMod-Next/releases/latest";
    /**
     * Every release, pre-releases included, and asked only by somebody already
     * running one.
     *
     * {@code /releases/latest} leaves pre-releases out, which is exactly what
     * it is for: nobody on a finished version should be pulled onto an alpha by
     * a check they did not ask for. But it means an alpha is a dead end in the
     * other direction too — the people running the build that exists to be
     * reported against are the only ones who never hear that a newer one is out
     * to report against instead. So a pre-release asks the full list as well,
     * and a finished version does not.
     */
    private static final String GITHUB_ALL =
            "https://api.github.com/repos/VLOD-ZDOV/VulkanMod-Next/releases";

    /**
     * The file this mod ships as, which is also how a version is recognised in
     * whatever shape a service chooses to answer in.
     *
     * Matching the jar name rather than parsing the JSON on purpose: both
     * services have changed the shape of their answer before and neither has
     * ever changed what the file is called, so this survives a redesign at the
     * other end that a field name would not.
     *
     * <p>The game's version sits between the two from 0.10.0-alpha.4 on, and is listed
     * here by name rather than matched as "something that looks like a version".
     * A pattern loose enough to skip any version-shaped token would read
     * {@code vulkanmodnext-1.12.2-1.0.0} as version 1.12.2 the day the mod's
     * own number reaches 1 — which is a wrong answer that looks right, and the
     * kind that gets noticed a year later. Adding a line here is the price of
     * shipping for a new version of the game, and it is a small one.
     *
     * <p>Both mod names are accepted. The mod shipped as {@code vulkanmod112-} up
     * to 0.10.0 and as {@code vulkanmodnext-} after it, and a page carrying
     * files under both should not look empty from either side. It does not
     * repair the break in the other direction — a copy of 0.10.0 already
     * installed somewhere is looking for the old name only and will not notice
     * the first release under the new one. Nothing here can reach that copy;
     * what this can do is make sure the next rename does not do it again.
     */
    static final Pattern FILE = Pattern.compile(
            "vulkanmod(?:next|112)-(?:1\\.12\\.2-|1\\.16\\.5-)?"
                    + "(\\d+(?:\\.\\d+)*(?:-[0-9A-Za-z]+(?:\\.\\d+)*)?)");
    /** GitHub names the release rather than the file, so it is asked its way. */
    private static final Pattern TAG = Pattern.compile(
            "\"tag_name\"\\s*:\\s*\"v?(\\d+(?:\\.\\d+)*(?:-[0-9A-Za-z]+(?:\\.\\d+)*)?)");

    private static final int TIMEOUT_MILLIS = 4000;
    /** Enough for either answer; a service that sends more is not sending this. */
    private static final int READ_LIMIT = 256 * 1024;

    private static volatile String newer;
    private static volatile String page = CURSEFORGE_PAGE;
    private static volatile boolean started;

    private UpdateCheck() {
    }

    /** The version worth telling the player about, or null. */
    public static String newerVersion() {
        return newer;
    }

    /**
     * Where to send somebody who wants it — the page that actually has it.
     *
     * Defaults to CurseForge and stays there unless the check found the newer
     * build only in the repository, so a session where nothing was found at all
     * still points somewhere sensible.
     */
    public static String downloadPage() {
        return page;
    }

    public static void start() {
        if (started || !VulkanConfig.isUpdateCheck()) {
            return;
        }
        started = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                look();
            }
        }, "VulkanModNext update check");
        thread.setDaemon(true);
        thread.start();
    }

    private static void look() {
        String published = ask(CURSEFORGE, FILE);
        String tagged = ask(leadsTo(Tags.VERSION) ? GITHUB_ALL : GITHUB, TAG);
        String found = published;
        if (found == null || (tagged != null && isNewer(tagged, found))) {
            found = tagged;
        }
        if (found == null || !isNewer(found, Tags.VERSION)) {
            return;
        }
        // The page keeps the click only when the page has the file. Equal
        // counts as having it: the usual case is both services carrying the
        // same build, and that is the case the preference exists for.
        boolean onThePage = published != null && !isNewer(found, published);
        page = onThePage ? CURSEFORGE_PAGE : GITHUB_PAGE;
        newer = found;
        net.vulkanmodnext.VulkanModNext.LOGGER.info(
                "Version {} is available and this is {}; sending anyone who wants it to {}",
                found, Tags.VERSION, onThePage ? "the published page" : "the repository releases");
    }

    /** @return the highest version the answer mentions, or null for any failure */
    private static String ask(String address, Pattern pattern) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "VulkanModNext/" + Tags.VERSION);
            if (connection.getResponseCode() != 200) {
                return null;
            }
            String body = read(connection);
            String best = null;
            Matcher matcher = pattern.matcher(body);
            // Every match, not the first: a list of files is not promised to be
            // in any order, and the newest is the one worth reporting whichever
            // line it came back on.
            while (matcher.find()) {
                String candidate = matcher.group(1);
                if (best == null || isNewer(candidate, best)) {
                    best = candidate;
                }
            }
            return best;
        } catch (Throwable ignored) {
            // Named nowhere. A player without a network is not looking at a
            // stack trace about it, and a service that is down is not this
            // mod's fault to report.
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String read(HttpURLConnection connection) throws Exception {
        InputStream in = connection.getInputStream();
        try {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = in.read(buffer)) > 0 && out.size() < READ_LIMIT) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), Charset.forName("UTF-8"));
        } finally {
            in.close();
        }
    }

    /**
     * Whether one dotted version is above another, compared as numbers.
     *
     * As numbers and not as text, which is the whole of the difficulty: 0.10.0
     * is above 0.9.0 and sorts below it in every alphabet there is. This mod
     * passed that boundary this month, so a text comparison would have started
     * telling everybody they were up to date exactly when they stopped being.
     */
    static boolean isNewer(String candidate, String current) {
        // The three numbers first, and only the three numbers: a suffix has to
        // be cut off before the split, or the dot inside "alpha.2" becomes a
        // fourth number and 0.10.0 stops being above 0.10.0-alpha.2. That is
        // the exact shape of a trap this comparison has already fallen into
        // once, from the other side.
        int order = compareNumbers(numbersOf(candidate), numbersOf(current));
        if (order != 0) {
            return order > 0;
        }
        String leftSuffix = suffixOf(candidate);
        String rightSuffix = suffixOf(current);
        if (leftSuffix.isEmpty() || rightSuffix.isEmpty()) {
            // The numbers agree, so what separates them is whether one is a
            // build published on the way to that version rather than as it:
            // 0.10.0 is above 0.10.0-alpha, and nothing is above 0.10.0 itself.
            // Without this an alpha is a dead end — it exists to be reported
            // against, and the release those reports go into never reaches
            // anybody running it.
            return !leftSuffix.isEmpty() ? false : !rightSuffix.isEmpty();
        }
        // Both are on the way to the same version, so they are ordered against
        // each other the way the suffixes read: alpha before alpha.2 before
        // beta. Numbers as numbers, words as words, and a longer suffix above
        // the prefix it extends.
        return compareSuffixes(leftSuffix, rightSuffix) > 0;
    }

    /** The leading digits-and-dots, which is the version the suffix leads to. */
    private static String numbersOf(String version) {
        int cut = 0;
        while (cut < version.length()
                && (version.charAt(cut) == '.' || Character.isDigit(version.charAt(cut)))) {
            cut++;
        }
        return version.substring(0, cut);
    }

    /** Everything after those numbers, with any separator dropped. */
    private static String suffixOf(String version) {
        String numbers = numbersOf(version);
        String rest = version.substring(numbers.length());
        return rest.startsWith("-") || rest.startsWith("+") ? rest.substring(1) : rest;
    }

    private static int compareNumbers(String candidate, String current) {
        String[] left = candidate.split("\\.");
        String[] right = current.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = part(left, i);
            int b = part(right, i);
            if (a != b) {
                return a > b ? 1 : -1;
            }
        }
        return 0;
    }

    private static int compareSuffixes(String candidate, String current) {
        String[] left = candidate.split("\\.");
        String[] right = current.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            if (i >= left.length) {
                return -1;
            }
            if (i >= right.length) {
                return 1;
            }
            boolean leftNumber = isNumber(left[i]);
            boolean rightNumber = isNumber(right[i]);
            if (leftNumber && rightNumber) {
                int a = Integer.parseInt(left[i]);
                int b = Integer.parseInt(right[i]);
                if (a != b) {
                    return a > b ? 1 : -1;
                }
            } else if (leftNumber != rightNumber) {
                // A word outranks a number, the way every scheme that has
                // thought about it orders them: alpha.2 is on the way to beta.
                return leftNumber ? -1 : 1;
            } else {
                int order = left[i].compareTo(right[i]);
                if (order != 0) {
                    return order > 0 ? 1 : -1;
                }
            }
        }
        return 0;
    }

    private static boolean isNumber(String part) {
        if (part.isEmpty()) {
            return false;
        }
        for (int i = 0; i < part.length(); i++) {
            if (!Character.isDigit(part.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a version names something on the way to those numbers rather than
     * those numbers — anything carrying more than digits and dots.
     */
    private static boolean leadsTo(String version) {
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return true;
            }
        }
        return false;
    }

    private static int part(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            // A suffix nobody planned for — "0.11.0-rc1" — counts as the number
            // in front of it rather than throwing the whole comparison away.
            int cut = 0;
            while (cut < parts[index].length() && Character.isDigit(parts[index].charAt(cut))) {
                cut++;
            }
            return cut == 0 ? 0 : Integer.parseInt(parts[index].substring(0, cut));
        }
    }
}
