package com.quiet.drop;

import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;

import com.quiet.px.Font;
import com.quiet.px.Menu;
import com.quiet.px.Pal;
import com.quiet.px.PxActivity;
import com.quiet.px.Qr;
import com.quiet.px.Stage;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Drop — send files and links to the TV from any phone or laptop on the same Wi-Fi.
 * Scan the QR on the stone tablet, or type the address. The server lives only while this
 * screen is open, behind a fresh four-digit code. APKs go straight to the system installer;
 * everything else lands in Downloads/Drop. Links open on the TV immediately.
 */
public class Main extends PxActivity {

    static final int QZ = 3, MOD = 3;   // quiet-zone modules, art pixels per module

    static final int MAX_CONN = 6, STRIKES = 6, ROTATE_AFTER = 24, LINE_MAX = 8192;
    static final long MAX_FILE = 4L << 30;                          // 4 GB, and never past what's free

    ServerSocket server;
    volatile String code;
    String address = "";
    final SecureRandom random = new SecureRandom();
    final Map<String, Integer> strikes = new HashMap<>();           // wrong guesses at the code, by address
    int wrong;                                                     // all wrong guesses this visit
    final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(MAX_CONN);
    boolean[][] qr;
    final List<Menu.Item> received = new ArrayList<>();
    String status = "";
    boolean phone;

    static final class Arrival {
        final String name;
        final Uri uri;
        final File apk;

        Arrival(String name, Uri uri, File apk) {
            this.name = name;
            this.uri = uri;
            this.apk = apk;
        }
    }

    @Override
    protected Menu makeMenu() {
        return new Menu(26, 82, 66);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {   // on a phone: the remote
            startActivity(new Intent(this, Phone.class));
            finish();
            phone = true;
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // One code per visit: stepping out to watch something keeps the phone's page valid.
        code = String.valueOf(1000 + random.nextInt(9000));
        // Anything left in the inbox from an earlier visit (an install that was never confirmed).
        File[] old = new File(getCacheDir(), "inbox").listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (phone) return;
        try {
            server = bind();
            showCode();
            Thread t = new Thread(this::serve, "drop");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            address = "";
            status = "COULDN'T OPEN A PORT";
        }
    }

    void showCode() {
        address = ip() + ":" + server.getLocalPort() + "/" + code;
        qr = Qr.encode("http://" + address);
    }

    /** Too many wrong guesses from around the network: a new code, so guessing starts again from nothing. */
    void rotate() {
        code = String.valueOf(1000 + random.nextInt(9000));
        wrong = 0;
        runOnUiThread(() -> {
            if (server == null) return;
            showCode();
            status = "NEW CODE: SCAN AGAIN";
            view.now();
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
    }

    // ---------------------------------------------------------------- drawing

    int tabletSize() {
        return qr == null ? 0 : (qr.length + QZ * 2) * MOD;
    }

    int tabletX() {
        return 306 - tabletSize();
    }

    static final int TABLET_Y = 20;

    @Override
    protected float[] restingPlace() {
        return new float[]{tabletX() + tabletSize() - 14, TABLET_Y - 6};
    }

    @Override
    protected String carving() {
        Menu.Item it = menu.focused();
        if (it == null) return "";
        String n = ((Arrival) it.tag).name;
        int dot = n.lastIndexOf('.');
        return dot > 0 && n.length() - dot <= 5 ? Font.upper(n.substring(dot + 1)) : "";
    }

    @Override
    protected void lights(Stage s) {
        if (qr == null) return;
        int sz = tabletSize();
        s.light(tabletX() + sz / 2f, TABLET_Y + sz / 2f, sz * 0.95f, 0.42f, false);
    }

    @Override
    protected void draw(Stage s) {
        s.text("DROP", 14, 12, 3, Pal.TEXT);
        s.small(status.isEmpty() ? "SCAN WITH YOUR PHONE, OR TYPE" : status, 15, 40, 2,
                status.isEmpty() ? Pal.DIM : Pal.AMBER);
        s.text(address, 15, 50, 1, Pal.TEXT);
        s.small("RECEIVED", 15, 70, 2, Pal.DIM);
        if (received.isEmpty()) s.small("NOTHING YET", 26, 82, 2, Pal.DIM);

    }

    @Override
    protected void overlay(Stage s) {
        if (qr == null) return;
        int x0 = tabletX(), sz = tabletSize();
        // The tablet: a lit slab with a dark rim and a shadow line beneath.
        s.rect(x0 - 2, TABLET_Y - 2, sz + 4, sz + 4, Pal.COOL[2]);
        s.rect(x0 - 1, TABLET_Y - 1, sz + 2, sz + 2, Pal.COOL[4]);
        s.rect(x0, TABLET_Y, sz, sz, Pal.TEXT);
        s.rect(x0 - 1, TABLET_Y + sz + 2, sz + 2, 1, Pal.VOID);
        for (int y = 0; y < qr.length; y++)
            for (int x = 0; x < qr.length; x++)
                if (qr[y][x]) s.rect(x0 + (QZ + x) * MOD, TABLET_Y + (QZ + y) * MOD, MOD, MOD, Pal.VOID);
        String cap = "CODE " + code;
        s.small(cap, x0 + (sz - Font.microWidth(cap, 2)) / 2, TABLET_Y + sz + 6, 2, Pal.DIM);
    }

    @Override
    protected String hint() {
        Menu.Item it = menu.focused();
        if (it == null) return "";
        return ((Arrival) it.tag).apk != null ? "OK INSTALLS IT" : "OK OPENS IT";
    }

    @Override
    protected void select(Menu.Item item) {
        Arrival a = (Arrival) item.tag;
        try {
            if (a.apk != null) {
                if (a.apk.exists()) Installer.install(this, a.apk);
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(a.uri, mime(a.name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(i, a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            status = "NOTHING HERE OPENS THAT";
        }
    }

    void arrived(Arrival a, long size) {
        String shown = Font.upper(a.name);
        if (shown.length() > 24) shown = shown.substring(0, 22) + "..";
        Menu.Item it = new Menu.Item(shown);
        it.note = Font.upper(Formatter.formatShortFileSize(this, size)) + (a.apk != null ? " · INSTALL" : "");
        it.tag = a;
        received.add(0, it);
        menu.set(received, 0);
        status = a.apk != null ? "INSTALLING " + shown : "SAVED TO DOWNLOADS/DROP";
        stage.drop(60 + stage.rnd.nextInt(200));
        view.poke();
        view.now();
        if (a.apk != null) select(it);
    }

    // ---------------------------------------------------------------- server

    ServerSocket bind() throws IOException {
        for (int p = 8080; p < 8090; p++) {
            try {
                return new ServerSocket(p);
            } catch (IOException ignored) {
            }
        }
        return new ServerSocket(0);
    }

    static String ip() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.getName().startsWith("tun")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "0.0.0.0";
    }

    void serve() {
        ServerSocket s = server;
        while (s != null && !s.isClosed()) {
            try {
                Socket c = s.accept();
                if (!slots.tryAcquire()) {                          // a flood: turn the extras away
                    c.close();
                    continue;
                }
                Thread t = new Thread(() -> {
                    try {
                        handle(c);
                    } finally {
                        slots.release();
                    }
                }, "drop-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    void handle(Socket c) {
        String who = c.getInetAddress().getHostAddress();
        try (Socket sock = c) {
            sock.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 1 << 16);
            OutputStream out = sock.getOutputStream();
            synchronized (strikes) {
                if (strikes.getOrDefault(who, 0) >= STRIKES) return;   // guessed wrong too often: ignored
            }
            String[] request = line(in).split(" ");
            if (request.length < 2) return;
            Map<String, String> headers = new HashMap<>();
            int count = 0;
            for (String h; !(h = line(in)).isEmpty(); ) {
                if (++count > 64) return;
                int i = h.indexOf(':');
                if (i > 0) headers.put(h.substring(0, i).trim().toLowerCase(Locale.ROOT), h.substring(i + 1).trim());
            }
            String method = request[0], target = request[1];
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
            long length;
            try {
                length = Long.parseLong(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException e) {
                length = -1;
            }
            String base = "/" + code;
            boolean ours = path.equals(base) || path.startsWith(base + "/");
            if (!ours && !path.equals("/favicon.ico")) {
                strike(who);
                reply(out, 404, "text/plain", "Not here. Check the code on the TV.".getBytes());
                return;
            }
            if (length < 0) {
                reply(out, 400, "text/plain", "Bad request".getBytes());
                return;
            }

            if (method.equals("GET") && path.equals(base + "/app.apk")) {     // this very app, for a phone
                byte[] apk = java.nio.file.Files.readAllBytes(new File(getApplicationInfo().sourceDir).toPath());
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\n"
                        + "Content-Disposition: attachment; filename=\"QuietTV.apk\"\r\nContent-Length: " + apk.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(apk);
                out.flush();
                return;
            }
            if (method.equals("GET") && (path.equals(base) || path.equals(base + "/"))) {
                reply(out, 200, "text/html; charset=utf-8", Page.html(Build.MODEL, base, pairLink()).getBytes(StandardCharsets.UTF_8));
            } else if (method.equals("PUT") && path.equals(base + "/file")) {
                if (length > Math.min(MAX_FILE, getCacheDir().getUsableSpace() - (256L << 20))) {
                    reply(out, 413, "text/plain", "Too big for the TV's free space".getBytes());
                    return;
                }
                receiveFile(safeName(param(query, "name")), in, length);
                reply(out, 200, "text/plain", "ok".getBytes());
            } else if (method.equals("POST") && path.equals(base + "/link")) {
                String url = new String(readN(in, (int) Math.min(length, 8192)), StandardCharsets.UTF_8).trim();
                if (url.matches("sk-[A-Za-z0-9_-]{20,}")) {        // an OpenAI key: the voice's, kept privately by home
                    boolean saved = saveVoiceKey(url);
                    runOnUiThread(() -> status = saved ? "VOICE KEY SAVED · THE VOICE IS READY" : "COULDN'T SAVE THE KEY");
                    reply(out, saved ? 200 : 500, "text/plain", (saved ? "Saved. Press the remote's voice button and speak."
                            : "Couldn't save it on the TV.").getBytes());
                } else if (smart(url)) reply(out, 200, "text/plain", "ok".getBytes());
                else if (openLink(url)) reply(out, 200, "text/plain", "ok".getBytes());
                else reply(out, 400, "text/plain", "Only web links".getBytes());
            } else {
                reply(out, 404, "text/plain", "Not here. Check the code on the TV.".getBytes());
            }
        } catch (Exception ignored) {
        }
    }

    void strike(String who) {
        boolean again;
        synchronized (strikes) {
            strikes.put(who, strikes.getOrDefault(who, 0) + 1);
            again = ++wrong >= ROTATE_AFTER;
        }
        if (again) rotate();
    }

    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int ch; (ch = in.read()) != -1 && ch != '\n'; ) {
            if (ch != '\r') b.write(ch);
            if (b.size() > LINE_MAX) throw new IOException("line too long");
        }
        return b.toString("UTF-8");
    }

    static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int got = 0;
        for (int r; got < n && (r = in.read(buf, got, n - got)) > 0; ) got += r;
        return buf;
    }

    static void reply(OutputStream out, int status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : status == 400 ? "Bad Request"
                : status == 413 ? "Payload Too Large" : "Not Found") + "\r\n"
                + "Content-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    static String param(String query, String key) throws IOException {
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) return URLDecoder.decode(kv.substring(i + 1), "UTF-8");
        }
        return "";
    }

    static String safeName(String n) {
        n = n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f\\x7f]", "_").trim().replaceFirst("^\\.+", "");
        return n.isEmpty() ? "file" : n.length() > 120 ? n.substring(n.length() - 120) : n;
    }

    void receiveFile(String name, InputStream in, long length) throws IOException {
        boolean apk = name.toLowerCase(Locale.ROOT).endsWith(".apk");
        Uri uri = null;
        File file = null;
        OutputStream out;
        if (apk) {
            File dir = new File(getCacheDir(), "inbox");
            dir.mkdirs();
            file = new File(dir, name);
            out = new FileOutputStream(file);
        } else {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime(name));
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Drop");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("no media slot");
            out = getContentResolver().openOutputStream(uri);
        }
        long got = 0;
        try (OutputStream o = out) {
            byte[] buf = new byte[1 << 16];
            for (int n; got < length && (n = in.read(buf, 0, (int) Math.min(buf.length, length - got))) > 0; ) {
                o.write(buf, 0, n);
                got += n;
            }
        }
        if (got < length) {
            if (file != null) file.delete();
            if (uri != null) getContentResolver().delete(uri, null, null);
            throw new IOException("cut short");
        }
        if (uri != null) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
        }
        Arrival a = new Arrival(name, uri, file);
        runOnUiThread(() -> arrived(a, length));
    }

    /** Anything typed or pasted on the phone page goes to home, which works out what it is (see Voice.shared). */
    boolean smart(String text) {
        try {
            sendBroadcast(new Intent("com.quiet.SHARE").setPackage("com.quiet.home").putExtra("text", text),
                    "com.quiet.permission.PREFS");
            runOnUiThread(() -> status = "ON ITS WAY");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The pairing link for the Quiet app on a phone: this TV's address and home's secret. */
    String pairLink() {
        String ip = address.contains(":") ? address.substring(0, address.indexOf(':')) : address;
        try (android.database.Cursor c = getContentResolver().query(Uri.parse("content://com.quiet.home.prefs/all"), null, null, null, null)) {
            while (c != null && c.moveToNext())
                if ("inbox".equals(c.getString(0))) {
                    String[] v = c.getString(1).split(":", 2);
                    return "quiet://pair?h=" + ip + "&p=" + v[0] + "&t=" + v[1];
                }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** Hands the voice key to home's preferences (Quiet apps only); it's never shown or logged. */
    boolean saveVoiceKey(String key) {
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put("voiceKey", key);
            return getContentResolver().update(Uri.parse("content://com.quiet.home.prefs/all"), v, null, null) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Web links (and Play Store / YouTube ones) only; nothing that could poke at other apps' insides. */
    boolean openLink(String url) {
        if (url.isEmpty() || url.matches("(?s).*\\s.*")) return false;
        if (!url.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) url = "https://" + url;
        if (!url.matches("(?i)^(https?://|market://|vnd\\.youtube:).+")) return false;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        runOnUiThread(() -> {
            try {
                status = "OPENING LINK";
                startActivity(i);
            } catch (Exception e) {
                status = "NO APP HERE OPENS THAT LINK";
            }
            view.now();
        });
        return true;
    }

    static String mime(String name) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(name.replace(' ', '_')).toLowerCase(Locale.ROOT);
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m == null ? "application/octet-stream" : m;
    }
}
