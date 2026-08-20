package net.vulkanmod112.client;

import net.vulkanmod112.Tags;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a newer build of this mod exists, asked once and never again.
 *
 * <h2>Two places, and the order is deliberate</h2>
 *
 * CurseForge is asked first because that is where a download is worth something
 * to whoever wrote this, and GitHub second because it is the one that cannot be
 * behind a service that decided today to answer robots differently. Either
 * answer is the same answer, so the first that arrives wins and the second is
 * never asked. A version that comes back from GitHub is still pointed at the
 * CurseForge page to download, since the number and the place are separate
 * questions.
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

    /** Where a player is sent, whichever source answered. */
    public static final String DOWNLOAD_PAGE =
            "https://www.curseforge.com/minecraft/mc-mods/vulkanmod-legacy";

    private static final String CURSEFORGE =
            "https://api.cfwidget.com/minecraft/mc-mods/vulkanmod-legacy";
    private static final String GITHUB =
            "https://api.github.com/repos/VLOD-ZDOV/VulkanMod-1.12.2/releases/latest";

    /**
     * The file this mod ships as, which is also how a version is recognised in
     * whatever shape a service chooses to answer in.
     *
     * Matching the jar name rather than parsing the JSON on purpose: both
     * services have changed the shape of their answer before and neither has
     * ever changed what the file is called, so this survives a redesign at the
     * other end that a field name would not.
     */
    private static final Pattern FILE = Pattern.compile("vulkanmod112-(\\d+(?:\\.\\d+)*)");
    /** GitHub names the release rather than the file, so it is asked its way. */
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?(\\d+(?:\\.\\d+)*)");

    private static final int TIMEOUT_MILLIS = 4000;
    /** Enough for either answer; a service that sends more is not sending this. */
    private static final int READ_LIMIT = 256 * 1024;

    private static volatile String newer;
    private static volatile boolean started;

    private UpdateCheck() {
    }

    /** The version worth telling the player about, or null. */
    public static String newerVersion() {
        return newer;
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
        }, "VulkanMod112 update check");
        thread.setDaemon(true);
        thread.start();
    }

    private static void look() {
        String found = ask(CURSEFORGE, FILE);
        if (found == null) {
            found = ask(GITHUB, TAG);
        }
        if (found != null && isNewer(found, Tags.VERSION)) {
            newer = found;
            net.vulkanmod112.VulkanMod112.LOGGER.info(
                    "Version {} is available; this is {}", found, Tags.VERSION);
        }
    }

    /** @return the highest version the answer mentions, or null for any failure */
    private static String ask(String address, Pattern pattern) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "VulkanMod112/" + Tags.VERSION);
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
        String[] left = candidate.split("\\.");
        String[] right = current.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = part(left, i);
            int b = part(right, i);
            if (a != b) {
                return a > b;
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
