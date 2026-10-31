# Exact list of patches applied

Applied by `transform/MainClassTransformer.java` and
`transform/PaperEventsTransformer.java`, directly to the compiled classes
inside the jar downloaded from Modrinth. Nothing below reproduces any of
the original method bodies - each edit is either a one-line allocation
swap, an access-flag tweak, or a redirect to a small helper class that
ships with this project (see the "New classes added" list below).

## `Geyser_Recipe_Fix.class`

| # | Where | Change |
|---|-------|--------|
| 1 | static initializer | `new HashMap<>()` -> `new ConcurrentHashMap<>()` for the `openMenus` field. Safe because every other reference to it uses the declared type `java.util.Map`. |
| 2 | `onEnable()`, right after `logger = getLogger();` | Inserts one call: `FoliaRuntimeSupport.onEnable()`. That helper (shipped with this plugin, not upstream) detects Folia via a harmless `Class.forName` check and logs which scheduler mode is active. Nothing else in `onEnable()` is touched. |

## `PaperEvents.class`

| # | Where | Change |
|---|-------|--------|
| 1 | `forwardSkips` field | Access flag widened from `private` to package-private, so our injected `FoliaAnvilBridge` class (deliberately placed in the same package, `net.sideways_sky.geyserrecipefix.events`) can read/write the same field instance the rest of the class already checks. Type and semantics unchanged. |
| 2 | static initializer | `new HashSet<>()` -> `ConcurrentHashMap.newKeySet()` for `forwardSkips`. Both leave one `Set` reference on the stack, so nothing else in the initializer changes. |
| 3 | `openForward(HumanEntity)` | Entire body replaced with a single call: `FoliaAnvilBridge.openForward(player)`. Method name, descriptor, and access are untouched, so the existing (unmodified) caller elsewhere in the jar keeps resolving it exactly as before. |

## `plugin.yml`

Adds one line, `folia-supported: true`, if not already present.

## `AnvilSim.class`

| # | Where | Change |
|---|-------|--------|
| 1 | `setCost(int, Player)` | The original method is renamed to `setCost$original` (identical body - none of it is modified or reproduced elsewhere), and a new `setCost` is installed that calls the renamed original first, then `AnvilRenameFix.afterSetCost(this, player)` (shipped with this project). |

This fixes upstream issue [#19](https://github.com/AnsonEyre/Geyser-Recipe-Fix/issues/19) - "Enchanting items reset the item name."

**Root cause.** In vanilla's `AnvilMenu#createResult`, a *blank* rename text combined with an input item that already has a custom name means "the player cleared the name": the result has its custom name removed and costs 1 extra level. A normal Java client never triggers this, because its rename box is pre-filled with the item's current name and that name is sent to the server whenever the input slot changes. GeyserRecipeFix's simulated Bedrock GUI has no rename box, so the text stays blank and every custom-named item loses its name.

**Fix.** `AnvilRenameFix.afterSetCost` does what the vanilla client does: if the rename text is blank and the input has a custom name, it calls vanilla's own `AnvilMenu#setItemName(<that name>)`. Vanilla then recomputes the result with "no rename requested", so the original custom name (with any formatting) is kept and no extra level is charged. If the input item is later swapped, the name we injected is replaced/cleared again (only ever a name we injected ourselves; a name typed by a player is never touched). The menu change is performed on the player's own scheduler (region thread on Folia, main thread on Paper), because `setCost` is driven from packet interception and may run on another thread. If vanilla rejects the name (it refuses names over 50 characters), it falls back to copying the name onto the current result by hand (the cost may then be 1 level high).

One Minecraft-internal field (`AnvilMenu#itemName`, the current rename text) is read via reflection, as `AnvilMenu` has no getter for it. If it ever disappears, the fix logs one warning and does nothing, rather than risk breaking the anvil GUI.

## Every class in the jar: Paper-compat redirects

A final generic pass (`PaperCompatTransformer`) runs over every `.class` in the downloaded jar and redirects two Paper-specific things that the official build was compiled against but that newer Paper versions removed or locked down, into same-shaped methods in the injected `PaperCompat` class. Both are pure call-site swaps with identical stack effects; no method bodies are copied or reproduced.

| Old reference in upstream's bytecode | Replaced by | Why |
|---|---|---|
| `ItemStack.fromBukkitCopy(bukkit ItemStack)` | `PaperCompat.fromBukkitCopy(...)` -> `CraftItemStack.asNMSCopy(...)` | A Paper-added method on the vanilla `ItemStack`; gone on Paper 26.3, where right-clicking an anvil disconnected Bedrock players with `NoSuchMethodError`. |
| `GETFIELD ServerPlayer.containerSynchronizer` | `PaperCompat.containerSynchronizer(player)` (reads the field reflectively) | A private vanilla field that Paper exposed through an access transformer; reading it reflectively works whether or not that still exists. |

The pass first does a cheap byte search for the two names so classes that can't contain them are never parsed. The patcher logs how many redirects it applied.

## New classes added to the jar (not upstream's - see `src/main/java/net/sideways_sky/geyserrecipefix/`)

- `net/sideways_sky/geyserrecipefix/FoliaRuntimeSupport.class` - Folia
  detection + startup log line.
- `net/sideways_sky/geyserrecipefix/events/FoliaAnvilBridge.class` -
  Folia-safe replacement for the old "close inventory, wait a tick, open
  the real anvil" flow, run through the player's entity scheduler (region
  thread on Folia, main thread on Paper). The legacy BukkitScheduler is not
  used anywhere, since Folia throws for it.
- `net/sideways_sky/geyserrecipefix/inventories/AnvilRenameFix.class` -
  keeps a custom-named item's name through the simulated anvil (issue #19,
  see above).
- `net/sideways_sky/geyserrecipefix/PaperCompat.class` - the stable
  replacements the Paper-compat redirects above point at.

## Compile-only stand-ins (never packaged)

Three tiny classes (`Geyser_Recipe_Fix`, `PaperEvents`, `AnvilSim` under
`src/main/java/net/sideways_sky/geyserrecipefix/`) reproducing only a
handful of field *declarations* (name + type, not any logic) so the helper
classes above compile against the exact name/descriptor the real classes
use, and therefore link correctly against the genuine downloaded classes at
runtime. They're ordinary members of the `main` source set (see the build
layout note below for why), and are kept out of the shipped jar the same
way the real helper classes are.

## Build layout note

All of `FoliaRuntimeSupport`, `FoliaAnvilBridge`, `AnvilRenameFix`, and the
three compile-only stand-ins above are ordinary classes in the `main`
source set. That's the only way found to reliably get paperweight's
Mojang-mapped NMS classpath (needed for e.g. `AnvilMenu`,
`AbstractContainerMenu`) applied to them - a separate custom source set
doesn't reliably pick it up, even when the dev bundle dependency is added
directly to its own `compileOnly` configuration; this was tried twice (once
for an `injected` source set, once for a `stubs` source set) and failed the
same way both times. After compiling, `build.gradle`'s
`bundleInjectedClasses` task copies just the three real helper `.class`
files into `resources/main/injected/`, which is what `JarPatcher` actually
reads and splices into the target jar. `shadowJar` then excludes the whole
`net/sideways_sky/geyserrecipefix/**` path from the shipped plugin jar -
covering the helper classes' own path *and* the stand-ins - so none of it
ever exists as directly loadable classes on GeyserRecipeFixPatcher's own
runtime classpath; only the `injected/` byte copies do, and those are only
ever read, never loaded, by our own plugin.
