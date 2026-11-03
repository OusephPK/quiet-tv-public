package com.quiet.update;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

import com.quiet.px.Font;
import com.quiet.px.IconBar;
import com.quiet.px.Menu;
import com.quiet.px.Pal;
import com.quiet.px.PxActivity;
import com.quiet.px.Stage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates — keeps sideloaded apps current, straight from each project's official release feed.
 * No background service, no database, no ads: open it, it checks, OK installs.
 * Anything from the Play Store is the Play Store's job; there's a door to it at the bottom.
 */
public class Main extends PxActivity {

    static final String UA = "QuietUpdates/1.0 (Android TV)";
    static final String PLAY = "\u0001play", AGAIN = "\u0001again";

    interface Source {
        Release latest(String abi) throws IOException;
    }

    static final class Release {
        String version;
        long code;
        String url;
    }

    static final class Tracked {
        final String label, pkg;
        final Source source;
        Menu.Item item;
        PackageInfo installed;
        Release latest;
        boolean busy;

        Tracked(String label, String pkg, Source source) {
            this.label = label;
            this.pkg = pkg;
            this.source = source;
        }

        /** Not here yet, but its feed is: OK puts it on. */
        boolean canInstall() {
            return installed == null && latest != null;
        }

        boolean hasUpdate() {
            if (installed == null || latest == null) return false;
            if (latest.code > 0) return latest.code > installed.getLongVersionCode();
            return compare(latest.version, installed.versionName) > 0;
        }
    }

    // Sideloaded apps on this TV. Add a line here to track another.
    final List<Tracked> apps = new ArrayList<>();
    {
        apps.add(new Tracked("Stremio", "com.stremio.one", Main::stremio));
    }

    String abi, meta = "CHECKING";

    @Override
    protected Menu makeMenu() {
        return new Menu(24, 62, 86);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        abi = Build.SUPPORTED_ABIS[0];
        List<Menu.Item> items = new ArrayList<>();
        for (Tracked t : apps) {
            t.item = new Menu.Item(Font.upper(t.label));
            t.item.tag = t;
            items.add(t.item);
        }
        menu.set(items, 0);
        // Secondary actions live in the system bar, like everywhere else.
        bar = new IconBar();
        List<IconBar.Icon> icons = new ArrayList<>();
        icons.add(new IconBar.Icon(IconBar.REFRESH, "CHECK AGAIN", AGAIN));
        icons.add(new IconBar.Icon(IconBar.STORE, "PLAY STORE", PLAY));
        bar.set(icons);
    }

    @Override
    protected void onResume() {
        super.onResume();
        check();   // also runs after the install dialog closes, so versions are always fresh
    }

    @Override
    protected void selectIcon(IconBar.Icon icon) {
        Menu.Item proxy = new Menu.Item("");
        proxy.tag = icon.tag;
        select(proxy);
    }

    @Override
    protected void select(Menu.Item item) {
        if (item.tag instanceof Tracked) {
            Tracked t = (Tracked) item.tag;
            if (t.busy) return;
            if (t.hasUpdate() || t.canInstall()) update(t);
            else if (t.latest == null) {                       // couldn't reach its feed: try again
                if (!checking) check();
            }
            else if (t.installed != null) {                    // up to date: OK opens it
                Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(t.pkg);
                if (i == null) i = getPackageManager().getLaunchIntentForPackage(t.pkg);
                if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } else if (PLAY.equals(item.tag)) {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage("com.android.vending");
            if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (AGAIN.equals(item.tag)) {
            check();
        }
    }

    @Override
    protected String hint() {                                 // what OK does on this row, right now
        Menu.Item it = menu.focused();
        if (it == null || !(it.tag instanceof Tracked)) return "";
        Tracked t = (Tracked) it.tag;
        if (t.busy) return "";
        if (t.hasUpdate()) return "OK UPDATES IT";
        if (t.canInstall()) return "OK INSTALLS IT";
        if (t.latest == null) return checking ? "" : "OK CHECKS AGAIN";
        return t.installed != null ? "OK OPENS " + Font.upper(t.label) : "";
    }

    @Override
    protected String carving() {
        Menu.Item it = menu.focused();
        if (it == null || !(it.tag instanceof Tracked)) return "";
        Tracked t = (Tracked) it.tag;
        // The version you have, unless there's a newer one to get (then that one).
        if (t.latest != null && (t.installed == null || t.hasUpdate())) return clean(t.latest.version);
        return t.installed != null ? clean(t.installed.versionName) : "";
    }

    @Override
    protected void draw(Stage s) {
        s.text("UPDATES", 14, 12, 3, Pal.TEXT);
        s.small(meta, 15, 40, 2, Pal.DIM);
    }

    // ---------------------------------------------------------------- checking

    volatile boolean checking;

    void check() {
        checking = true;
        meta = "CHECKING";
        for (Tracked t : apps) {
            t.installed = installed(t.pkg);
            if (!t.busy) note(t, t.installed == null ? "NOT INSTALLED" : "CHECKING", false);
        }
        new Thread(() -> {
            for (Tracked t : apps) {
                try {
                    t.latest = t.source.latest(abi);
                } catch (Exception e) {
                    t.latest = null;
                }
                runOnUiThread(() -> show(t));
            }
            runOnUiThread(this::summarise);
        }).start();
    }

    void note(Tracked t, String text, boolean hot) {
        t.item.note = text;
        t.item.hot = hot;
        view.now();
    }

    void show(Tracked t) {
        if (t.busy) return;
        String have = t.installed == null ? null : clean(t.installed.versionName);
        if (t.installed == null) note(t, t.latest == null ? "NOT INSTALLED" : "NOT INSTALLED · OK TO INSTALL " + clean(t.latest.version), t.latest != null);
        else if (t.latest == null) note(t, have + " · FEED UNREACHABLE", false);
        else if (t.hasUpdate()) note(t, have + " → " + clean(t.latest.version), true);
        else note(t, have + " · UP TO DATE", false);
    }

    void summarise() {
        checking = false;
        int n = 0;
        for (Tracked t : apps) if (t.hasUpdate()) n++;
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        meta = (n == 0 ? "ALL CURRENT" : n + (n == 1 ? " UPDATE" : " UPDATES")) + " · " + time;
        view.now();
    }

    void update(Tracked t) {
        if (t.busy || !(t.hasUpdate() || t.canInstall())) return;
        t.busy = true;
        Release r = t.latest;
        new Thread(() -> {
            File apk = new File(getCacheDir(), t.pkg + ".apk");
            try {
                download(r.url, apk, pct -> runOnUiThread(() -> {
                    t.item.progress = pct / 100f;
                    note(t, "DOWNLOADING " + pct + "%", true);
                }));
                String why = verify(apk, t.pkg);
                if (why != null) throw new IOException(why);
                runOnUiThread(() -> {
                    t.item.progress = -1;
                    note(t, "INSTALLING", true);
                    stage.splash(stage.mote.x, 3f);
                });
                Installer.install(this, apk);
            } catch (Exception e) {
                apk.delete();
                String why = e.getMessage() != null && e.getMessage().startsWith("!") ? e.getMessage().substring(1) : "FAILED";
                runOnUiThread(() -> {
                    t.item.progress = -1;
                    note(t, why + " · OK TO RETRY", true);
                });
            } finally {
                t.busy = false;
            }
        }).start();
    }

    /**
     * The download is what it says it is: the same app, signed by the same people as the one already
     * here, and newer. (Android would refuse a different signer anyway; this says so plainly, and
     * never offers a stranger's APK to the installer at all.) Null when it's fine.
     */
    String verify(File apk, String pkg) {
        PackageManager pm = getPackageManager();
        PackageInfo got = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (got == null) return "!DOWNLOAD DAMAGED";
        if (!pkg.equals(got.packageName)) return "!WRONG APP IN DOWNLOAD";
        if (installed(pkg) == null) return null;           // a first install: nothing to match it against
        try {
            PackageInfo have = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            if (got.getLongVersionCode() < have.getLongVersionCode()) return "!DOWNLOAD IS OLDER";
            if (got.signingInfo == null || have.signingInfo == null) return "!UNSIGNED DOWNLOAD";
            java.util.Set<String> ours = new java.util.HashSet<>();
            for (android.content.pm.Signature sig : have.signingInfo.getApkContentsSigners()) ours.add(sig.toCharsString());
            for (android.content.pm.Signature sig : got.signingInfo.getApkContentsSigners())
                if (!ours.contains(sig.toCharsString())) return "!SIGNED BY SOMEONE ELSE";
        } catch (PackageManager.NameNotFoundException e) {
            return "!NOT INSTALLED";
        }
        return null;
    }

    PackageInfo installed(String pkg) {
        try {
            return getPackageManager().getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- sources

    /** Latest GitHub release; the first asset pattern that matches wins. {abi} is this TV's CPU. */
    static Source github(String repo, String... assetPatterns) {
        return abi -> {
            String json = fetch("https://api.github.com/repos/" + repo + "/releases/latest");
            Release r = new Release();
            Matcher tag = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            if (!tag.find()) throw new IOException("no tag");
            r.version = tag.group(1);
            for (String p : assetPatterns) {
                Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]*/"
                        + p.replace("{abi}", Pattern.quote(abi)) + ")\"").matcher(json);
                if (m.find()) {
                    r.url = m.group(1);
                    return r;
                }
            }
            throw new IOException("no matching asset");
        };
    }

    /** Stremio publishes Android TV builds on its downloads page; filenames carry the versionCode. */
    static Release stremio(String abi) throws IOException {
        String html = fetch("https://www.stremio.com/downloads");
        Matcher m = Pattern.compile("https://dl\\.strem\\.io/android/v([0-9.]+)-androidTV/"
                + "com\\.stremio\\.one-[0-9.]+-([0-9]+)-" + Pattern.quote(abi) + "\\.apk").matcher(html);
        Release best = null;
        while (m.find()) {
            long code = Long.parseLong(m.group(2));
            if (best == null || code > best.code) {
                best = new Release();
                best.version = m.group(1);
                best.code = code;
                best.url = m.group(0);
            }
        }
        if (best == null) throw new IOException("no build for " + abi);
        return best;
    }

    // ---------------------------------------------------------------- http

    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());
        return c;
    }

    static String fetch(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    interface Progress { void on(int pct); }

    static void download(String url, File to, Progress p) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("!NOT A SECURE LINK");
        HttpURLConnection c = open(url);
        long total = c.getContentLengthLong(), got = 0;
        int last = -1;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                got += n;
                int pct = total > 0 ? (int) (got * 100 / total) : 0;
                if (pct != last) p.on(last = pct);
            }
            if (total > 0 && got != total) throw new IOException("!DOWNLOAD CUT SHORT");
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- versions

    /** "32.68s" -> "32.68", "1.10.4-androidTV" -> "1.10.4". */
    static String clean(String v) {
        Matcher m = Pattern.compile("[0-9]+(\\.[0-9]+)*").matcher(v == null ? "" : v);
        return m.find() ? m.group() : "";
    }

    static int compare(String a, String b) {
        String[] x = clean(a).split("\\."), y = clean(b).split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : 0;
            long q = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : 0;
            if (p != q) return Long.compare(p, q);
        }
        return 0;
    }
}
