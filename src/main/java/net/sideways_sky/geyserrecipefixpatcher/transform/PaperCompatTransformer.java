package net.sideways_sky.geyserrecipefixpatcher.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;

/**
 * Generic pass applied to every class in the downloaded jar: redirects uses
 * of Paper-specific members that newer Paper versions removed or locked down
 * to same-shaped methods in our injected {@code PaperCompat} class.
 *
 * - {@code INVOKESTATIC ItemStack.fromBukkitCopy(bukkit ItemStack)} ->
 *   {@code PaperCompat.fromBukkitCopy(...)} (identical descriptor).
 * - {@code GETFIELD ServerPlayer.containerSynchronizer} ->
 *   {@code INVOKESTATIC PaperCompat.containerSynchronizer(ServerPlayer)}
 *   (both consume one ServerPlayer reference and leave one
 *   ContainerSynchronizer, so the surrounding code is untouched).
 */
public final class PaperCompatTransformer extends ClassVisitor {

    private static final String HELPER = "net/sideways_sky/geyserrecipefix/PaperCompat";

    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String FROM_BUKKIT_COPY = "fromBukkitCopy";
    private static final String FROM_BUKKIT_COPY_DESC = "(Lorg/bukkit/inventory/ItemStack;)Lnet/minecraft/world/item/ItemStack;";

    private static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    private static final String SYNC_FIELD = "containerSynchronizer";
    private static final String SYNC_FIELD_DESC = "Lnet/minecraft/world/inventory/ContainerSynchronizer;";
    private static final String SYNC_METHOD_DESC = "(L" + SERVER_PLAYER + ";)" + SYNC_FIELD_DESC;

    private static final byte[] NEEDLE_COPY = FROM_BUKKIT_COPY.getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEEDLE_SYNC = SYNC_FIELD.getBytes(StandardCharsets.UTF_8);

    private int rewrites = 0;

    public PaperCompatTransformer(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    /** Cheap pre-check so classes that can't contain either reference are never parsed or rewritten. */
    public static boolean mightNeedRewrite(byte[] classBytes) {
        return contains(classBytes, NEEDLE_COPY) || contains(classBytes, NEEDLE_SYNC);
    }

    public int rewrites() {
        return rewrites;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                if (opcode == Opcodes.INVOKESTATIC && ITEM_STACK.equals(owner)
                        && FROM_BUKKIT_COPY.equals(mName) && FROM_BUKKIT_COPY_DESC.equals(mDesc)) {
                    rewrites++;
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, FROM_BUKKIT_COPY, FROM_BUKKIT_COPY_DESC, false);
                } else {
                    super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                }
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String fName, String fDesc) {
                if (opcode == Opcodes.GETFIELD && SERVER_PLAYER.equals(owner)
                        && SYNC_FIELD.equals(fName) && SYNC_FIELD_DESC.equals(fDesc)) {
                    rewrites++;
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, SYNC_FIELD, SYNC_METHOD_DESC, false);
                } else {
                    super.visitFieldInsn(opcode, owner, fName, fDesc);
                }
            }
        };
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
