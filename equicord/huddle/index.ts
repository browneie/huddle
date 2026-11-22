/*
 * Huddle: forwards Discord notifications and your current voice call to the
 * Huddle Ornithe mod, which shows them in game.
 */

import { showNotification } from "@api/Notifications";
import { definePluginSettings } from "@api/Settings";
import definePlugin, { OptionType, PluginNative } from "@utils/types";
import { findStoreLazy } from "@webpack";
import {
    ChannelStore,
    GuildMemberStore,
    GuildRoleStore,
    GuildStore,
    PresenceStore,
    RelationshipStore,
    SelectedChannelStore,
    UserStore,
} from "@webpack/common";

import { listSounds, playSound } from "./soundboard";

const Native = VencordNative.pluginHelpers.Huddle as PluginNative<typeof import("./native")>;

/** Keep in step with the mod's version; they warn each other when they don't match. */
const PLUGIN_VERSION = "1.2.1";
const UPDATE_CHECK_MS = 6 * 60 * 60 * 1000;

const VoiceStateStore = findStoreLazy("VoiceStateStore");
const SpeakingStore = findStoreLazy("SpeakingStore");
const MediaEngineStore = findStoreLazy("MediaEngineStore");
const UserGuildSettingsStore = findStoreLazy("UserGuildSettingsStore");

const settings = definePluginSettings({
    port: {
        type: OptionType.NUMBER,
        description: "Port the Minecraft mod listens on (must match the mod's config)",
        default: 25599,
        onChange: (port: number) => Native.start(port, PLUGIN_VERSION),
    },
    notifyOnline: {
        type: OptionType.BOOLEAN,
        description: "Send notifications to Minecraft while Online",
        default: true,
    },
    notifyIdle: {
        type: OptionType.BOOLEAN,
        description: "Send notifications to Minecraft while Idle",
        default: true,
    },
    notifyInvisible: {
        type: OptionType.BOOLEAN,
        description: "Send notifications to Minecraft while Invisible",
        default: true,
    },
    notifyDnd: {
        type: OptionType.BOOLEAN,
        description: "Send notifications to Minecraft while on Do Not Disturb",
        default: false,
    },
    directMessages: {
        type: OptionType.BOOLEAN,
        description: "Notify for direct and group messages",
        default: true,
    },
    mentions: {
        type: OptionType.BOOLEAN,
        description: "Notify when you (or a role you have) are mentioned in a server",
        default: true,
    },
    everyone: {
        type: OptionType.BOOLEAN,
        description: "Also notify for @everyone and @here",
        default: false,
    },
    showContent: {
        type: OptionType.BOOLEAN,
        description: "Include the message text (turn off to show only who messaged you)",
        default: true,
    },
    voiceOverlay: {
        type: OptionType.BOOLEAN,
        description: "Send your voice channel to the in-game overlay",
        default: true,
        onChange: () => queueVoice(),
    },
    soundboard: {
        type: OptionType.BOOLEAN,
        description: "Let the in-game wheel play soundboard sounds in your voice call. Off by default: while it's on, any program on this computer that can reach the game's port could play sounds as you.",
        default: false,
    },
    checkForUpdates: {
        type: OptionType.BOOLEAN,
        description: "Tell me when a new Huddle release is on GitHub (the plugin never updates itself)",
        default: true,
    },
});

// ---------- soundboard ----------

let listening = false;
/** Sounds last offered to the wheel; play requests for anything else are refused. */
let offered = new Map<string, string | null>();

async function handleCommand(cmd: any) {
    if (!settings.store.soundboard) {
        send({ type: "soundboard", available: false, reason: "Turn on Soundboard in the Huddle plugin settings in Discord.", sounds: [] });
        return;
    }
    if (cmd.type === "soundboard_request") {
        const list: any = await listSounds(url => Native.fetchImage(url));
        offered = new Map(list.sounds.map((s: any) => [s.id, s.guild]));
        send(list);
    } else if (cmd.type === "play_sound" && typeof cmd.id === "string") {
        if (!offered.has(cmd.id)) {
            send({ type: "sound_result", id: cmd.id, ok: false, error: "That sound isn't available." });
            return;
        }
        const error = await playSound(cmd.id, offered.get(cmd.id) ?? null);
        send({ type: "sound_result", id: cmd.id, ok: !error, error });
    }
}

/** Takes the game's requests one at a time for as long as the plugin runs. */
async function commandLoop() {
    while (listening) {
        const cmd = await Native.nextCommand();
        if (!listening) break;
        if (!cmd) continue;
        try {
            await handleCommand(cmd);
        } catch (e) {
            console.error("[Huddle] soundboard request failed", e);
        }
    }
}

// ---------- versions ----------

/** Compares dotted versions numerically; anything after '-' or '+' is ignored. */
function compareVersions(a: string, b: string): number {
    const x = a.split(/[-+]/)[0].split(".").map(n => parseInt(n, 10) || 0);
    const y = b.split(/[-+]/)[0].split(".").map(n => parseInt(n, 10) || 0);
    for (let i = 0; i < Math.max(x.length, y.length); i++) {
        const d = (x[i] ?? 0) - (y[i] ?? 0);
        if (d) return Math.sign(d);
    }
    return 0;
}

let updateTimer: ReturnType<typeof setInterval> | null = null;
let modCheckTimer: ReturnType<typeof setInterval> | null = null;
let notifiedRelease: string | null = null;
let checkedModVersion: string | null = null;

async function checkForUpdate() {
    if (!settings.store.checkForUpdates) return;
    const release = await Native.latestRelease();
    if (!release || compareVersions(release.version, PLUGIN_VERSION) <= 0 || notifiedRelease === release.version) return;
    notifiedRelease = release.version;
    showNotification({
        title: `Huddle ${release.version} is available`,
        body: `You have plugin ${PLUGIN_VERSION}. Click to open the release, then replace the plugin folder and rebuild Equicord.`,
        onClick: () => VencordNative.native.openExternal(release.url),
        permanent: true,
    });
}

/** Warns once per game connection when the mod and the plugin are different versions. */
async function checkModVersion() {
    const mod = await Native.getModVersion();
    if (!mod) {
        checkedModVersion = null;
        return;
    }
    if (mod === checkedModVersion) return;
    checkedModVersion = mod;
    const cmp = compareVersions(mod, PLUGIN_VERSION);
    if (cmp === 0) return;
    showNotification({
        title: cmp > 0 ? "Update the Huddle plugin" : "Update the Huddle mod",
        body: cmp > 0
            ? `The game has mod ${mod} but this plugin is ${PLUGIN_VERSION}. Some things may not work until they match.`
            : `This plugin is ${PLUGIN_VERSION} but the game has mod ${mod}. Some things may not work until they match.`,
    });
}

// ---------- helpers ----------

function myStatus(): string {
    const me = UserStore.getCurrentUser();
    return (me && PresenceStore.getStatus(me.id)) || "online";
}

function statusAllowed(): boolean {
    switch (myStatus()) {
        case "idle": return settings.store.notifyIdle;
        case "dnd": return settings.store.notifyDnd;
        case "invisible":
        case "offline": return settings.store.notifyInvisible;
        default: return settings.store.notifyOnline;
    }
}

function displayName(userId: string, guildId?: string | null, fallback?: any): string {
    const nick = guildId ? GuildMemberStore.getNick(guildId, userId) : null;
    if (nick) return nick;
    const user: any = UserStore.getUser(userId) ?? fallback;
    return user?.globalName ?? user?.global_name ?? user?.username ?? "Unknown";
}

/** Turns <@id>, <#id>, <@&id> and custom emoji into readable text. */
function cleanContent(content: string, guildId?: string | null): string {
    return content
        .replace(/<@!?(\d+)>/g, (_, id) => "@" + displayName(id, guildId))
        .replace(/<#(\d+)>/g, (_, id) => "#" + (ChannelStore.getChannel(id)?.name ?? "channel"))
        .replace(/<@&(\d+)>/g, (_, id) => "@" + (guildId && GuildRoleStore.getRole(guildId, id)?.name || "role"))
        .replace(/<a?(:\w+:)\d+>/g, "$1")
        .replace(/<t:(\d+)(?::\w)?>/g, (_, t) => new Date(Number(t) * 1000).toLocaleString())
        .trim();
}

function messageBody(message: any, guildId?: string | null): string {
    const text = message.content ? cleanContent(message.content, guildId) : "";
    if (text) return text;
    if (message.attachments?.length) return `[${message.attachments.length} attachment${message.attachments.length > 1 ? "s" : ""}]`;
    if (message.sticker_items?.length) return `[sticker: ${message.sticker_items[0].name}]`;
    if (message.embeds?.length) return "[embed]";
    return "";
}

function isChannelMuted(channel: any): boolean {
    try {
        const guildId = channel.guild_id ?? null;
        if (guildId && UserGuildSettingsStore.isMuted(guildId)) return true;
        return !!UserGuildSettingsStore.isChannelMuted(guildId, channel.id);
    } catch {
        return false;
    }
}

function mentionsMe(message: any, guildId: string, meId: string): boolean {
    if (message.mentions?.some((u: any) => (u.id ?? u) === meId)) return true;
    if (message.mention_everyone) return settings.store.everyone;
    if (message.mention_roles?.length) {
        const roles: string[] = GuildMemberStore.getMember(guildId, meId)?.roles ?? [];
        return message.mention_roles.some((r: string) => roles.includes(r));
    }
    return false;
}

function send(payload: object) {
    Native.send(JSON.stringify(payload));
}

function sendNotification(title: string, subtitle: string, body: string) {
    send({ type: "notification", title, subtitle, body });
}

// ---------- notifications ----------

function onMessage(message: any, optimistic: boolean) {
    if (optimistic || !message?.author) return;
    const me = UserStore.getCurrentUser();
    if (!me || message.author.id === me.id) return;
    if (!statusAllowed()) return;
    if (RelationshipStore.isBlocked(message.author.id)) return;

    const channel = ChannelStore.getChannel(message.channel_id);
    if (!channel || isChannelMuted(channel)) return;

    const guildId: string | null = channel.guild_id ?? message.guild_id ?? null;
    const isPrivate = !guildId;

    if (isPrivate ? !settings.store.directMessages : !(settings.store.mentions && mentionsMe(message, guildId!, me.id))) return;

    const author = message.member?.nick ?? displayName(message.author.id, guildId, message.author);
    let subtitle: string;
    if (isPrivate) {
        subtitle = channel.type === 3
            ? (channel.name || "Group DM")
            : "Direct Message";
    } else {
        subtitle = `#${channel.name} · ${GuildStore.getGuild(guildId!)?.name ?? "Server"}`;
    }
    const body = settings.store.showContent ? messageBody(message, guildId) : "sent a message";
    sendNotification(author, subtitle, body);
}

// ---------- voice overlay ----------

let voiceTimer: ReturnType<typeof setTimeout> | null = null;
let lastVoiceJson = "";

function buildVoice(): object {
    const channelId = SelectedChannelStore.getVoiceChannelId();
    if (!settings.store.voiceOverlay || !channelId) return { type: "voice", channel: null, users: [] };

    const channel = ChannelStore.getChannel(channelId);
    const guildId: string | null = channel?.guild_id ?? null;
    const meId: string | undefined = UserStore.getCurrentUser()?.id;
    const states: Record<string, any> = VoiceStateStore.getVoiceStatesForChannel(channelId) ?? {};

    const users = Object.values(states).map((s: any) => {
        const self = s.userId === meId;
        const selfMute = self ? !!MediaEngineStore.isSelfMute?.() : !!s.selfMute;
        const selfDeaf = self ? !!MediaEngineStore.isSelfDeaf?.() : !!s.selfDeaf;
        return {
            id: s.userId,
            name: displayName(s.userId, guildId),
            self,
            muted: selfMute,
            deafened: selfDeaf,
            serverMuted: !!s.mute || !!s.suppress,
            serverDeafened: !!s.deaf,
            speaking: !!SpeakingStore.isSpeaking(s.userId),
            streaming: !!s.selfStream,
        };
    }).sort((a, b) => a.name.localeCompare(b.name));

    return {
        type: "voice",
        channel: channel?.name ?? "Voice",
        guild: guildId ? GuildStore.getGuild(guildId)?.name ?? null : null,
        users,
    };
}

function flushVoice() {
    voiceTimer = null;
    const json = JSON.stringify(buildVoice());
    if (json === lastVoiceJson) return;
    lastVoiceJson = json;
    Native.sendVoice(json);
}

/** Store changes (speaking especially) fire in bursts; coalesce them into one update. */
function queueVoice() {
    if (!voiceTimer) voiceTimer = setTimeout(flushVoice, 50);
}

const voiceStores = () => [VoiceStateStore, SpeakingStore, SelectedChannelStore, MediaEngineStore];

export default definePlugin({
    name: "Huddle",
    description: "Shows your notifications and voice call inside Minecraft, through the Huddle mod.",
    authors: [{ name: "brownie", id: 0n }],
    settings,

    flux: {
        MESSAGE_CREATE({ message, optimistic }: { message: any; optimistic: boolean; }) {
            try {
                onMessage(message, optimistic);
            } catch (e) {
                console.error("[Huddle] notification failed", e);
            }
        },
    },

    toolboxActions: {
        "Send Huddle Test Notification"() {
            sendNotification("Huddle", "Test", "If you can read this in game, it works!");
        },
    },

    start() {
        Native.start(settings.store.port, PLUGIN_VERSION);
        checkForUpdate();
        updateTimer = setInterval(checkForUpdate, UPDATE_CHECK_MS);
        modCheckTimer = setInterval(checkModVersion, 5000);
        listening = true;
        commandLoop();
        for (const store of voiceStores()) store.addChangeListener(queueVoice);
        lastVoiceJson = "";
        queueVoice();
    },

    stop() {
        for (const store of voiceStores()) store.removeChangeListener(queueVoice);
        if (voiceTimer) clearTimeout(voiceTimer);
        voiceTimer = null;
        if (updateTimer) clearInterval(updateTimer);
        if (modCheckTimer) clearInterval(modCheckTimer);
        updateTimer = modCheckTimer = null;
        checkedModVersion = null;
        listening = false;
        offered.clear();
        // the mod clears the overlay when the connection drops
        Native.stop();
    },
});
