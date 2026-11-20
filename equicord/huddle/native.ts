/*
 * Runs in Discord's main (Node) process. Connects to the TCP server the Minecraft mod hosts on
 * 127.0.0.1 and writes one JSON object per line. Living here, not in the renderer, means Discord's
 * Content-Security-Policy never gets a say in the connection.
 */

import { IpcMainInvokeEvent } from "electron";
import { createConnection, Socket } from "net";

const PROTOCOL_VERSION = 1;
const RETRY_MS = 3000;

let socket: Socket | null = null;
let connected = false;
let running = false;
let port = 25599;
let retryTimer: ReturnType<typeof setTimeout> | null = null;
/** The last voice snapshot, so a game that starts (or restarts) mid-call gets the overlay at once. */
let lastVoice: string | null = null;

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

function connect() {
    if (!running || socket) return;
    const s = createConnection({ host: "127.0.0.1", port });
    socket = s;
    s.setNoDelay(true);
    s.setKeepAlive(true, 10_000);

    s.on("connect", () => {
        connected = true;
        write(JSON.stringify({ type: "hello", version: PROTOCOL_VERSION }));
        if (lastVoice) write(lastVoice);
    });
    // The mod never sends anything we need; drain it so the socket doesn't back up.
    s.on("data", () => { });
    s.on("error", () => { /* game not running; "close" follows and retries */ });
    s.on("close", () => {
        if (socket !== s) return; // an old socket closing after a port change
        connected = false;
        socket = null;
        scheduleRetry();
    });
}

function disconnect() {
    running = false;
    connected = false;
    if (retryTimer) clearTimeout(retryTimer);
    retryTimer = null;
    socket?.destroy();
    socket = null;
}

export function start(_: IpcMainInvokeEvent, newPort: number) {
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
