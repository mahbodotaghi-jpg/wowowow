package dev.nitro.gui;

import dev.nitro.Config;
import dev.nitro.module.Module;
import dev.nitro.module.ModuleManager;
import dev.nitro.settings.Setting;
import dev.nitro.util.ColorUtil;
import dev.nitro.util.Gfx;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NITRO ClickGUI: animated violet-to-cyan accent, fade/slide-in, category tabs with a sliding highlight,
 * searchable module cards with animated switches, and a settings pane with sliders, chips and colour bars.
 */
public final class ClickGui extends Screen {
    private static final int A1 = 0x8B5CF6, A2 = 0x22D3EE;
    private static final int TEXT = 0xFFF1F0F7;
    private static final int DIM = 0xFF8E8AA3;
    private static final int TRACK = 0xFF3A3650;

    private static final int TAB_W = 104, LIST_W = 200, HEAD = 46, CARD_H = 38, GAP = 4;

    private int catIndex = 1; // 0 = favourites
    private Module selected;
    private int listScroll, setScroll;
    private boolean binding;
    private String search = "";
    private boolean searchFocus;

    private Setting.Num dragNum;
    private int dragX0, dragW;
    private Setting.Color dragColor;
    private int dragCh;

    private final Map<Object, Float> anim = new HashMap<>();
    private final long openedAt = System.nanoTime();
    private long lastFrame = openedAt;
    private float time, dt, alpha, slide, tabY = -1;

    private int wx, wy, ww, wh;

    private enum Kind { ENABLE, BIND, FAV, SETTING }

    private record Row(Kind kind, Setting<?> s, int y, int h) {}

    public ClickGui() {
        super(Text.literal("NITRO"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------ geometry

    private void layout() {
        time = (System.nanoTime() - openedAt) / 1e9f;
        float p = Math.min(1f, time / 0.28f);
        float e = 1f - (1f - p) * (1f - p) * (1f - p);
        alpha = e;
        slide = (1f - e) * 16f;
        ww = Math.min(width - 20, 660);
        wh = Math.min(height - 20, 370);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2 + Math.round(slide);
    }

    private int listX() { return wx + TAB_W; }
    private int setX() { return listX() + LIST_W + 6; }
    private int setR() { return wx + ww - 10; }
    private int top() { return wy + HEAD; }
    private int bot() { return wy + wh - 10; }
    private int searchX() { return wx + ww - 16 - 190; }

    // ------------------------------------------------------------ colour helpers

    private int accent(float t) {
        float s = 0.5f + 0.5f * (float) Math.sin(time * 1.1f + t * 2.6f);
        return ColorUtil.lerp(A1, A2, s);
    }

    private int ac(float t, float a) {
        return ColorUtil.argb(accent(t), a * alpha);
    }

    private int A(int argb) {
        return Gfx.mulA(argb, alpha);
    }

    private float an(Object key, boolean on) {
        float cur = anim.getOrDefault(key, on ? 1f : 0f);
        float target = on ? 1f : 0f;
        cur += (target - cur) * Math.min(1f, dt * 14f);
        if (Math.abs(target - cur) < 0.005f) cur = target;
        anim.put(key, cur);
        return cur;
    }

    private void text(DrawContext c, String s, int x, int y, int argb) {
        if (((argb >>> 24) & 0xFF) < 8) return;
        c.drawText(textRenderer, s, x, y, argb, false);
    }

    private void small(DrawContext c, String s, float x, float y, float scale, int argb) {
        if (((argb >>> 24) & 0xFF) < 8) return;
        Gfx.text(c, textRenderer, s, x, y, scale, argb, false);
    }

    private void sw(DrawContext c, int x, int y, Object key, boolean on) {
        float t = an(key, on);
        int track = Gfx.lerpArgb(A(TRACK), ac(x / 120f, 1f), t);
        Gfx.roundFill(c, x, y, 26, 14, 7, track);
        int kx = x + 2 + Math.round(12 * t);
        Gfx.roundFill(c, kx, y + 2, 10, 10, 5, A(0xFFF8F8FC));
    }

    // ------------------------------------------------------------ content model

    private List<Module> visibleModules() {
        List<Module> out = new ArrayList<>();
        Module.Category[] cats = Module.Category.values();
        String q = search.trim().toLowerCase(Locale.ROOT);
        for (Module m : ModuleManager.INSTANCE.modules) {
            boolean ok;
            if (!q.isEmpty()) {
                ok = m.name.toLowerCase(Locale.ROOT).contains(q) || m.description.toLowerCase(Locale.ROOT).contains(q);
            } else {
                ok = catIndex == 0 ? m.favorite : m.category == cats[catIndex - 1];
            }
            if (ok) out.add(m);
        }
        return out;
    }

    private int enabledIn(int tab) {
        int n = 0;
        Module.Category[] cats = Module.Category.values();
        for (Module m : ModuleManager.INSTANCE.modules) {
            boolean in = tab == 0 ? m.favorite : m.category == cats[tab - 1];
            if (in && m.enabled) n++;
        }
        return n;
    }

    private int rowHeight(Setting<?> s) {
        if (s instanceof Setting.Num) return 36;
        if (s instanceof Setting.Color) return 44;
        if (s instanceof Setting.Choice<?> c) return 24 + c.options().length * 15;
        return 26;
    }

    private int headerHeight() {
        if (selected == null) return 0;
        int lines = textRenderer.wrapLines(Text.literal(selected.description), setR() - setX() - 28).size();
        return 30 + lines * 10 + 8;
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (selected == null) return rows;
        int y = top() + 6 + headerHeight() - setScroll;
        rows.add(new Row(Kind.ENABLE, null, y, 28)); y += 32;
        rows.add(new Row(Kind.BIND, null, y, 26)); y += 30;
        rows.add(new Row(Kind.FAV, null, y, 26)); y += 34;
        for (Setting<?> s : selected.settings) {
            if (!s.visible.getAsBoolean()) continue;
            int h = rowHeight(s);
            rows.add(new Row(Kind.SETTING, s, y, h));
            y += h + GAP;
        }
        return rows;
    }

    private int contentHeight() {
        List<Row> r = rows();
        if (r.isEmpty()) return 0;
        Row last = r.get(r.size() - 1);
        return last.y() + last.h() + setScroll - top();
    }

    // ------------------------------------------------------------ render

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        layout();
        long now = System.nanoTime();
        dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;

        ctx.fillGradient(0, 0, width, height, A(0xB005040C), A(0xD00B0618));

        // window
        Gfx.glow(ctx, wx, wy, ww, wh, 12, accent(0.4f), 0.32f * alpha);
        Gfx.roundGradientV(ctx, wx, wy, ww, wh, 12, A(0xF21B1729), A(0xF20B0A13));
        Gfx.roundBorder(ctx, wx, wy, ww, wh, 12, ac(0.2f, 0.55f));
        for (int x = wx + 12; x < wx + ww - 12; x += 4) {
            ctx.fill(x, wy + 1, Math.min(x + 4, wx + ww - 12), wy + 3, ac((x - wx) / (float) ww, 0.95f));
        }

        drawHeader(ctx, mx, my);
        drawTabs(ctx, mx, my);
        drawList(ctx, mx, my);
        drawSettings(ctx, mx, my);
    }

    private void drawHeader(DrawContext ctx, int mx, int my) {
        // gradient logo
        String logo = "NITRO";
        float x = wx + 18;
        for (int i = 0; i < logo.length(); i++) {
            String ch = String.valueOf(logo.charAt(i));
            small(ctx, ch, x, wy + 13, 1.6f, ac(i / 4f, 1f));
            x += textRenderer.getWidth(ch) * 1.6f + 1f;
        }
        small(ctx, "CLIENT", x + 4, wy + 19, 0.75f, A(DIM));

        // search
        int sx = searchX(), sy = wy + 12, sw = 190, sh = 20;
        float f = an("search", searchFocus);
        Gfx.roundFill(ctx, sx, sy, sw, sh, 6, A(0x66000000));
        Gfx.roundBorder(ctx, sx, sy, sw, sh, 6, Gfx.lerpArgb(A(0x33FFFFFF), ac(0.6f, 0.9f), f));
        boolean empty = search.isEmpty();
        String shown = empty ? (searchFocus ? "" : "Search modules...") : search;
        while (textRenderer.getWidth(shown) > sw - 16) shown = shown.substring(1);
        text(ctx, shown, sx + 8, sy + 6, A(empty ? 0xFF6D6A80 : TEXT));
        if (searchFocus && (int) (time * 2) % 2 == 0) {
            int cx = sx + 8 + textRenderer.getWidth(shown);
            ctx.fill(cx, sy + 5, cx + 1, sy + 15, A(0xFFFFFFFF));
        }
    }

    private void drawTabs(DrawContext ctx, int mx, int my) {
        Module.Category[] cats = Module.Category.values();
        int n = cats.length + 1;
        boolean searching = !search.trim().isEmpty();
        int tx = wx + 10, tw = TAB_W - 16;

        float target = top() + 8 + catIndex * 30;
        tabY = tabY < 0 ? target : tabY + (target - tabY) * Math.min(1f, dt * 16f);
        if (!searching) {
            Gfx.roundFill(ctx, tx, Math.round(tabY), tw, 26, 7, ac(0.1f, 0.22f));
            Gfx.roundFill(ctx, tx, Math.round(tabY) + 5, 3, 16, 1, ac(0.0f, 1f));
        }
        for (int i = 0; i < n; i++) {
            int ty = top() + 8 + i * 30;
            boolean sel = i == catIndex && !searching;
            boolean hover = mx >= tx && mx < tx + tw && my >= ty && my < ty + 26;
            String label = i == 0 ? "Favorites" : cats[i - 1].label;
            int col = sel ? A(TEXT) : A(hover ? 0xFFC9C6DA : DIM);
            text(ctx, label, tx + 12, ty + 9, col);
            int cnt = enabledIn(i);
            if (cnt > 0) {
                String s = String.valueOf(cnt);
                Gfx.roundFill(ctx, tx + tw - 18, ty + 7, 14, 12, 6, ac(i / 5f, 0.35f));
                small(ctx, s, tx + tw - 11 - textRenderer.getWidth(s) * 0.4f, ty + 10, 0.8f, A(TEXT));
            }
        }
        // footer hint
        small(ctx, "RShift: close", tx + 4, bot() - 8, 0.75f, A(0xFF5F5C73));
    }

    private void drawList(DrawContext ctx, int mx, int my) {
        int lx = listX() + 2, lw = LIST_W - 4;
        Gfx.roundFill(ctx, lx, top(), lw, bot() - top(), 9, A(0x55000000));
        ctx.enableScissor(lx, top() + 1, lx + lw, bot() - 1);
        List<Module> mods = visibleModules();
        int y = top() + 6 - listScroll;
        if (mods.isEmpty()) {
            String msg = !search.trim().isEmpty() ? "No match" : catIndex == 0 ? "Star a module to pin it here" : "No modules";
            text(ctx, msg, lx + 12, y + 6, A(DIM));
        }
        for (Module m : mods) {
            int cx = lx + 6, cw = lw - 12;
            boolean sel = m == selected;
            boolean hover = mx >= cx && mx < cx + cw && my >= y && my < y + CARD_H && my >= top() && my < bot();
            float on = an(m, m.enabled);
            float hv = an(m.name + "#h", hover);
            int base = Gfx.lerpArgb(A(0x33FFFFFF), ac(0.3f, 0.20f), on);
            base = Gfx.lerpArgb(base, A(0x4DFFFFFF), hv * 0.5f);
            Gfx.roundFill(ctx, cx, y, cw, CARD_H, 8, base);
            if (sel) Gfx.roundBorder(ctx, cx, y, cw, CARD_H, 8, ac(0.5f, 0.9f));
            if (on > 0.02f) Gfx.roundFill(ctx, cx, y + 6, 3, CARD_H - 12, 1, ac(y / 140f, on));

            text(ctx, m.favorite ? "*" : "+", cx + 9, y + 6, m.favorite ? ac(0.1f, 1f) : A(0xFF6D6A80));
            text(ctx, m.name, cx + 20, y + 6, A(Gfx.lerpArgb(0xFFD2CFE2, 0xFFFFFFFF, on)));
            String desc = textRenderer.trimToWidth(m.description, (int) ((cw - 66) / 0.75f));
            small(ctx, desc, cx + 20, y + 21, 0.75f, A(DIM));
            sw(ctx, cx + cw - 34, y + 12, m, m.enabled);
            y += CARD_H + GAP;
        }
        ctx.disableScissor();
    }

    private void drawSettings(DrawContext ctx, int mx, int my) {
        int sx = setX(), sw = setR() - sx;
        Gfx.roundFill(ctx, sx, top(), sw, bot() - top(), 9, A(0x55000000));
        if (selected == null) {
            text(ctx, "Select a module", sx + 16, top() + 16, A(DIM));
            text(ctx, "Click a card to see its settings", sx + 16, top() + 30, A(0xFF5F5C73));
            return;
        }
        ctx.enableScissor(sx, top() + 1, sx + sw, bot() - 1);
        int ix = sx + 12, iw = sw - 24;

        // header: name + description
        int hy = top() + 8 - setScroll;
        small(ctx, selected.name, ix, hy + 2, 1.5f, A(0xFFFFFFFF));
        int ly = hy + 28;
        for (OrderedText line : textRenderer.wrapLines(Text.literal(selected.description), iw - 4)) {
            ctx.drawText(textRenderer, line, ix, ly, A(DIM), false);
            ly += 10;
        }

        for (Row r : rows()) {
            if (r.y() + r.h() < top() || r.y() > bot()) continue;
            switch (r.kind()) {
                case ENABLE -> {
                    float t = an(selected.name + "#big", selected.enabled);
                    int c1 = Gfx.lerpArgb(A(0x44FFFFFF), ac(0.1f, 0.9f), t);
                    int c2 = Gfx.lerpArgb(A(0x33FFFFFF), ac(0.9f, 0.9f), t);
                    Gfx.roundGradientV(ctx, ix, r.y(), iw, r.h(), 8, c1, c2);
                    String s = selected.enabled ? "ENABLED" : "DISABLED";
                    text(ctx, s, ix + iw / 2 - textRenderer.getWidth(s) / 2, r.y() + 10, A(0xFFFFFFFF));
                }
                case BIND -> {
                    card(ctx, ix, r, iw);
                    text(ctx, "Shortcut", ix + 10, r.y() + 9, A(TEXT));
                    String k = binding ? "press a key..." : keyName(selected.key);
                    int w = textRenderer.getWidth(k) + 14;
                    Gfx.roundFill(ctx, ix + iw - w - 6, r.y() + 4, w, 18, 5, binding ? ac(0.2f, 0.35f) : A(0x44000000));
                    text(ctx, k, ix + iw - w - 6 + 7, r.y() + 9, binding ? A(0xFFFFFFFF) : A(DIM));
                }
                case FAV -> {
                    card(ctx, ix, r, iw);
                    text(ctx, "Favorite", ix + 10, r.y() + 9, A(TEXT));
                    sw(ctx, ix + iw - 36, r.y() + 6, "fav", selected.favorite);
                }
                case SETTING -> drawSetting(ctx, r, ix, iw, mx, my);
            }
        }
        ctx.disableScissor();
    }

    private void card(DrawContext ctx, int ix, Row r, int iw) {
        Gfx.roundFill(ctx, ix, r.y(), iw, r.h(), 8, A(0x33FFFFFF));
    }

    private void drawSetting(DrawContext ctx, Row r, int ix, int iw, int mx, int my) {
        Setting<?> s = r.s();
        int y = r.y();
        card(ctx, ix, r, iw);
        text(ctx, s.name, ix + 10, y + 9, A(TEXT));
        if (s instanceof Setting.Bool b) {
            sw(ctx, ix + iw - 36, y + 6, s, b.on());
        } else if (s instanceof Setting.Choice<?> c) {
            Object[] opts = c.options();
            for (int i = 0; i < opts.length; i++) {
                boolean on = opts[i] == c.value;
                int oy = y + 22 + i * 15;
                float t = an(c.name + "#" + System.identityHashCode(c) + i, on);
                if (t > 0.02f) Gfx.roundFill(ctx, ix + 8, oy, iw - 16, 13, 5, ac(i / 4f, 0.30f * t));
                text(ctx, String.valueOf(opts[i]), ix + 16, oy + 3, A(on ? 0xFFFFFFFF : DIM));
                if (on) Gfx.roundFill(ctx, ix + iw - 20, oy + 4, 5, 5, 2, ac(i / 4f, 1f));
            }
        } else if (s instanceof Setting.Num n) {
            String v = n.step >= 1 ? String.valueOf(n.i()) : String.format("%.2f", n.d()).replaceAll("0+$", "").replaceAll("\\.$", "");
            text(ctx, v, ix + iw - 10 - textRenderer.getWidth(v), y + 9, A(0xFFFFFFFF));
            int x0 = ix + 10, w = iw - 20;
            Gfx.roundFill(ctx, x0, y + 25, w, 4, 2, A(TRACK));
            int fw = Math.max(4, (int) (w * n.fraction()));
            Gfx.roundFill(ctx, x0, y + 25, fw, 4, 2, ac(0.2f, 1f));
            Gfx.roundFill(ctx, x0 + fw - 4, y + 23, 8, 8, 4, A(0xFFFFFFFF));
        } else if (s instanceof Setting.Color c) {
            Gfx.roundFill(ctx, ix + iw - 30, y + 6, 20, 12, 4, A(0xFF000000 | c.rgb()));
            Gfx.roundBorder(ctx, ix + iw - 30, y + 6, 20, 12, 4, A(0x66FFFFFF));
            int x0 = ix + 10, w = (iw - 20) / 3;
            int[] cols = {0xFFE05555, 0xFF55C46A, 0xFF5587E0};
            for (int i = 0; i < 3; i++) {
                int sx = x0 + i * w;
                Gfx.roundFill(ctx, sx, y + 28, w - 6, 4, 2, A(TRACK));
                int fw = Math.max(4, (int) ((w - 6) * (c.ch(i) / 255.0)));
                Gfx.roundFill(ctx, sx, y + 28, fw, 4, 2, A(cols[i]));
                Gfx.roundFill(ctx, sx + fw - 4, y + 26, 8, 8, 4, A(0xFFFFFFFF));
            }
        }
    }

    private String keyName(int key) {
        if (key < 0) return "None";
        try {
            return InputUtil.fromKeyCode(key, 0).getLocalizedText().getString();
        } catch (Exception e) {
            return "Key " + key;
        }
    }

    // ------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        layout();
        // search box
        int sx = searchX(), sy = wy + 12;
        boolean inSearch = mx >= sx && mx < sx + 190 && my >= sy && my < sy + 20;
        searchFocus = inSearch;
        if (inSearch) return true;

        // tabs
        int tx = wx + 10, tw = TAB_W - 16;
        if (mx >= tx && mx < tx + tw && my >= top()) {
            int n = Module.Category.values().length + 1;
            for (int i = 0; i < n; i++) {
                int ty = top() + 8 + i * 30;
                if (my >= ty && my < ty + 26) {
                    catIndex = i;
                    search = "";
                    listScroll = 0;
                    return true;
                }
            }
        }
        // list
        int lx = listX() + 2, lw = LIST_W - 4;
        if (mx >= lx && mx < lx + lw && my >= top() && my < bot()) {
            int y = top() + 6 - listScroll;
            int cx = lx + 6, cw = lw - 12;
            for (Module m : visibleModules()) {
                if (my >= y && my < y + CARD_H && mx >= cx && mx < cx + cw) {
                    if (button == 1 || mx >= cx + cw - 40) m.toggle();
                    else if (mx < cx + 20) m.favorite = !m.favorite;
                    else {
                        selected = m;
                        setScroll = 0;
                        binding = false;
                    }
                    return true;
                }
                y += CARD_H + GAP;
            }
            return true;
        }
        // settings
        if (selected != null && mx >= setX() && mx < setR() && my >= top() && my < bot()) {
            int ix = setX() + 12, iw = setR() - setX() - 24;
            for (Row r : rows()) {
                if (my < r.y() || my >= r.y() + r.h()) continue;
                if (mx < ix || mx > ix + iw) break;
                switch (r.kind()) {
                    case ENABLE -> selected.toggle();
                    case BIND -> binding = !binding;
                    case FAV -> selected.favorite = !selected.favorite;
                    case SETTING -> clickSetting(r, mx, my, button, ix, iw);
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private void clickSetting(Row r, double mx, double my, int button, int ix, int iw) {
        Setting<?> s = r.s();
        if (s instanceof Setting.Bool b) {
            b.value = !b.value;
        } else if (s instanceof Setting.Choice<?> c) {
            int idx = (int) Math.floor((my - (r.y() + 22)) / 15.0);
            if (idx >= 0 && idx < c.options().length) c.set(idx);
            else c.next(button == 1 ? -1 : 1);
        } else if (s instanceof Setting.Num n) {
            dragNum = n;
            dragX0 = ix + 10;
            dragW = iw - 20;
            n.setFraction((mx - dragX0) / dragW);
        } else if (s instanceof Setting.Color c) {
            int w = (iw - 20) / 3;
            int ch = (int) ((mx - (ix + 10)) / w);
            if (ch < 0 || ch > 2) return;
            dragColor = c;
            dragCh = ch;
            dragX0 = ix + 10 + ch * w;
            dragW = w - 6;
            c.setCh(ch, (int) Math.round(255.0 * (mx - dragX0) / dragW));
        }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragNum != null) {
            dragNum.setFraction((mx - dragX0) / dragW);
            return true;
        }
        if (dragColor != null) {
            dragColor.setCh(dragCh, (int) Math.round(255.0 * (mx - dragX0) / dragW));
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragNum = null;
        dragColor = null;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        layout();
        if (mx >= listX() && mx < listX() + LIST_W) {
            int max = Math.max(0, visibleModules().size() * (CARD_H + GAP) + 12 - (bot() - top()));
            listScroll = Math.max(0, Math.min(max, listScroll - (int) (vAmount * 20)));
        } else if (mx >= setX()) {
            int max = Math.max(0, contentHeight() + 12 - (bot() - top()));
            setScroll = Math.max(0, Math.min(max, setScroll - (int) (vAmount * 20)));
        }
        return true;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (searchFocus && chr >= 32 && chr != 127 && search.length() < 24) {
            search += chr;
            listScroll = 0;
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (binding && selected != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                binding = false;
            } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE) {
                selected.key = -1;
                binding = false;
            } else {
                selected.key = keyCode;
                binding = false;
            }
            return true;
        }
        if (searchFocus) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchFocus = false;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        Config.save();
        super.close();
    }
}
