package io.github.nicolasgoyon.folkhold.balance;

import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * The plugin of the mixin configuration of the balance mod. It applies all the mixins. After the mixin of the
 * optional mod Slumber, it examines the result: when an injector found no target (a different version of Slumber),
 * it writes a warning to the log.
 */
public final class BalanceMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SLUMBER_MIXIN = "SlumberSimulatorMixin";
    private static final List<String> SLUMBER_HANDLERS =
            List.of("folkhold_balance$limitSleepTicks", "folkhold_balance$timeSleepTick");

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
        if (!mixinClassName.endsWith("." + SLUMBER_MIXIN)) {
            return;
        }
        for (String handler : SLUMBER_HANDLERS) {
            if (!callsHandler(targetClass, handler)) {
                LOGGER.warn("Folkhold Balance: this version of Slumber is not supported ({} found no target). "
                        + "The limit of the extra ticks during a sleep (section sleep of the server configuration) "
                        + "does not apply.", handler);
            }
        }
    }

    /** True if a method of the class calls the merged handler with this name. */
    private static boolean callsHandler(ClassNode targetClass, String handler) {
        for (MethodNode method : targetClass.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.contains(handler)) {
                    return true;
                }
            }
        }
        return false;
    }
}
