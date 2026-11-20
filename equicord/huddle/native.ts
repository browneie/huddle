/*
 * Runs in Discord's main (Node) process. Connects to the TCP server the Minecraft mod hosts on
 * 127.0.0.1 and writes one JSON object per line. Living here, not in the renderer, means Discord's
 * Content-Security-Policy never gets a say in the connection, or in the GitHub update check.
 */

import { IpcMainInvokeEvent } from "electron";
import { createConnection, Socket } from "net";

const PROTOCOL_VERSION = 1;
const RETRY_MS = 3000;
const RELEASES_API = "https://api.github.com/repos/browneie/huddle/releases/latest";

let socket: Socket | null = null;
let connected = false;
let running = false;
let port = 25599;
let pluginVersion = "0.0.0";
let retryTimer: ReturnType<typeof setTimeout> | null = null;
/** The last voice snapshot, so a game that starts (or restarts) mid-call gets the overlay at once. */
let lastVoice: string | null = null;
/** The mod version the game reported in its hello, or null when not connected (or a pre-1.1.0 mod). */
let modVersion: string | null = null;

/** Requests from the game ("soundboard_request", "play_sound"), waiting for the renderer to take them. */
const commands: any[] = [];
let commandWaiter: ((cmd: any) => void) | null = null;
const MAX_QUEUED = 32;
/** Only these come from the game; anything else on the socket is ignored. */
const GAME_COMMANDS = new Set(["soundboard_request", "play_sound"]);

/** Emoji pictures, fetched once each: url -> base64 PNG (or null when it couldn't be fetched). */
const images = new Map<string, Promise<string | null>>();
const IMAGE_HOSTS = new Set(["cdn.discordapp.com", "cdn.jsdelivr.net"]);

function write(line: string) {
    if (connected && socket) socket.write(line + "\n");
}

function scheduleRetry() {
    if (!running || retryTimer) return;
    retryTimer = setTimeout(() => {
        retryTimer = null;
        connect();
    }, RETRY_MS);
}

function onLine(line: string) {
    try {
        const msg = JSON.parse(line);
        if (msg?.type === "hello" && typeof msg.mod === "string") {
            modVersion = msg.mod;
            return;
        }
        if (!GAME_COMMANDS.has(msg?.type)) return;
        if (commandWaiter) {
            const wake = commandWaiter;
            commandWaiter = null;
            wake(msg);
        } else if (commands.length < MAX_QUEUED) {
            commands.push(msg);
        }
    } catch {
        // not ours
    }
}

function connect() {
    if (!running || socket) return;
    const s = createConnection({ host: "127.0.0.1", port });
    socket = s;
    s.setNoDelay(true);
    s.setKeepAlive(true, 10_000);

    let buffer = "";
    s.on("connect", () => {
        connected = true;
        write(JSON.stringify({ type: "hello", version: PROTOCOL_VERSION, plugin: pluginVersion }));
        if (lastVoice) write(lastVoice);
    });
    s.on("data", chunk => {
        buffer += chunk.toString("utf8");
        if (buffer.length > 1 << 16) buffer = ""; // the mod only sends short messages
        let nl: number;
        while ((nl = buffer.indexOf("\n")) >= 0) {
            onLine(buffer.slice(0, nl));
            buffer = buffer.slice(nl + 1);
        }
    });
    s.on("error", () => { /* game not running; "close" follows and retries */ });
    s.on("close", () => {
        if (socket !== s) return; // an old socket closing after a port change
        connected = false;
        modVersion = null;
        socket = null;
        scheduleRetry();
    });
}

function disconnect() {
    running = false;
    connected = false;
    modVersion = null;
    if (retryTimer) clearTimeout(retryTimer);
    retryTimer = null;
    socket?.destroy();
    socket = null;
}

export function start(_: IpcMainInvokeEvent, newPort: number, version: string) {
    pluginVersion = version;
    if (running && newPort === port) return;
    disconnect();
    running = true;
    port = newPort;
    connect();
}

export function stop(_: IpcMainInvokeEvent) {
    disconnect();
    lastVoice = null;
}

export function send(_: IpcMainInvokeEvent, json: string) {
    write(json);
}

export function sendVoice(_: IpcMainInvokeEvent, json: string) {
    lastVoice = json;
    write(json);
}

export function isConnected(_: IpcMainInvokeEvent) {
    return connected;
}

export function getModVersion(_: IpcMainInvokeEvent) {
    return modVersion;
}

/**
 * Resolves with the next request from the game, or null after a while so the renderer can check
 * whether it should keep listening. One caller at a time.
 */
export function nextCommand(_: IpcMainInvokeEvent): Promise<any> {
    if (commands.length) return Promise.resolve(commands.shift());
    if (commandWaiter) commandWaiter(null);
    return new Promise(resolve => {
        const timer = setTimeout(() => {
            if (commandWaiter === done) commandWaiter = null;
            resolve(null);
        }, 25_000);
        const done = (cmd: any) => {
            clearTimeout(timer);
            resolve(cmd);
        };
        commandWaiter = done;
    });
}

/** A small emoji picture as base64 PNG, from Discord's CDN or Twemoji only. */
export function fetchImage(_: IpcMainInvokeEvent, url: string): Promise<string | null> {
    let host: string;
    try {
        host = new URL(url).hostname;
    } catch {
        return Promise.resolve(null);
    }
    if (!IMAGE_HOSTS.has(host)) return Promise.resolve(null);
    let cached = images.get(url);
    if (!cached) {
        cached = (async () => {
            try {
                const res = await fetch(url, { headers: { "User-Agent": "Huddle" } });
                if (!res.ok) return null;
                const bytes = Buffer.from(await res.arrayBuffer());
                return bytes.length > 256 * 1024 ? null : bytes.toString("base64");
            } catch {
                return null;
            }
        })();
        images.set(url, cached);
    }
    return cached;
}

/** The latest published Huddle release, or null if GitHub can't be reached. */
export async function latestRelease(_: IpcMainInvokeEvent): Promise<{ version: string; url: string; } | null> {
    try {
        const res = await fetch(RELEASES_API, {
            headers: { "Accept": "application/vnd.github+json", "User-Agent": "Huddle-Updater" },
        });
        if (!res.ok) return null;
        const json = await res.json();
        if (json.draft || json.prerelease || typeof json.tag_name !== "string") return null;
        return { version: json.tag_name.replace(/^v/, ""), url: json.html_url };
    } catch {
        return null;
    }
}
