package com.huddle;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Handoff between the socket threads and the render thread. */
public final class LinkState {

    public static final class Notification {
        public final String title, subtitle, body;

        public Notification(String title, String subtitle, String body) {
            this.title = title;
            this.subtitle = subtitle;
            this.body = body;
        }
    }

    public static final class Member {
        public final String name;
        public final boolean self, muted, deafened, serverMuted, serverDeafened, speaking, streaming;

        public Member(String name, boolean self, boolean muted, boolean deafened, boolean serverMuted,
                      boolean serverDeafened, boolean speaking, boolean streaming) {
            this.name = name;
            this.self = self;
            this.muted = muted;
            this.deafened = deafened;
            this.serverMuted = serverMuted;
            this.serverDeafened = serverDeafened;
            this.speaking = speaking;
            this.streaming = streaming;
        }
    }

    public static final class Voice {
        public final String channel, guild;
        public final List<Member> members;

        public Voice(String channel, String guild, List<Member> members) {
            this.channel = channel;
            this.guild = guild;
            this.members = members;
        }
    }

    private static final ConcurrentLinkedQueue<Notification> PENDING = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger CLIENTS = new AtomicInteger();
    private static volatile Voice voice;

    private LinkState() {}

    static void pushNotification(Notification n) {
        if (PENDING.size() < 32) PENDING.add(n);
    }

    public static Notification pollNotification() {
        return PENDING.poll();
    }

    static void setVoice(Voice v) {
        voice = v;
    }

    /** The call you're in, or null when you're not in one (or Discord isn't connected). */
    public static Voice voice() {
        return voice;
    }

    static void clientConnected() {
        CLIENTS.incrementAndGet();
    }

    static void clientDisconnected() {
        if (CLIENTS.decrementAndGet() <= 0) {
            CLIENTS.set(0);
            voice = null;
        }
    }

    public static boolean connected() {
        return CLIENTS.get() > 0;
    }
}
