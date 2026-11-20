/*
 * Soundboard for the in-game wheel: lists the sounds you can play right now and plays the one the
 * game picks, the same way Discord's own soundboard button does.
 */

import { playAudio } from "@api/AudioPlayer";
import {
    ChannelStore,
    Constants,
    GuildStore,
    MediaEngineStore,
    PermissionsBits,
    PermissionStore,
    RestAPI,
    SelectedChannelStore,
    SoundboardStore,
    UserStore,
    VoiceStateStore,
} from "@webpack/common";

/** What the mod's wheel gets for each sound. */
interface WheelSound {
    id: string;
    /** null for Discord's default sounds */
    guild: string | null;
    name: string;
    /** where it's from: "Favorite", a server name, or "Default" */
    group: string;
    favorite: boolean;
    /** base64 PNG of the sound's emoji, if it has one */
    image: string | null;
}

const TWEMOJI = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@15.1.0/assets/72x72/";

/** Twemoji's file name for an emoji: its code points in hex, without FE0F unless it's a ZWJ sequence. */
function twemojiName(emoji: string): string {
    const points = [...emoji].map(c => c.codePointAt(0)!);
    const zwj = points.includes(0x200d);
    return points.filter(p => zwj || p !== 0xfe0f).map(p => p.toString(16)).join("-");
}

function emojiUrl(sound: any): string | null {
    if (sound.emojiId) return `https://cdn.discordapp.com/emojis/${sound.emojiId}.png?size=48`;
    if (sound.emojiName) return `${TWEMOJI}${twemojiName(sound.emojiName)}.png`;
    return null;
}

function isDefault(guildKey: string | null | undefined) {
    return !guildKey || guildKey === "0" || guildKey === "DEFAULTS";
}

/** Why you can't use the soundboard right now, or null if you can. */
function blockedReason(): string | null {
    const channelId = SelectedChannelStore.getVoiceChannelId();
    if (!channelId) return "Join a voice channel to use the soundboard.";
    const me = UserStore.getCurrentUser();
    const state = me && VoiceStateStore.getVoiceStateForUser(me.id);
    if (MediaEngineStore.isSelfDeaf?.() || state?.deaf) return "You can't use the soundboard while deafened.";
    if (state?.mute || state?.suppress) return "You can't use the soundboard while server muted.";
    const channel = ChannelStore.getChannel(channelId);
    if (channel?.guild_id && PermissionsBits.USE_SOUNDBOARD != null
        && !PermissionStore.can(PermissionsBits.USE_SOUNDBOARD, channel)) {
        return "You don't have permission to use the soundboard here.";
    }
    return null;
}

/** Sounds from other servers need Nitro and the Use External Sounds permission. */
function canUseExternal(channel: any): boolean {
    const me: any = UserStore.getCurrentUser();
    if (!me?.premiumType) return false;
    if (!channel?.guild_id || PermissionsBits.USE_EXTERNAL_SOUNDS == null) return true;
    return PermissionStore.can(PermissionsBits.USE_EXTERNAL_SOUNDS, channel);
}

/** Each listed sound's own volume (0-1), so it can be played back for you at the level others hear. */
const volumes = new Map<string, number>();

let defaultsFetch: Promise<void> | null = null;
let extraDefaults: any[] = [];

/** Discord only loads default sounds once its soundboard is opened, so ask for them ourselves if needed. */
function ensureDefaults(): Promise<void> {
    const loaded = [...(SoundboardStore.getSounds() ?? new Map()).keys()].some(isDefault);
    if (loaded || extraDefaults.length) return Promise.resolve();
    defaultsFetch ??= RestAPI.get({ url: "/soundboard-default-sounds" })
        .then((res: any) => {
            extraDefaults = (res?.body ?? []).map((s: any) => ({
                soundId: s.sound_id,
                name: s.name,
                emojiId: s.emoji_id,
                emojiName: s.emoji_name,
                available: s.available ?? true,
                volume: s.volume,
            }));
        })
        .catch(() => { })
        .finally(() => { defaultsFetch = null; });
    return defaultsFetch;
}

/**
 * The sounds you can play in your current call: favorites, this server's, the defaults, then your
 * other servers' when you have Nitro.
 */
export async function listSounds(fetchImage: (url: string) => Promise<string | null>) {
    const reason = blockedReason();
    if (reason) return { type: "soundboard", available: false, reason, sounds: [] };

    await ensureDefaults();
    const channel = ChannelStore.getChannel(SelectedChannelStore.getVoiceChannelId()!);
    const here: string | null = channel?.guild_id ?? null;
    const external = canUseExternal(channel);
    const favorites: Set<string> = SoundboardStore.getFavorites?.() ?? new Set();

    const picked: { sound: any; guild: string | null; group: string; rank: number; }[] = [];
    const seen = new Set<string>();
    const add = (sound: any, guildKey: string | null) => {
        if (!sound?.soundId || sound.available === false || seen.has(sound.soundId)) return;
        const guild = isDefault(guildKey) ? null : guildKey;
        if (guild && guild !== here && !external) return;
        seen.add(sound.soundId);
        volumes.set(sound.soundId, typeof sound.volume === "number" ? sound.volume : 1);
        const favorite = favorites.has(sound.soundId);
        const group = favorite ? "Favorite" : guild ? GuildStore.getGuild(guild)?.name ?? "Server" : "Default";
        const rank = favorite ? 0 : guild === here && guild ? 1 : !guild ? 2 : 3;
        picked.push({ sound, guild, group, rank });
    };

    for (const [guildKey, sounds] of SoundboardStore.getSounds() ?? []) {
        for (const sound of sounds ?? []) add(sound, guildKey);
    }
    for (const sound of extraDefaults) add(sound, null);

    picked.sort((a, b) => a.rank - b.rank || a.group.localeCompare(b.group) || a.sound.name.localeCompare(b.sound.name));

    const sounds: WheelSound[] = await Promise.all(picked.map(async ({ sound, guild, group }) => {
        const url = emojiUrl(sound);
        return {
            id: sound.soundId,
            guild,
            name: sound.name ?? "Sound",
            group,
            favorite: group === "Favorite",
            image: url ? await fetchImage(url) : null,
        };
    }));

    return {
        type: "soundboard",
        available: true,
        reason: sounds.length ? null : "No sounds to play here.",
        sounds,
    };
}

/** Plays a sound in your current call. Returns an error message for the game, or null on success. */
export async function playSound(id: string, guild: string | null): Promise<string | null> {
    const reason = blockedReason();
    if (reason) return reason;
    const channelId = SelectedChannelStore.getVoiceChannelId()!;
    try {
        await RestAPI.post({
            url: Constants.Endpoints.SEND_SOUNDBOARD_SOUND(channelId),
            body: guild ? { sound_id: id, source_guild_id: guild } : { sound_id: id },
        });
        // Discord's own button plays the sound for you locally; the API call only plays it for
        // everyone else, so play it here too.
        const cdn = (window as any).GLOBAL_ENV?.CDN_HOST ?? "cdn.discordapp.com";
        playAudio(`https://${cdn}/soundboard-sounds/${id}`, { volume: (volumes.get(id) ?? 1) * 100 });
        return null;
    } catch (e: any) {
        if (e?.status === 429) return "Slow down: the soundboard is on cooldown.";
        return e?.body?.message ?? "Discord didn't play that sound.";
    }
}
