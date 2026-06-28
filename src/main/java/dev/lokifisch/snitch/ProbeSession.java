package dev.lokifisch.snitch;

import com.comphenix.protocol.wrappers.BlockPosition;

import java.util.UUID;

/**
 * One in-flight sign probe for a player. A sign has four lines, so each session
 * can test up to four translation keys at once.
 *
 * For each line index we remember:
 *   - the fallback word we injected (returned verbatim if the key did NOT resolve)
 *   - the friendly mod name and the key (null if the line is unused)
 */
public final class ProbeSession {

    public final UUID player;
    public final BlockPosition position;
    public final String[] fallbacks = new String[4];
    public final String[] modNames = new String[4];
    public final String[] keys = new String[4];
    public final long createdTick;

    public ProbeSession(UUID player, BlockPosition position, long createdTick) {
        this.player = player;
        this.position = position;
        this.createdTick = createdTick;
    }
}
