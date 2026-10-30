package net.sideways_sky.geyserrecipefix.events;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.sideways_sky.geyserrecipefix.Geyser_Recipe_Fix;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Original code contributed by GeyserRecipeFixPatcher.
 *
 * This class is NOT part of the official Geyser Recipe Fix plugin. At patch
 * time, GeyserRecipeFixPatcher rewrites the body of the original, unchanged
 * {@code PaperEvents.openForward(HumanEntity)} method (same name, same
 * signature, so every existing caller keeps working unmodified) to do
 * nothing but call {@link #openForward(HumanEntity)} here, and widens
 * {@code PaperEvents.forwardSkips} from private to package-private so this
 * class - placed in the same package on purpose - can register skips in it
 * exactly like the original inline lambda used to.
 *
 * The goal is functionally identical to what the original single-threaded
 * scheduler call did, just safe to run on Folia: everything touching the
 * player is run through the player's own entity scheduler, which is the
 * region thread on Folia and the main thread on Paper.
 *
 * (The legacy BukkitScheduler is deliberately not used anywhere: Folia
 * throws UnsupportedOperationException for it. This project is compiled
 * against the 26.x Paper API, where Entity#getScheduler() always exists,
 * so no reflection or fallback is needed.)
 */
public final class FoliaAnvilBridge {

    private FoliaAnvilBridge() {
    }

    public static void openForward(HumanEntity humanPlayer) {
        if (!(humanPlayer instanceof Player player)) {
            // Original behavior only ever made sense for real players; bail
            // out quietly for any other HumanEntity implementation.
            return;
        }
        runOnPlayerThread(player, 1L, () -> {
            player.closeInventory();
            PaperEvents.forwardSkips.add(player);
            openRealAnvil(player);
        });
    }

    /**
     * Runs {@code task} on the thread that owns {@code player} (the player's
     * region thread on Folia, the main thread on Paper): on the next tick if
     * {@code delayTicks} is 0 or less, otherwise after that many ticks. If
     * the player has been removed before it runs, the task is silently
     * dropped. Also used by AnvilRenameFix.
     */
    public static void runOnPlayerThread(Player player, long delayTicks, Runnable task) {
        Plugin plugin = (Plugin) Geyser_Recipe_Fix.instance;
        if (delayTicks <= 0L) {
            player.getScheduler().run(plugin, scheduled -> task.run(), null);
        } else {
            player.getScheduler().runDelayed(plugin, scheduled -> task.run(), null, delayTicks);
        }
    }

    /**
     * Opens the real, unmodified vanilla anvil menu for the player, built
     * directly via NMS rather than Paper's {@code Player#openAnvil(...)}
     * convenience method. Only vanilla classes plus CraftPlayer#getHandle
     * are involved, all of which are stable across Paper and Folia.
     */
    private static void openRealAnvil(Player player) {
        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        serverPlayer.openMenu(new SimpleMenuProvider(
                (containerId, inv, p) -> new AnvilMenu(containerId, inv, ContainerLevelAccess.NULL),
                Component.translatable("container.repair")
        ));
    }
}
