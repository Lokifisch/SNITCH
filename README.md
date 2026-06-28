# SNITCH

**S**erver-side **N**otifier **I**nspecting **T**ranslation **T**o **C**atch **H**ackers

A Paper/Spigot plugin that detects client-side mods (and resource packs) by abusing **[MC-265322](https://bugs.mojang.com/browse/MC-265322)** — the sign-translation vulnerability. The server fabricates an invisible sign, stuffs a mod's *translation key* into it with a fallback word, opens and force-closes the sign editor, and reads back the text the client reports. If the returned text differs from the fallback, that translation key resolved on the client — meaning the mod (or a resource pack defining the key) is installed.

> ⚠️ **For server operators detecting mods on their own server.** This relies on a known client vulnerability. A patch mod (e.g. by Nick Overflow) exists for players who want to block it. Use responsibly and within your jurisdiction's rules.

---

## How it works

For each player, SNITCH runs this packet sequence (mirroring the technique from the video that inspired it):

1. **Block Change** → place a throwaway sign a few blocks below the player (never visible).
2. **Block Entity Data** → set the sign's lines to *translatable components*, each carrying a unique random fallback word.
3. **Open Sign Editor** → the client opens the editor and converts the translation keys to plain text.
4. **Container Close** → forces the editor closed; the client reports the resolved text.
5. **Update Sign (inbound)** → SNITCH compares each line to its fallback. A mismatch = the key resolved = mod detected.
6. **Block Change** → revert the fake sign to the real block.

A single sign carries **four lines**, so keys are probed four at a time. Batches are sent **sequentially** — the next batch only fires after the previous one gets a response. This prevents key mismatches when multiple mods are installed.

The scan fires shortly after join, during the terrain-loading screen, so the editor GUI is never seen.

## Install

1. Install **[ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/)** (required).
2. Drop `SNITCH-x.x.x.jar` into `plugins/`.
3. Start the server — `plugins/SNITCH/config.yml` is generated with defaults.
4. Set your `server-name`, configure `mods:`, then `/snitch reload`.

**Requirements:** Paper/Spigot **1.21.x**, Java **21**, ProtocolLib.

## Configuration

### Server name

Shown in kick and ban messages via the `%server%` placeholder:

```yaml
server-name: "My Server"
```

### Flag actions

Each time a player is caught with the same mod, their flag count for that mod increases. You configure what happens at each count:

```yaml
action:
  flags:
    1: KICK   # first detection → kick
    2: BAN    # second detection → permanent ban
  # Any count not listed defaults to LOG (console only).

  kick-message: "&cYou have been kicked from &e%server%&c: &f%mod% &cis not allowed."
  ban-message:  "&cYou have been permanently banned from &e%server%&c for using &f%mod%&c."
```

Supported actions: `LOG`, `KICK`, `BAN`.

Optional console commands run on every detection regardless of the flag action. Placeholders: `%player%`, `%mod%`, `%key%`, `%evidence%`, `%flag%`, `%server%`:

```yaml
  commands:
    - "tellraw @a[permission=snitch.admin] {\"text\":\"[SNITCH] %player% flagged for %mod% (flag %flag%)\",\"color\":\"red\"}"
```

### Configuring mods

Each mod entry maps a friendly name to a list of translation keys. The player is flagged as soon as **any one** key resolves — multiple keys per mod give more reliable detection.

To find the real keys for a mod, open its `.jar` (it's just a zip) and read `assets/<modid>/lang/en_us.json`. A wrong key simply never matches — there are **no false positives**.

```yaml
mods:
  Freecam:
    - "key.freecam.toggle"
    - "key.freecam.config.open"
    - "key.freecam.controlPlayer.toggle"

  MeteorClient:
    - "key.meteor-client.open-gui"
    - "key.meteor-client.open-commands"
    - "key.category.meteor-client.meteor-client"
```

> **Note:** Mods with no lang file (pure library/mixin mods like baritone-meteor) cannot be detected via this technique.

## Commands

All under `/snitch` (permission `snitch.admin`):

| Command | Description |
|---------|-------------|
| `/snitch list` | Show all mods and their keys |
| `/snitch add <mod> <translation.key>` | Add a key to a mod (creates mod if new, persists + reloads) |
| `/snitch remove <mod> <translation.key>` | Remove one key from a mod (removes mod entry if no keys left) |
| `/snitch removemod <mod>` | Remove an entire mod and all its keys |
| `/snitch scan [player]` | Run a scan immediately |
| `/snitch reload` | Reload `config.yml` from disk |
| `/snitch info` | Show server name, mod/key count, and configured flag actions |

Players with `snitch.bypass` are never scanned. Tab completion works on all subcommands.

## Building

```bash
mvn package
# -> target/SNITCH-x.x.x.jar
```

## Caveats

- **Version sensitivity.** Built against the 1.21 packet layout. If a batch never reports, tune `close-delay-ticks` in the config.
- **In-game scans** (`/snitch scan` on an already-loaded player) may briefly flash a sign editor. Join-time scans do not.
- This is an exploit of a real client bug. Mojang may patch it, and client-side patch mods can block it.
- Mods with no lang file (pure libraries, mixins-only) cannot be detected via this technique.

## License

Provided as-is for educational and server-administration purposes.
