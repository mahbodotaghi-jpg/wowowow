package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.CombatUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * Anchor Macro: while you look at a respawn anchor (and hold right click, if that option is on) it swaps to glowstone,
 * charges the anchor, swaps to your "explode" slot and detonates it, one step per delay. Only works where anchors
 * explode (Overworld / End).
 */
public final class AnchorMacro extends Module {
    private final Setting.Num charges = new Setting.Num("Charges before explode", 1, 1, 4, 1);
    private final Setting.Num explodeSlot = new Setting.Num("Explode slot (hotbar 1-9)", 1, 1, 9, 1);
    private final Setting.Num delay = new Setting.Num("Delay (ticks)", 1, 0, 10, 1);
    private final Setting.Bool needUse = new Setting.Bool("Only while holding right click", true);
    private final Setting.Bool needTotem = new Setting.Bool("Require offhand totem", true);
    private final Setting.Bool swapBack = new Setting.Bool("Switch back after explode", false);

    private int timer, backSlot = -1;

    public AnchorMacro() {
        super("Anchor Macro", "Charges and explodes the respawn anchor you look at.", Category.COMBAT);
        add(charges, explodeSlot, delay, needUse, needTotem, swapBack);
    }

    @Override
    public void onEnable() {
        timer = 0;
        backSlot = -1;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (timer > 0) {
            timer--;
            return;
        }
        if (mc.currentScreen != null) return;
        if (mc.world.getDimension().respawnAnchorWorks()) return; // Nether: anchors do not explode
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        BlockState st = mc.world.getBlockState(hit.getBlockPos());
        if (!st.isOf(Blocks.RESPAWN_ANCHOR)) return;
        if (needUse.on() && !mc.options.useKey.isPressed()) return;
        if (needTotem.on() && !mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;

        int have = st.get(RespawnAnchorBlock.CHARGES);
        if (have < charges.i()) {
            int g = CombatUtil.findHotbar(Items.GLOWSTONE);
            if (g < 0) return;
            if (backSlot < 0) backSlot = mc.player.getInventory().selectedSlot;
            CombatUtil.selectHotbar(g);
        } else {
            CombatUtil.selectHotbar(explodeSlot.i() - 1);
        }

        if (mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit).isAccepted()) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        if (have >= charges.i() && swapBack.on() && backSlot >= 0) {
            CombatUtil.selectHotbar(backSlot);
            backSlot = -1;
        }
        timer = delay.i();
    }
}
