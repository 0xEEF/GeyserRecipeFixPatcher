package net.sideways_sky.geyserrecipefix;

/**
 * Original code contributed by GeyserRecipeFixPatcher.
 *
 * This class is NOT part of the official Geyser Recipe Fix plugin. It is
 * compiled separately by GeyserRecipeFixPatcher and spliced into a copy of
 * the official jar at patch time, alongside a single inserted call to
 * {@link #onEnable()} at the end of {@code Geyser_Recipe_Fix#onEnable()}.
 *
 * It exists only to log whether the server is running Folia, so admins can
 * confirm the patch took effect. It does not alter GeyserRecipeFix's own
 * behavior.
 */
public final class FoliaRuntimeSupport {

    private static boolean folia = false;
    private static boolean detected = false;

    private FoliaRuntimeSupport() {
    }

    /** Called once, from an inserted call site at the end of onEnable(). */
    public static void onEnable() {
        folia = detectFolia();
        detected = true;
        if (Geyser_Recipe_Fix.logger != null) {
            Geyser_Recipe_Fix.logger.info(folia
                    ? "[GeyserRecipeFixPatcher] Detected Folia - region/entity-aware scheduling is active."
                    : "[GeyserRecipeFixPatcher] Running on Paper - using the standard scheduler.");
        }
    }

    public static boolean isFolia() {
        if (!detected) {
            folia = detectFolia();
            detected = true;
        }
        return folia;
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
