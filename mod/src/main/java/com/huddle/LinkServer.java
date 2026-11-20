package com.huddle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A loopback-only TCP server. The Equicord plugin connects and writes one JSON object per line:
 * {@code {"type":"notification",...}} or {@code {"type":"voice",...}}. Everything here runs off the
 * game thread and only hands finished values to {@link LinkState}.
 */
public final class LinkServer {

    static final Logger LOGGER = LogManager.getLogger("Huddle");
    /** A line longer than this isn't from the plugin; drop the connection rather than buffer it. */
    private static final int MAX_LINE = 1 << 20;

    private LinkServer() {}

    public static void start(int port) {
        Thread thread = new Thread(() -> listen(port), "huddle-server");
        thread.setDaemon(true);
        thread.start();
    }

    private static void listen(int port) {
        try (ServerSocket server = new ServerSocket(port, 4, InetAddress.getLoopbackAddress())) {
            LOGGER.info("Listening for Discord on 127.0.0.1:{}", port);
            while (true) {
                Socket client = server.accept();
                Thread thread = new Thread(() -> handle(client), "huddle-client");
                thread.setDaemon(true);
                thread.start();
            }
        } catch (IOException e) {
            LOGGER.error("Couldn't listen on port {} (is another game already using it?)", port, e);
        }
    }

    private static void handle(Socket client) {
        LinkState.clientConnected();
        try (Socket socket = client;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            // tell the plugin which mod version it's talking to, so it can warn about a mismatch
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("version", 1);
            hello.addProperty("mod", Updater.version());
            OutputStream out = socket.getOutputStream();
            out.write((hello.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            String line;
            while ((line = in.readLine()) != null) {
                if (line.length() > MAX_LINE) break;
                if (line.isEmpty()) continue;
                try {
                    dispatch(new JsonParser().parse(line).getAsJsonObject());
                } catch (RuntimeException e) {
                    LOGGER.warn("Ignoring a bad message from Discord: {}", e.toString());
                }
            }
        } catch (IOException ignored) {
            // Discord closed or restarted; it reconnects on its own
        } finally {
            LinkState.clientDisconnected();
        }
    }

    private static void dispatch(JsonObject msg) {
        switch (str(msg, "type")) {
            case "notification":
                LinkState.pushNotification(new LinkState.Notification(
                        str(msg, "title"), str(msg, "subtitle"), str(msg, "body")));
                break;
            case "voice":
                LinkState.setVoice(parseVoice(msg));
                break;
            case "hello":
                checkPluginVersion(msg);
                break;
            default:
                break; // anything newer than us
        }
    }

    private static volatile boolean warnedMismatch;

    /** Plugins before 1.1.0 don't say their version, so a missing one counts as 1.0.0. */
    private static void checkPluginVersion(JsonObject hello) {
        String plugin = hello.has("plugin") ? str(hello, "plugin") : "1.0.0";
        String mod = Updater.version();
        int cmp = Updater.compare(plugin, mod);
        if (cmp == 0 || warnedMismatch) return;
        warnedMismatch = true;
        if (cmp < 0) {
            Overlay.notifyLater("Update the Huddle plugin",
                    "Discord has plugin " + plugin + " but the mod is " + mod + ". Some things may not work until they match.");
        } else {
            Overlay.notifyLater("Update the Huddle mod",
                    "The mod is " + mod + " but Discord has plugin " + plugin + ". Some things may not work until they match.");
        }
    }

    private static LinkState.Voice parseVoice(JsonObject msg) {
        JsonElement channel = msg.get("channel");
        if (channel == null || channel.isJsonNull()) return null;
        List<LinkState.Member> members = new ArrayList<>();
        JsonElement users = msg.get("users");
        if (users != null && users.isJsonArray()) {
            JsonArray array = users.getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject u = element.getAsJsonObject();
                members.add(new LinkState.Member(
                        str(u, "name"),
                        bool(u, "self"),
                        bool(u, "muted"),
                        bool(u, "deafened"),
                        bool(u, "serverMuted"),
                        bool(u, "serverDeafened"),
                        bool(u, "speaking"),
                        bool(u, "streaming")));
            }
        }
        return new LinkState.Voice(clean(channel.getAsString()), str(msg, "guild"), members);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : clean(e.getAsString());
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    /** Strips Minecraft's § formatting codes and control characters so Discord text renders as typed. */
    private static String clean(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') { i++; continue; }
            if (c == '\n' || c == '\r' || c == '\t') { out.append(' '); continue; }
            if (Character.isISOControl(c)) continue;
            out.append(c);
        }
        return out.toString();
    }
}
