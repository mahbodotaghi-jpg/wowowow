package dev.nitro.modules;

import dev.nitro.mixin.SimpleOptionAccessor;
import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import net.minecraft.client.option.SimpleOption;

/**
 * Zoom: narrows the field of view while enabled (bind it to a key in the ClickGUI to toggle it quickly).
 * The original FOV and mouse sensitivity are restored on disable, when leaving a world and when the game closes.
 */
public final class Zoom extends Module {
    public static Zoom INSTANCE;

    private final Setting.Num level = new Setting.Num("Zoom level", 4, 1.5, 12, 0.5);
    private final Setting.Bool smooth = new Setting.Bool("Smooth zoom", true);
    private final Setting.Bool lowerSens = new Setting.Bool("Lower sensitivity", true);

    private Integer savedFov;
    private Double savedSens;
    private double cur;

    public Zoom() {
        super("Zoom", "Zooms in like a spyglass. Bind a key to it for quick use.", Category.RENDER);
        add(level, smooth, lowerSens);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.world == null) {
            enabled = false;
        }
    }

    @Override
    public void onTick() {
        if (mc.options == null) return;
        if (savedFov == null) {
            savedFov = mc.options.getFov().getValue();
            savedSens = mc.options.getMouseSensitivity().getValue();
            cur = savedFov;
        }
        double target = Math.max(4.0, savedFov / level.d());
        cur = smooth.on() ? cur + (target - cur) * 0.4 : target;
        int fov = (int) Math.round(cur);
        SimpleOption<Integer> opt = mc.options.getFov();
        if (opt.getValue() != fov) {
            @SuppressWarnings("unchecked")
            SimpleOptionAccessor<Integer> acc = (SimpleOptionAccessor<Integer>) (Object) opt;
            acc.nitro$setValue(fov);
        }
        if (lowerSens.on() && savedSens != null) {
            mc.options.getMouseSensitivity().setValue(savedSens / Math.pow(level.d(), 0.7));
        } else if (savedSens != null && mc.options.getMouseSensitivity().getValue().doubleValue() != savedSens.doubleValue()) {
            mc.options.getMouseSensitivity().setValue(savedSens);
        }
    }

    @Override
    public void onDisable() {
        restore0();
    }

    @Override
    public void onWorldChanged() {
        if (mc.world == null) restore0();
    }

    private void restore0() {
        if (mc.options == null) return;
        if (savedFov != null) {
            @SuppressWarnings("unchecked")
            SimpleOptionAccessor<Integer> acc = (SimpleOptionAccessor<Integer>) (Object) mc.options.getFov();
            acc.nitro$setValue(savedFov);
        }
        if (savedSens != null) mc.options.getMouseSensitivity().setValue(savedSens);
        savedFov = null;
        savedSens = null;
    }

    /** Called when the client is shutting down so options.txt never keeps a zoomed FOV. */
    public static void restore() {
        if (INSTANCE != null) INSTANCE.restore0();
    }
}
