package net.sideways_sky.geyserrecipefixpatcher;

import net.sideways_sky.geyserrecipefixpatcher.net.ModrinthClient;
import net.sideways_sky.geyserrecipefixpatcher.transform.JarPatcher;
import net.sideways_sky.geyserrecipefixpatcher.util.PatchState;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public final class PatchRunner {

    private final JavaPlugin plugin;
    private final ModrinthClient modrinth = new ModrinthClient();
    private final JarPatcher jarPatcher = new JarPatcher();
    private final PatchState state;

    public PatchRunner(JavaPlugin plugin) throws IOException {
        this.plugin = plugin;
        this.state = new PatchState(plugin.getDataFolder().toPath());
    }

    public enum Result {UP_TO_DATE, INSTALLED_FIRST_RUN, STAGED_RESTART_REQUIRED, ALLOWLIST_BLOCKED, FAILED}

    /**
     * Runs the full check -> download -> patch -> install flow. Safe to call
     * from onEnable(). Re-patches whenever EITHER the upstream GeyserRecipeFix
     * version or GeyserRecipeFixPatcher's own version (i.e. its patch logic)
     * has changed since the last successful patch - so upgrading this plugin
     * always re-applies the current patches, even if upstream hasn't moved.
     */
    public Result run(Consumer<String> log) {
        FileConfiguration cfg = plugin.getConfig();
        String projectSlug = cfg.getString("modrinth-project", "geyser-recipe-fix");
        List<String> allowlist = cfg.getStringList("allowed-versions");
        boolean autoUpdate = cfg.getBoolean("auto-update", true);

        try {
            String installed = state.installedVersion();
            String installedPatcherVersion = state.installedPatcherVersion();
            String currentPatcherVersion = plugin.getPluginMeta().getVersion();

            ModrinthClient.ReleaseInfo latest = modrinth.fetchLatest(projectSlug);
            log.accept("Latest upstream release on Modrinth: " + latest.versionNumber() + " (" + latest.fileName() + ")");

            boolean upstreamChanged = !latest.versionNumber().equals(installed);
            boolean patcherChanged = !currentPatcherVersion.equals(installedPatcherVersion);

            if (!upstreamChanged && !patcherChanged) {
                log.accept("Already up to date (GeyserRecipeFix v" + installed + ", patched by GeyserRecipeFixPatcher v"
                        + installedPatcherVersion + "). Nothing to do.");
                return Result.UP_TO_DATE;
            }

            ModrinthClient.ReleaseInfo target;
            if (upstreamChanged && installed != null && !autoUpdate) {
                // Don't move to the new upstream version - but if we're only
                // here because the patcher itself changed, re-patch whatever
                // version is already pinned instead of doing nothing.
                if (!patcherChanged) {
                    log.accept("A newer upstream version (" + latest.versionNumber() + ") is available but auto-update "
                            + "is off - not updating.");
                    return Result.UP_TO_DATE;
                }
                log.accept("GeyserRecipeFixPatcher was updated (v" + installedPatcherVersion + " -> v" + currentPatcherVersion
                        + ") - re-patching the currently pinned GeyserRecipeFix v" + installed
                        + " (auto-update is off, so not moving to v" + latest.versionNumber() + ").");
                target = modrinth.fetchByVersionNumber(projectSlug, installed)
                        .orElseThrow(() -> new IOException(
                                "v" + installed + " is no longer listed on Modrinth, so it can't be re-patched. "
                                + "Turn on auto-update, or add a currently-listed version to allowed-versions."));
            } else {
                target = latest;
                if (patcherChanged && !upstreamChanged) {
                    log.accept("GeyserRecipeFixPatcher was updated (v" + installedPatcherVersion + " -> v"
                            + currentPatcherVersion + ") - re-patching v" + installed + " with the current patch logic.");
                }
            }

            if (!allowlist.isEmpty() && !allowlist.contains(target.versionNumber())) {
                log.accept("Version " + target.versionNumber() + " is not in allowed-versions in config.yml - skipping. "
                        + "Add it there once you've verified it works.");
                return Result.ALLOWLIST_BLOCKED;
            }

            Path work = plugin.getDataFolder().toPath().resolve("work");
            Files.createDirectories(work);
            Path downloaded = work.resolve(target.fileName());
            log.accept("Downloading official build from Modrinth...");
            modrinth.download(target.downloadUrl(), downloaded);

            Path pluginsDir = plugin.getDataFolder().toPath().getParent();
            Path targetJar = pluginsDir.resolve("GeyserRecipeFix.jar");
            boolean firstInstall = !Files.exists(targetJar);

            log.accept("Applying local patches...");
            jarPatcher.patch(downloaded, targetJar, getClass().getClassLoader());

            state.setInstalledVersion(target.versionNumber());
            state.setInstalledPatcherVersion(currentPatcherVersion);
            Files.deleteIfExists(downloaded);

            if (firstInstall) {
                log.accept("Installed GeyserRecipeFix v" + target.versionNumber() + " (patcher v" + currentPatcherVersion
                        + ") to plugins/GeyserRecipeFix.jar. Restart the server to load it.");
                return Result.INSTALLED_FIRST_RUN;
            } else {
                log.accept("Updated GeyserRecipeFix to v" + target.versionNumber() + " (patcher v" + currentPatcherVersion
                        + "). Restart the server to apply it.");
                return Result.STAGED_RESTART_REQUIRED;
            }

        } catch (JarPatcher.UnsupportedUpstreamVersionException e) {
            log.accept("Patch NOT applied: " + e.getMessage());
            return Result.FAILED;
        } catch (Exception e) {
            log.accept("Patch run failed: " + e);
            return Result.FAILED;
        }
    }

    public PatchState state() {
        return state;
    }
}
