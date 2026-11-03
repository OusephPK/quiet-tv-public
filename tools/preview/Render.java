package com.quiet.px;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The home screen's world, rendered on a desktop JVM (no TV, no emulator): the same Stage, Life,
 * Dog, Vista... with a stand-in clock, list and icon bar. See preview.sh.
 *
 *   java com.quiet.px.Render out.png [seconds] [scale]     (-Dbar=true: the icon bar focused)
 */
public class Render {
    public static void main(String[] a) throws Exception {
        String out = a[0];
        float secs = a.length > 1 ? Float.parseFloat(a[1]) : 30;
        int scale = a.length > 2 ? Integer.parseInt(a[2]) : 3;
        boolean inBar = Boolean.getBoolean("bar");

        Stage s = new Stage("com.quiet.home".hashCode());
        Menu menu = new Menu(26, 72, 86);
        menu.items.add(new Menu.Item("WATCH"));
        menu.items.add(new Menu.Item("LISTEN"));
        menu.items.add(Menu.Item.gap());
        for (String l : new String[]{"YOUTUBE", "APPLE TV", "NETFLIX"}) menu.items.add(new Menu.Item(l));
        IconBar bar = new IconBar();
        List<IconBar.Icon> icons = new ArrayList<>();
        icons.add(new IconBar.Icon(IconBar.INPUTS, "INPUTS", "i"));
        icons.add(new IconBar.Icon(IconBar.APPS, "ALL APPS", "a"));
        icons.add(new IconBar.Icon(IconBar.SOUND_ON, "MUSIC + NATURE", "s"));
        icons.add(new IconBar.Icon(IconBar.SETTINGS, "SETTINGS", "g"));
        bar.set(icons);
        if (inBar) bar.focus = 2;

        String carved = null;
        for (int f = 0, n = Math.round(secs * 20); f < n; f++) {      // 20 fps, as on the TV
            android.os.SystemClock.now += 50;
            s.tick(0.05f);
            s.nBlits = 0;
            s.nOver = 0;
            String c = inBar ? "" : menu.focused().label;
            if (!c.equals(carved)) {
                carved = c;
                s.carve(c, 298, 150, Font.width(c, 3, 1) <= 108 ? 3 : 2);
            }
            if (!c.isEmpty()) s.light(250, 138, 80, 0.3f, false);
            s.camera(menu.fraction());
            menu.active = !inBar;
            menu.lights(s);
            if (inBar) bar.lights(s);
            s.light(46, 24, 60, 0.2f, false);
            s.resolve();
            s.decor();
            int x = s.text("9", 14, 10, 4, Pal.TEXT);
            x = s.text(":", x, 10, 4, Pal.TEXT);
            s.text("41", x, 10, 4, Pal.TEXT);
            s.small("MON 2 NOV  ·  " + s.partOfDay() + "  ·  24°", 15, 43, 2, Pal.DIM);
            menu.draw(s);
            bar.draw(s, inBar);
            if (inBar) s.mote.perch(bar.perchX(), bar.perchY());
            else s.mote.perch(menu.perchX(), menu.perchY());
            s.actors();
            s.pool();
            s.reveal();
        }
        BufferedImage img = new BufferedImage(Stage.W * scale, Stage.H * scale, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < Stage.H * scale; y++)
            for (int x = 0; x < Stage.W * scale; x++) img.setRGB(x, y, s.out[(y / scale) * Stage.W + x / scale]);
        ImageIO.write(img, "png", new File(out));
    }
}
