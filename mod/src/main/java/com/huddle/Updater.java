package com.huddle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Checks this repo's GitHub releases for a newer Huddle and, when you say so (or always, if you turned
 * that on), puts the new jar in your mods folder for the next launch.
 *
 * Only release assets from {@link #REPO} are used, and a jar is installed only when its SHA-256 matches
 * the digest GitHub lists for that asset. Nothing is installed while running from a dev environment.
 */
public final class Updater {

    static final String REPO = "browneie/huddle";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final int MAX_JAR_BYTES = 16 * 1024 * 1024;

    /** A newer release that's been found. */
    static final class Release {
        final String version, jarUrl, jarName, sha256, pageUrl;

        Release(String version, String jarUrl, String jarName, String sha256, String pageUrl) {
            this.version = version;
            this.jarUrl = jarUrl;
            this.jarName = jarName;
            this.sha256 = sha256;
            this.pageUrl = pageUrl;
        }
    }

    private static volatile Release pending;
    private static volatile boolean installing, installed;

    private Updater() {}

    /** This mod's version, as fabric.mod.json says. */
    public static String version() {
        return FabricLoader.getInstance().getModContainer("huddle")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");
    }

    /** Runs the check off the game thread; the result is shown as a notification by {@link Overlay}. */
    static void checkInBackground() {
        if (!LinkConfig.checkForUpdates) return;
        Thread thread = new Thread(() -> {
            try {
                Release r = fetchLatest();
                if (r == null || compare(r.version, version()) <= 0) return;
                LinkServer.LOGGER.info("Huddle {} is available (you have {})", r.version, version());
                if (LinkConfig.autoInstallUpdates && r.sha256 != null) {
                    install(r);
                } else {
                    pending = r;
                }
            } catch (Exception e) {
                LinkServer.LOGGER.warn("Couldn't check for Huddle updates: {}", e.toString());
            }
        }, "huddle-updater");
        thread.setDaemon(true);
        thread.start();
    }

    /** A release waiting for you to press Update, handed out once. */
    static Release takePending() {
        Release r = pending;
        pending = null;
        return r;
    }

    private static Release fetchLatest() throws IOException {
        JsonObject json = new JsonParser().parse(new String(get(API, 1024 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
        if (json.has("draft") && json.get("draft").getAsBoolean()) return null;
        if (json.has("prerelease") && json.get("prerelease").getAsBoolean()) return null;
        String tag = json.get("tag_name").getAsString();
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        String page = json.get("html_url").getAsString();

        JsonArray assets = json.getAsJsonArray("assets");
        for (JsonElement e : assets) {
            JsonObject a = e.getAsJsonObject();
            String name = a.get("name").getAsString();
            if (!name.equals("huddle-" + version + ".jar")) continue;
            String digest = a.has("digest") && !a.get("digest").isJsonNull() ? a.get("digest").getAsString() : null;
            String sha = digest != null && digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(Locale.ROOT) : null;
            return new Release(version, a.get("browser_download_url").getAsString(), name, sha, page);
        }
        // a release without a jar: still worth telling you about
        return new Release(version, null, null, null, page);
    }

    /** Whether pressing Update can install this one (it has a jar with a published checksum). */
    static boolean installable(Release r) {
        return r.jarUrl != null && r.sha256 != null && currentJar() != null;
    }

    /** Downloads, checks and swaps in the new jar. Off the game thread. */
    static void installInBackground(Release r) {
        Thread thread = new Thread(() -> install(r), "huddle-updater");
        thread.setDaemon(true);
        thread.start();
    }

    private static synchronized void install(Release r) {
        if (installing || installed) return;
        installing = true;
        try {
            Path current = currentJar();
            if (current == null || r.jarUrl == null || r.sha256 == null) {
                throw new IOException("this release can't be installed automatically");
            }
            byte[] jar = get(r.jarUrl, MAX_JAR_BYTES);
            String actual = sha256(jar);
            if (!actual.equals(r.sha256)) {
                throw new IOException("the download's checksum didn't match, so it wasn't installed");
            }

            Path mods = current.getParent();
            Path target = mods.resolve(r.jarName);
            Path part = mods.resolve(r.jarName + ".part");
            Files.write(part, jar);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            // Two Huddle jars would stop the game from starting, so the old one has to go. On Windows
            // the running jar is locked; it's removed on the next launch by removeLeftovers().
            if (!current.equals(target)) {
                try {
                    Files.delete(current);
                } catch (IOException locked) {
                    Files.write(mods.resolve(".huddle-remove"), current.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                    deleteAfterExit(current);
                }
            }
            installed = true;
            Overlay.notifyLater("Huddle " + r.version + " installed", "It'll load the next time you start the game.");
        } catch (Exception e) {
            LinkServer.LOGGER.error("Couldn't install Huddle {}", r.version, e);
            Overlay.notifyLater("Huddle update failed", e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            installing = false;
        }
    }

    /**
     * Windows keeps the running jar locked until the game exits, so a helper deletes it a few seconds
     * after. Fabric can refuse to start with two copies of a mod, so this has to happen before the next launch.
     */
    private static void deleteAfterExit(Path jar) {
        String path = jar.toAbsolutePath().toString();
        if (path.indexOf('"') >= 0) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                new ProcessBuilder("cmd", "/c", "ping -n 4 127.0.0.1 >nul & del /f /q \"" + path + "\"")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException ignored) {
                // not Windows, or no cmd: the marker file handles it on the next launch
            }
        }, "huddle-cleanup"));
    }

    /** Finishes a removal that Windows blocked last time. Runs at startup, before anything else. */
    static void removeLeftovers() {
        Path current = currentJar();
        if (current == null) return;
        Path marker = current.getParent().resolve(".huddle-remove");
        if (!Files.exists(marker)) return;
        try {
            String name = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
            Path old = current.getParent().resolve(name);
            if (!old.equals(current) && name.startsWith("huddle-") && name.endsWith(".jar")) Files.deleteIfExists(old);
            Files.delete(marker);
        } catch (IOException e) {
            LinkServer.LOGGER.warn("Couldn't remove the old Huddle jar: {}", e.toString());
        }
    }

    /** The jar this mod was loaded from, as it appears in the mods folder; null in a dev environment. */
    private static Path currentJar() {
        ModContainer mod = FabricLoader.getInstance().getModContainer("huddle").orElse(null);
        if (mod == null) return null;
        List<Path> paths = mod.getOrigin().getPaths();
        if (paths.size() != 1) return null;
        Path jar = paths.get(0);
        if (!Files.isRegularFile(jar) || !jar.getFileName().toString().endsWith(".jar")) return null;
        // OneClient links mods into its own store; replace the link in the mods folder, not the store
        Path mods = FabricLoader.getInstance().getGameDir().resolve("mods");
        try (java.util.stream.Stream<Path> files = Files.list(mods)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (Files.isSymbolicLink(p) && Files.isSameFile(p, jar)) return p;
            }
        } catch (IOException ignored) {
            // fall through
        }
        // loaded from somewhere other than the mods folder (a launcher's own store, say): we can't
        // swap that safely, so only point you at the release
        try {
            return Files.isSameFile(jar.getParent(), mods) ? jar : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] get(String url, int limit) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Huddle-Updater");
        c.setRequestProperty("Accept", url.equals(API) ? "application/vnd.github+json" : "application/octet-stream");
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("GitHub answered " + code);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limit) throw new IOException("download too large");
            }
            return out.toByteArray();
        }
    }

    private static String sha256(byte[] data) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compares dotted versions numerically ("1.10.0" > "1.9.2"); anything after '-' or '+' is ignored. */
    static int compare(String a, String b) {
        String[] x = a.split("[-+]")[0].split("\\.");
        String[] y = b.split("[-+]")[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0;
            int q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
