package net.sideways_sky.geyserrecipefixpatcher.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

public final class JarPatcher {

    private static final String MAIN_CLASS_ENTRY = "net/sideways_sky/geyserrecipefix/Geyser_Recipe_Fix.class";
    private static final String PAPER_EVENTS_ENTRY = "net/sideways_sky/geyserrecipefix/events/PaperEvents.class";
    private static final String ANVIL_SIM_ENTRY = "net/sideways_sky/geyserrecipefix/inventories/AnvilSim.class";
    private static final String PLUGIN_YML_ENTRY = "plugin.yml";

    /** Thrown when the downloaded jar's structure doesn't match what our patches target. */
    public static final class UnsupportedUpstreamVersionException extends Exception {
        public UnsupportedUpstreamVersionException(String message) {
            super(message);
        }
    }

    /**
     * @param sourceJar     the freshly downloaded, unmodified official jar
     * @param destinationJar where to write the patched result
     * @param injectedClassLoader used to read the raw bytes of our own injected
     *                            helper classes, bundled as resources in this plugin's jar
     */
    private int compatRewrites = 0;

    /** How many Paper-compat redirects (see PaperCompatTransformer) the last patch() applied. */
    public int lastCompatRewrites() {
        return compatRewrites;
    }

    public void patch(Path sourceJar, Path destinationJar, ClassLoader injectedClassLoader)
            throws IOException, UnsupportedUpstreamVersionException {

        compatRewrites = 0;
        boolean mainClassSeen = false;
        boolean paperEventsSeen = false;
        boolean anvilSimSeen = false;
        boolean pluginYmlSeen = false;
        MainClassTransformer mainTransform = null;
        PaperEventsTransformer paperTransform = null;
        AnvilSimTransformer anvilSimTransform = null;

        Path tmp = destinationJar.resolveSibling(destinationJar.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);

        try (JarFile jar = new JarFile(sourceJar.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(tmp))) {

            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                byte[] data;

                if (MAIN_CLASS_ENTRY.equals(entry.getName())) {
                    mainClassSeen = true;
                    ClassReader reader = new ClassReader(jar.getInputStream(entry));
                    ClassWriter writer = new ClassWriter(reader, 0);
                    MainClassTransformer transformer = new MainClassTransformer(writer);
                    reader.accept(transformer, 0);
                    mainTransform = transformer;
                    data = writer.toByteArray();

                } else if (PAPER_EVENTS_ENTRY.equals(entry.getName())) {
                    paperEventsSeen = true;
                    ClassReader reader = new ClassReader(jar.getInputStream(entry));
                    ClassWriter writer = new ClassWriter(reader, 0);
                    PaperEventsTransformer transformer = new PaperEventsTransformer(writer);
                    reader.accept(transformer, 0);
                    paperTransform = transformer;
                    data = writer.toByteArray();

                } else if (PLUGIN_YML_ENTRY.equals(entry.getName())) {
                    pluginYmlSeen = true;
                    data = patchPluginYml(jar.getInputStream(entry).readAllBytes());

                } else if (ANVIL_SIM_ENTRY.equals(entry.getName())) {
                    anvilSimSeen = true;
                    AnvilSimTransformer transformer = new AnvilSimTransformer();
                    data = transformer.transform(jar.getInputStream(entry).readAllBytes());
                    anvilSimTransform = transformer;

                } else {
                    data = jar.getInputStream(entry).readAllBytes();
                }

                if (entry.getName().endsWith(".class") && PaperCompatTransformer.mightNeedRewrite(data)) {
                    ClassReader compatReader = new ClassReader(data);
                    ClassWriter compatWriter = new ClassWriter(compatReader, 0);
                    PaperCompatTransformer compat = new PaperCompatTransformer(compatWriter);
                    compatReader.accept(compat, 0);
                    if (compat.rewrites() > 0) {
                        data = compatWriter.toByteArray();
                        compatRewrites += compat.rewrites();
                    }
                }

                JarEntry outEntry = new JarEntry(entry.getName());
                outEntry.setTime(entry.getTime());
                out.putNextEntry(outEntry);
                out.write(data);
                out.closeEntry();
            }

            // Splice in our own, entirely original helper classes.
            for (String injectedEntryName : listInjectedClasses(injectedClassLoader)) {
                byte[] bytes;
                try (InputStream in = injectedClassLoader.getResourceAsStream("injected/" + injectedEntryName)) {
                    if (in == null) {
                        throw new IOException("Missing bundled injected class: " + injectedEntryName);
                    }
                    bytes = in.readAllBytes();
                }
                JarEntry outEntry = new JarEntry(injectedEntryName);
                out.putNextEntry(outEntry);
                out.write(bytes);
                out.closeEntry();
            }
        }

        List<String> problems = new ArrayList<>();
        if (!mainClassSeen) problems.add("Geyser_Recipe_Fix.class not found in the downloaded jar");
        if (!paperEventsSeen) problems.add("PaperEvents.class not found in the downloaded jar");
        if (!anvilSimSeen) problems.add("AnvilSim.class not found in the downloaded jar");
        if (!pluginYmlSeen) problems.add("plugin.yml not found in the downloaded jar");
        if (mainTransform != null) {
            if (!mainTransform.appliedHashMapSwap()) problems.add("openMenus HashMap allocation not found (onEnable/<clinit> may have changed upstream)");
            if (!mainTransform.appliedLoggerHook()) problems.add("`logger = getLogger();` assignment not found in onEnable()");
        }
        if (paperTransform != null) {
            if (!paperTransform.appliedFieldWiden()) problems.add("forwardSkips field not found");
            if (!paperTransform.appliedHashSetSwap()) problems.add("forwardSkips HashSet allocation not found");
            if (!paperTransform.appliedMethodReplace()) problems.add("openForward(HumanEntity) method not found");
        }
        if (anvilSimTransform != null) {
            if (!anvilSimTransform.applied()) problems.add("setCost(int, Player) method not found in AnvilSim");
        }

        if (!problems.isEmpty()) {
            Files.deleteIfExists(tmp);
            throw new UnsupportedUpstreamVersionException(
                    "The downloaded GeyserRecipeFix build doesn't match what this patcher expects, so nothing was "
                    + "installed (this usually means upstream changed something the patch targets - please check for "
                    + "a GeyserRecipeFixPatcher update). Details: " + String.join("; ", problems));
        }

        Files.move(tmp, destinationJar, StandardCopyOption.REPLACE_EXISTING);
    }

    private byte[] patchPluginYml(byte[] original) {
        String text = new String(original, StandardCharsets.UTF_8);
        if (text.contains("folia-supported")) {
            return original; // already present, nothing to do
        }
        String withFlag = text + (text.endsWith("\n") ? "" : "\n") + "folia-supported: true\n";
        return withFlag.getBytes(StandardCharsets.UTF_8);
    }

    private List<String> listInjectedClasses(ClassLoader cl) throws IOException {
        // The exact set this project ships; kept explicit (rather than
        // scanned) so a change here is a deliberate, reviewable edit.
        return List.of(
                "net/sideways_sky/geyserrecipefix/FoliaRuntimeSupport.class",
                "net/sideways_sky/geyserrecipefix/events/FoliaAnvilBridge.class",
                "net/sideways_sky/geyserrecipefix/inventories/AnvilRenameFix.class",
                "net/sideways_sky/geyserrecipefix/PaperCompat.class"
        );
    }
}
