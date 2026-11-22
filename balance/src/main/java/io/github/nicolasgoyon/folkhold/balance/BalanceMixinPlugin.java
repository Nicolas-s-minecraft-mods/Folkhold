package io.github.nicolasgoyon.folkhold.balance;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * The plugin of the mixin configuration of the balance mod. It skips the mixin of the optional mod Slumber
 * ({@code SlumberSimulatorMixin}) when the game has no Slumber. Thus Mixin does not try to load the target class of
 * this mixin, and the log has no warning about a missing class. It applies all the other mixins.
 */
public final class BalanceMixinPlugin implements IMixinConfigPlugin {
    /** The mod id of Slumber. */
    private static final String SLUMBER_ID = "slumber";
    private static final String SLUMBER_MIXIN = ".SlumberSimulatorMixin";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !mixinClassName.endsWith(SLUMBER_MIXIN) || hasSlumber();
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
    }

    /** True if the mod list of the loader has Slumber. */
    private static boolean hasSlumber() {
        LoadingModList mods = LoadingModList.get();
        return mods != null && mods.getModFileById(SLUMBER_ID) != null;
    }
}
