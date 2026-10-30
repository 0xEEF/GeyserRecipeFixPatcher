package net.sideways_sky.geyserrecipefixpatcher;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class GeyserRecipeFixPatcherPlugin extends JavaPlugin implements Listener {

    private PatchRunner runner;

    /**
     * Set once a patch run installs or updates plugins/GeyserRecipeFix.jar
     * during this server session. Stays true until the next restart (there's
     * no need to persist it - once the server restarts, either the new jar
     * is loaded and a fresh run reports UP_TO_DATE, or it's still pending
     * and this flag gets set again).
     */
    private volatile boolean restartRequired = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            runner = new PatchRunner(this);
        } catch (Exception e) {
            getLogger().severe("Failed to initialize patcher state: " + e);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);

        if (getConfig().getBoolean("check-on-startup", true)) {
            // Network I/O - keep it off the main thread so it never delays
            // server startup or trips the watchdog. The legacy
            // BukkitScheduler.runTaskAsynchronously(...) throws
            // UnsupportedOperationException on Folia by design, so we use
            // Paper's unified AsyncScheduler instead - it works the same
            // way on plain Paper too.
            getServer().getAsyncScheduler().runNow(this, task -> runAndReport(msg -> getLogger().info(msg)));
        } else {
            getLogger().info("check-on-startup is disabled in config.yml. Use /grfpatcher update to run manually.");
        }
    }

    /** Runs a patch check, logging a loud warning (and arming the join reminder) if a restart is now needed. */
    private void runAndReport(java.util.function.Consumer<String> log) {
        PatchRunner.Result result = runner.run(log);
        if (result == PatchRunner.Result.INSTALLED_FIRST_RUN || result == PatchRunner.Result.STAGED_RESTART_REQUIRED) {
            restartRequired = true;
            getLogger().warning("=====================================================================");
            getLogger().warning("GeyserRecipeFix was just installed/updated by GeyserRecipeFixPatcher.");
            getLogger().warning("RESTART THE SERVER to load it - it will not take effect until you do.");
            getLogger().warning("=====================================================================");
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        if (!restartRequired) {
            return;
        }
        Player player = e.getPlayer();
        if (player.hasPermission("geyserrecipefixpatcher.admin")) {
            player.sendMessage(Component.text("[GeyserRecipeFixPatcher] ", NamedTextColor.GOLD)
                    .append(Component.text("GeyserRecipeFix was updated - restart the server to load it.", NamedTextColor.YELLOW)));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("geyserrecipefixpatcher.admin")) {
            sender.sendMessage("You don't have permission to use this.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "status" -> {
                String installed = runner.state().installedVersion();
                String installedPatcherVersion = runner.state().installedPatcherVersion();
                sender.sendMessage("Installed GeyserRecipeFix version: " + (installed == null ? "none" : installed)
                        + " (patched by GeyserRecipeFixPatcher v" + (installedPatcherVersion == null ? "none" : installedPatcherVersion)
                        + "; running v" + getPluginMeta().getVersion() + ")"
                        + (restartRequired ? " - restart pending!" : ""));
            }
            case "check", "update" -> {
                sender.sendMessage("Checking Modrinth for the latest GeyserRecipeFix build...");
                getServer().getAsyncScheduler().runNow(this, task -> runAndReport(sender::sendMessage));
            }
            default -> sender.sendMessage("Usage: /grfpatcher <status|update>");
        }
        return true;
    }
}
