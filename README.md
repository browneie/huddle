# Huddle

Your Discord notifications and voice call, inside Minecraft 1.8.9 (Ornithe / OneClient).

> [!WARNING]
> **Security and privacy: read this before installing.**
>
> - **Client mods break Discord's Terms of Service.** The plugin needs Equicord, an unofficial Discord
>   client mod. Using one can get your account actioned. Use it at your own risk.
> - **The connection has no password and no encryption.** The mod listens on `127.0.0.1:25599`, which only
>   your own computer can reach, never your network or the internet. But any program on your computer can
>   connect to it and show fake notifications or a fake voice overlay in your game. The mod never sends
>   anything back, so a program that connects can't read anything from your game.
> - **Your messages pass through that port.** While Minecraft isn't running, another program on your
>   computer could take port 25599 first and receive the DMs and mentions the plugin sends, including
>   their text. If you share your computer or don't trust everything on it, turn off **Include the message
>   text** in the plugin settings, or turn the plugin off when you're not playing.
> - **The soundboard lets the game play sounds in your call as you.** It's off until you turn on
>   **Soundboard** in the plugin settings. While it's on, any program on your computer that can reach the
>   port could play soundboard sounds in your voice call too, though only sounds the wheel was offered.
>   Emoji pictures for the wheel are fetched from Discord's CDN and from Twemoji on cdn.jsdelivr.net.
> - **The mod can download and install updates.** It only takes releases from
>   [browneie/huddle](https://github.com/browneie/huddle), and only installs a jar whose SHA-256 matches the
>   checksum GitHub lists for it. That catches broken or swapped downloads, but if this GitHub account were
>   ever taken over, a bad release would pass the check too. By default it asks before installing; leave
>   **Install Updates Automatically** off if you want to look at each release first, or turn off **Check
>   for Updates** to stop it contacting GitHub at all. The plugin only checks and tells you; it never
>   changes its own files.
> - **DMs show up on screen.** If you stream or record Minecraft, people can read your notifications.
>   Turn off notifications in the mod, or set Do Not Disturb in Discord, while you're live.
>
> Huddle is not affiliated with Discord, Equicord, Polyfrost or Mojang.

## What you get

- **Notifications** for DMs, group DMs, and mentions of you or one of your roles. They show as OneConfig
  notifications, the same pop-ups OneConfig and Poly+ use, with an optional ding. The plugin decides
  which Discord statuses send them: by default Online, Idle and Invisible, but not Do Not Disturb. Muted
  channels, muted servers and blocked users are skipped.
- **A voice overlay** that lists everyone in your voice channel by server nickname or display name. Mic
  and headphone icons show who's muted or deafened, in red when the server did it. A green light shows
  who's talking, and a **LIVE** tag shows who's streaming. It's a OneConfig HUD called **Huddle Voice**:
  move it, scale it and change its background in OneConfig's HUD editor.
- **A soundboard wheel.** Hold **J** to open a wheel of your
  soundboard sounds: favorites first, then this server's, Discord's defaults, and your other servers'
  if you have Nitro. Point at one and let go to play it in your call, or tap J and click. Scroll for
  more pages; Escape or the middle closes it. Turn on **Soundboard** in the plugin settings first.
  Change the key, how dark the screen gets behind the wheel, and how solid the wheel is in
  OneConfig → Huddle → Soundboard Wheel. The key is also in Minecraft's Controls, under Huddle.

Huddle has two parts:

- **`equicord/huddle/`**: an Equicord user plugin. It watches your messages and voice channel and sends
  them to the game.
- **`mod/`**: an Ornithe mod. It listens on `127.0.0.1:25599` and shows them in game.

The plugin connects to the game, not the other way round, and retries every 3 seconds, so you can start
them in either order.

## Requirements

**Mod** (Minecraft 1.8.9, Ornithe):

| Needs | Version | Where it comes from |
| --- | --- | --- |
| Fabric Loader | 0.17 or newer | OneClient, or any Ornithe 1.8.9 instance |
| OneConfig | 1.8.0 or newer | OneClient (as `OneConfig-1.8.9-ornithe`) |
| fabric-language-kotlin | 1.14.1 or newer | OneClient, or [Modrinth](https://modrinth.com/mod/fabric-language-kotlin) |
| Compose bundle (`compose-multiplatform`) | any | OneClient (as `compose-bundle`), from Polyfrost |
| Java | 25 | OneClient's 1.8.9 profile |

A OneClient 1.8.9 profile already has all of these. Huddle 1.0.0 and 1.1.0 only needed OneConfig 1.6.0
and Fabric Loader; from 1.2.0 on, the list above applies.

**Plugin:** Equicord built from source, on the Discord desktop app (or Equibop).

## Install the plugin

1. User plugins need Equicord built from source:
   ```bash
   git clone https://github.com/Equicord/Equicord && cd Equicord && pnpm install
   ```
2. Copy the `huddle` folder (from this repo or the release zip) into `Equicord/src/userplugins/`.
3. Build and inject:
   ```bash
   pnpm build && pnpm inject
   ```
4. Restart Discord and turn on **Huddle** in Settings → Plugins.

It needs the desktop app, because the connection is made from Discord's Node process. The web version
can't do it.

## Install the mod

Download the latest `huddle-<version>.jar` from [Releases](../../releases), then drag it onto your 1.8.9
OneClient profile, or put it in the `mods` folder of a 1.8.9 Ornithe instance that has everything in
[Requirements](#requirements).

Settings are in OneConfig (Right Shift) → **Huddle**: turn notifications and their sound on or off, set
how long notifications stay, and choose how updates work. Place, size and style the overlay in
OneConfig's HUD editor, where it's listed as **Huddle Voice**.

To build it yourself:

```bash
cd mod && ./gradlew build
```

The jar is written to `mod/build/libs/`.

## Updates

- **The mod** checks GitHub when the game starts. When there's a newer release you get a notification
  with **Update**, which downloads the new jar, checks it, and swaps it in for your next launch. Turn on
  **Install Updates Automatically** in OneConfig → Huddle → Updates to skip the button. If Huddle was loaded
  from somewhere other than your mods folder, you get a **View** button that opens the release instead.
- **The plugin** checks GitHub when Discord starts and every 6 hours, and shows a Discord notification
  linking to the new release. To update it, replace `Equicord/src/userplugins/huddle` with the new folder
  and run `pnpm build`.
- **Mismatched versions:** when the game connects, the mod and the plugin compare versions. If one is
  older, both Discord and the game tell you which one to update.

The mod only checks at startup, and an update loads on the launch after you install it.

The updater swaps the jar but doesn't install other mods. Before updating across a version that needs
something new (1.1.0 → 1.2.0 needs OneConfig 1.8.0, fabric-language-kotlin and the Compose bundle), check
[Requirements](#requirements); otherwise Fabric stops at launch with a "missing dependency" screen. Taking
the Huddle jar back out of your mods folder gets you past it.

Updating from 1.0.0 is manual, since 1.0.0 has no updater.

## Testing

With the game open, open the Equicord toolbox and choose **Send Huddle Test Notification**. Then join any
voice channel to see the overlay.

## Protocol

Newline-delimited JSON over TCP. From Discord to the game:

```json
{"type":"hello","version":1}
{"type":"notification","title":"Alex","subtitle":"#general · My Server","body":"hey @you"}
{"type":"voice","channel":"General","guild":"My Server","users":[
  {"id":"1","name":"Alex","self":false,"muted":true,"deafened":false,
   "serverMuted":false,"serverDeafened":false,"speaking":false,"streaming":false}]}
{"type":"voice","channel":null,"users":[]}
{"type":"soundboard","available":true,"reason":null,"sounds":[
  {"id":"123","guild":null,"name":"quack","group":"Default","favorite":false,"image":"<base64 PNG>"}]}
{"type":"sound_result","id":"123","ok":false,"error":"Slow down: the soundboard is on cooldown."}
```

From the game to Discord:

```json
{"type":"hello","version":1,"mod":"1.2.0"}
{"type":"soundboard_request"}
{"type":"play_sound","id":"123","guild":null}
```

## License

[MIT](LICENSE)
