package dev.nitro.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.function.BooleanSupplier;

public abstract class Setting<T> {
    public final String name;
    public T value;
    public final T def;
    public BooleanSupplier visible = () -> true;

    protected Setting(String name, T def) {
        this.name = name;
        this.def = def;
        this.value = def;
    }

    /** Only show this setting in the GUI while the supplier returns true. */
    public void showIf(BooleanSupplier s) {
        this.visible = s;
    }

    public abstract JsonElement save();

    public abstract void load(JsonElement e);

    public static final class Bool extends Setting<Boolean> {
        public Bool(String name, boolean def) {
            super(name, def);
        }

        public boolean on() {
            return value;
        }

        @Override
        public JsonElement save() {
            return new JsonPrimitive(value);
        }

        @Override
        public void load(JsonElement e) {
            value = e.getAsBoolean();
        }
    }

    public static final class Num extends Setting<Double> {
        public final double min, max, step;

        public Num(String name, double def, double min, double max, double step) {
            super(name, def);
            this.min = min;
            this.max = max;
            this.step = step;
        }

        public double d() {
            return value;
        }

        public float f() {
            return value.floatValue();
        }

        public int i() {
            return (int) Math.round(value);
        }

        public void set(double v) {
            v = Math.round(v / step) * step;
            value = Math.max(min, Math.min(max, v));
        }

        public void setFraction(double f) {
            set(min + (max - min) * Math.max(0, Math.min(1, f)));
        }

        public double fraction() {
            return (value - min) / (max - min);
        }

        @Override
        public JsonElement save() {
            return new JsonPrimitive(value);
        }

        @Override
        public void load(JsonElement e) {
            set(e.getAsDouble());
        }
    }

    public static final class Choice<E extends java.lang.Enum<E>> extends Setting<E> {
        private final E[] all;

        public Choice(String name, E def, Class<E> cls) {
            super(name, def);
            this.all = cls.getEnumConstants();
        }

        public E[] options() {
            return all;
        }

        public void set(int i) {
            if (i >= 0 && i < all.length) value = all[i];
        }

        public void next(int dir) {
            int i = (value.ordinal() + dir + all.length) % all.length;
            value = all[i];
        }

        @Override
        public JsonElement save() {
            return new JsonPrimitive(value.name());
        }

        @Override
        public void load(JsonElement e) {
            String s = e.getAsString();
            for (E c : all) {
                if (c.name().equals(s)) {
                    value = c;
                    return;
                }
            }
        }
    }

    /** RGB colour stored as 0xRRGGBB. */
    public static final class Color extends Setting<Integer> {
        public Color(String name, int rgb) {
            super(name, rgb);
        }

        public int rgb() {
            return value & 0xFFFFFF;
        }

        /** channel 0 = red, 1 = green, 2 = blue */
        public int ch(int c) {
            return (value >> (16 - 8 * c)) & 0xFF;
        }

        public void setCh(int c, int v) {
            int sh = 16 - 8 * c;
            v = Math.max(0, Math.min(255, v));
            value = (value & ~(0xFF << sh)) | (v << sh);
        }

        @Override
        public JsonElement save() {
            return new JsonPrimitive(value);
        }

        @Override
        public void load(JsonElement e) {
            value = e.getAsInt() & 0xFFFFFF;
        }
    }
}
