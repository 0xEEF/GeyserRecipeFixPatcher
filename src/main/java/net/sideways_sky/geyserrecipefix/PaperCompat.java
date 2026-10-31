package net.sideways_sky.geyserrecipefix;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

import java.lang.reflect.Field;

/**
 * Original code contributed by GeyserRecipeFixPatcher.
 *
 * Stable replacements for a few Paper-specific additions that the official
 * Geyser Recipe Fix was compiled against but that Paper has since removed or
 * locked down. At patch time, GeyserRecipeFixPatcher rewrites each call to
 * the old member in the downloaded jar into a call to the matching method
 * here (same arguments, same result type, so nothing else changes).
 *
 * - {@code ItemStack.fromBukkitCopy(bukkit ItemStack)} (a method Paper added
 *   to the vanilla ItemStack class; gone on Paper 26.3, where it crashed the
 *   anvil GUI with NoSuchMethodError) -> {@link #fromBukkitCopy}, which uses
 *   the long-standing {@code CraftItemStack.asNMSCopy}.
 * - reading {@code ServerPlayer.containerSynchronizer} (a private vanilla
 *   field that Paper made public through an access transformer) ->
 *   {@link #containerSynchronizer}, which reads it reflectively so it works
 *   whether or not that access transformer still exists.
 *
 * Like the other injected classes this must stay a single .class file (no
 * nested/inner classes) - see the guard in build.gradle.
 */
public final class PaperCompat {

    private static volatile Field synchronizerField;

    private PaperCompat() {
    }

    public static ItemStack fromBukkitCopy(org.bukkit.inventory.ItemStack stack) {
        return CraftItemStack.asNMSCopy(stack);
    }

    public static ContainerSynchronizer containerSynchronizer(ServerPlayer player) {
        try {
            Field f = synchronizerField;
            if (f == null) {
                f = ServerPlayer.class.getDeclaredField("containerSynchronizer");
                f.setAccessible(true);
                synchronizerField = f;
            }
            return (ContainerSynchronizer) f.get(player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read ServerPlayer.containerSynchronizer on this server version", e);
        }
    }
}
