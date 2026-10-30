package net.sideways_sky.geyserrecipefix.inventories;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.sideways_sky.geyserrecipefix.Geyser_Recipe_Fix;
import net.sideways_sky.geyserrecipefix.events.FoliaAnvilBridge;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Original code contributed by GeyserRecipeFixPatcher.
 *
 * Fixes upstream issue https://github.com/AnsonEyre/Geyser-Recipe-Fix/issues/19 -
 * "Enchanting items reset the item name".
 *
 * Root cause (vanilla AnvilMenu#createResult): when the anvil's "rename text"
 * is blank and the input item already has a custom name, vanilla treats that
 * as "the player cleared the name" - it strips the custom name from the
 * result and adds 1 level to the cost. A normal Java client never hits this,
 * because its rename box is pre-filled with the item's current name and it
 * sends that name to the server whenever the input slot changes. The
 * simulated Bedrock GUI has no rename box, so the text stays blank and every
 * custom-named item loses its name.
 *
 * Fix: do exactly what the vanilla client does - tell the menu the rename
 * text is the input item's current name (AnvilMenu#setItemName). Vanilla then
 * sees "no rename requested", keeps the original custom name (including any
 * formatting) and charges no extra level. We also undo our own injected name
 * if the input item is swapped for a different one, as a Java client would.
 *
 * This is NOT part of the official Geyser Recipe Fix plugin. At patch time,
 * GeyserRecipeFixPatcher renames the original {@code AnvilSim.setCost(int,
 * Player)} method to {@code setCost$original} (identical body, just a new
 * name) and installs a new {@code setCost} in its place that calls the
 * renamed original first, then {@link #afterSetCost} here - so all of
 * AnvilSim's own logic still runs completely unmodified.
 *
 * Threading: setCost is driven from packet interception and may run off the
 * player's thread, so this class only does cheap read-only checks there and
 * performs the actual menu change on the player's own scheduler (the region
 * thread on Folia, the main thread on Paper).
 */
public final class AnvilRenameFix {

    // AnvilMenu.INPUT_SLOT / AnvilMenu.RESULT_SLOT (stable vanilla constants).
    private static final int FIRST_SLOT = 0;
    private static final int RESULT_SLOT = 2;

    // NOTE: deliberately no nested/inner classes in here. Each injected class
    // is spliced into the target jar as exactly one .class file (see
    // JarPatcher#listInjectedClasses and the bundleInjectedClasses task in
    // build.gradle); a nested class would be a second file that nothing
    // copies, and this fix would silently die with NoClassDefFoundError.

    /** The rename text we injected per menu, so we only ever undo a name that we injected ourselves. */
    private static final Map<AnvilMenu, String> AUTO_NAMES = Collections.synchronizedMap(new WeakHashMap<>());
    /** Menus that already have a fix queued on the player's thread. */
    private static final Set<AnvilMenu> PENDING = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private static volatile Field itemNameField;
    private static volatile boolean warnedOnce = false;

    private AnvilRenameFix() {
    }

    public static void afterSetCost(AnvilSim sim, Player player) {
        try {
            if (!(sim.menu instanceof AnvilMenu menu)) {
                return;
            }
            if (desiredName(menu) == null || !PENDING.add(menu)) {
                return; // nothing to fix, or a fix is already queued
            }
            try {
                FoliaAnvilBridge.runOnPlayerThread(player, 0L, () -> {
                    try {
                        apply(menu, player);
                    } catch (Throwable t) {
                        warn(t);
                    } finally {
                        PENDING.remove(menu);
                    }
                });
            } catch (Throwable t) {
                PENDING.remove(menu);
                throw t;
            }
        } catch (Throwable t) {
            warn(t);
        }
    }

    /**
     * What the rename text should be right now, or null if it is already
     * correct. Purely read-only, so it is safe to call from any thread.
     */
    private static String desiredName(AnvilMenu menu) throws ReflectiveOperationException {
        String typed = readTypedRenameText(menu);
        ItemStack input = menu.getSlot(FIRST_SLOT).getItem();
        String inputName = !input.isEmpty() && input.has(DataComponents.CUSTOM_NAME)
                ? input.getHoverName().getString()
                : null;
        String auto = AUTO_NAMES.get(menu);
        boolean typedIsOurs = auto != null && typed.equals(auto);

        if (inputName != null) {
            if (inputName.isBlank()) {
                return null; // vanilla can't represent a blank name in the rename box either
            }
            if (typed.isBlank() || (typedIsOurs && !typed.equals(inputName))) {
                return inputName;
            }
            return null;
        }
        // The input has no custom name (or is empty): drop a leftover name we injected for a previous item.
        return typedIsOurs ? "" : null;
    }

    private static void apply(AnvilMenu menu, Player player) throws ReflectiveOperationException {
        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        if (serverPlayer.containerMenu != menu) {
            return; // the anvil was closed or replaced before this ran
        }
        String desired = desiredName(menu); // re-check on the owning thread
        if (desired == null) {
            return;
        }
        if (menu.setItemName(desired)) {
            // Vanilla recomputed the result (and re-sent it) using the new rename text.
            if (desired.isEmpty()) {
                AUTO_NAMES.remove(menu);
            } else {
                AUTO_NAMES.put(menu, desired);
            }
            return;
        }
        // Vanilla refused the text (names over 50 characters are rejected). Fall back to
        // copying the name onto the current result by hand; the cost may be 1 level high.
        if (!desired.isEmpty()) {
            restoreResultNameInPlace(menu);
        }
    }

    private static void restoreResultNameInPlace(AnvilMenu menu) {
        ItemStack input = menu.getSlot(FIRST_SLOT).getItem();
        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        if (result.isEmpty() || !input.has(DataComponents.CUSTOM_NAME)) {
            return;
        }
        Component expected = input.get(DataComponents.CUSTOM_NAME);
        if (expected == null || expected.equals(result.get(DataComponents.CUSTOM_NAME))) {
            return;
        }
        result.set(DataComponents.CUSTOM_NAME, expected);
        menu.broadcastChanges();
    }

    /**
     * Reads whatever rename text the menu currently holds. AnvilMenu has no
     * getter for it, hence reflection on the private {@code itemName} field
     * (present in 1.21.x through 26.3). Returns "" when nothing was typed.
     */
    private static String readTypedRenameText(AnvilMenu menu) throws ReflectiveOperationException {
        Field f = itemNameField;
        if (f == null) {
            f = AnvilMenu.class.getDeclaredField("itemName");
            f.setAccessible(true);
            itemNameField = f;
        }
        Object value = f.get(menu);
        return value == null ? "" : value.toString();
    }

    private static void warn(Throwable t) {
        // Fail safe: if this server version's internals don't match what we expect,
        // skip the fix rather than risk breaking the anvil GUI. Log once only.
        if (!warnedOnce) {
            warnedOnce = true;
            Logger logger = Geyser_Recipe_Fix.logger != null ? Geyser_Recipe_Fix.logger : Logger.getLogger("GeyserRecipeFixPatcher");
            logger.log(Level.WARNING, "[GeyserRecipeFixPatcher] Could not apply the anvil custom-name fix "
                    + "(upstream issue #19) - this server version's internals may have changed. "
                    + "The rest of the patch is unaffected.", t);
        }
    }
}
