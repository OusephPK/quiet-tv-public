package com.quiet.home;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The TV's door for your phone, always open while the TV is on (it lives with the voice, which
 * Android keeps running). The Quiet app on your phone (Drop, installed from the TV) finds it on
 * the Wi-Fi by name and knocks with the pairing secret it was given:
 *
 *   POST /share   text (a link, a title, anything)     → understood and done (see Voice.shared)
 *   POST /voice   a WAV of you talking into your phone  → the voice, with the phone's microphone
 *   POST /hear    a WAV, from the phone's SPEAK TO TYPE  → typed into the box on screen: by the TV if it
 *                 can read the box ("DONE\n<words>"), else by the phone through Google's remote service:
 *                 "TYPE\n<words>" (the TV's keyboard is up: then its search key) or "GRID\n<words>" (an app's
 *                 own letter grid, as YouTube and Netflix have)
 *   POST /note    what the phone saw while typing (the remote line's messages): into the log and diary
 *
 * With "X-Can-Type: 1" (the phone is also the TV's remote), words meant for a box only the phone can
 * type into (the TV's keyboard is up for a box nothing can read, or an app that hides its screen, like
 * YouTube or Netflix, and they're just saying words to look up) come back as "TYPE\n<words>" or
 * "GRID\n<words>", and the phone types them in through Google's remote service.
 *   GET  /hello   "Quiet TV" (no secret; for finding the TV)
 *
 * Phones say which version of these answers they read ("X-Quiet-Phone: 2"); older ones get the old form.
 *
 * The secret lives in home's private prefs; Drop shows it to a phone only on its own page, which
 * itself needs the code on the TV screen.
 */
final class Inbox implements Runnable {

    static final int PORT = 8642;
    static final String TYPE = "_quiettv._tcp.";

    final Voice voice;
    ServerSocket server;
    NsdManager.RegistrationListener announced;
    volatile boolean running;
    final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(6);

    Inbox(Voice v) {
        voice = v;
    }

    static String token(Context c) {
        android.content.SharedPreferences p = Prefs.prefs(c);
        String t = p.getString("inboxToken", "");
        if (t.isEmpty()) {
            byte[] b = new byte[18];
            new SecureRandom().nextBytes(b);
            t = Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            p.edit().putString("inboxToken", t).apply();
        }
        return t;
    }

    void start() {
        if (running) return;
        running = true;
        token(voice);
        Thread t = new Thread(this, "inbox");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        try {
            if (announced != null) voice.getSystemService(NsdManager.class).unregisterService(announced);
        } catch (Exception ignored) {
        }
    }

    void announce() {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("Quiet TV");
        info.setServiceType(TYPE);
        info.setPort(PORT);
        announced = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo s) { Log.i(Voice.TAG, "inbox: on the Wi-Fi as " + s.getServiceName()); }
            @Override public void onRegistrationFailed(NsdServiceInfo s, int e) { Log.w(Voice.TAG, "inbox: couldn't announce (" + e + ")"); }
            @Override public void onServiceUnregistered(NsdServiceInfo s) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo s, int e) {}
        };
        try {
            voice.getSystemService(NsdManager.class).registerService(info, NsdManager.PROTOCOL_DNS_SD, announced);
        } catch (Exception e) {
            Log.w(Voice.TAG, "inbox: no announcing: " + e);
        }
    }

    @Override
    public void run() {
        try {
            server = new ServerSocket(PORT);
            Log.i(Voice.TAG, "inbox: open on port " + PORT);
            announce();
            while (running) {
                Socket s = server.accept();
                s.setSoTimeout(15_000);
                if (!slots.tryAcquire()) {                      // six at once is plenty for one phone
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                    continue;
                }
                Thread t = new Thread(() -> {
                    try {
                        handle(s);
                    } finally {
                        slots.release();
                    }
                }, "inbox-req");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (running) Log.w(Voice.TAG, "inbox: closed: " + e);
        }
        running = false;
    }

    void handle(Socket s) {
        try (Socket sock = s; InputStream in = new BufferedInputStream(sock.getInputStream()); OutputStream out = sock.getOutputStream()) {
            String line = readLine(in);
            if (line == null) return;
            String[] req = line.split(" ");
            if (req.length < 2) return;
            Map<String, String> h = new HashMap<>();
            for (String l; (l = readLine(in)) != null && !l.isEmpty(); ) {
                int c = l.indexOf(':');
                if (c > 0) h.put(l.substring(0, c).trim().toLowerCase(Locale.ROOT), l.substring(c + 1).trim());
            }
            String method = req[0], path = req[1];
            if (method.equals("GET") && path.equals("/hello")) {
                reply(out, 200, "Quiet TV");
                return;
            }
            String want = token(voice), got = h.getOrDefault("x-quiet-token", "");
            if (!MessageDigest.isEqual(want.getBytes(StandardCharsets.UTF_8), got.getBytes(StandardCharsets.UTF_8))) {
                reply(out, 403, "Not paired");
                return;
            }
            int phone;
            try {
                phone = Integer.parseInt(h.getOrDefault("x-quiet-phone", "1"));
            } catch (NumberFormatException e) {
                phone = 1;
            }
            int len;
            try {
                len = Integer.parseInt(h.getOrDefault("content-length", "0"));
            } catch (NumberFormatException e) {
                len = -1;
            }
            int max = path.equals("/voice") || path.equals("/hear") ? 4_000_000 : 64_000;
            if (len < 0 || len > max) {
                reply(out, 413, "Too big");
                return;
            }
            byte[] body = new byte[len];
            for (int off = 0; off < len; ) {
                int n = in.read(body, off, len - off);
                if (n < 0) break;
                off += n;
            }
            boolean canType = "1".equals(h.get("x-can-type"));
            if (method.equals("POST") && path.equals("/share")) {
                String text = new String(body, StandardCharsets.UTF_8).trim();
                if (canType && !Share.URL_IN.matcher(text).find()) {    // typed on the phone: a box only it can reach?
                    boolean keyboard = voice.hiddenBox();
                    if (keyboard || voice.hidesItself()) {
                        reply(out, 200, compat(voice.forPhone(text, keyboard), phone));
                        return;
                    }
                }
                String subject = java.net.URLDecoder.decode(h.getOrDefault("x-subject", ""), "UTF-8");
                voice.ui.post(() -> voice.shared(text, subject));
                reply(out, 200, "On the TV");
            } else if (method.equals("POST") && path.equals("/hear")) {
                String text = voice.transcribe(body);             // just the words; the key stays on the TV
                if (text == null) reply(out, 200, "DIDN'T CATCH THAT · TAP AND TRY AGAIN");
                else reply(out, 200, compat(voice.dictated(text), phone));
            } else if (method.equals("POST") && path.equals("/note")) {
                String note = new String(body, StandardCharsets.UTF_8);
                Log.i(Voice.TAG, "phone: " + note);
                voice.diary("phone: " + (note.length() > 600 ? note.substring(0, 600) : note));
                reply(out, 200, "ok");
            } else if (method.equals("POST") && path.equals("/voice") && canType) {
                reply(out, 200, compat(voice.fromPhone(body), phone));   // decided first: it may be words for a box
            } else if (method.equals("POST") && path.equals("/voice")) {
                voice.ui.post(() -> voice.spoken(body));
                reply(out, 200, "Listening");
            } else reply(out, 404, "Not here");
        } catch (Exception e) {
            Log.w(Voice.TAG, "inbox: " + e);
        }
    }

    /** A phone app from before 2 Nov's typing changes reads "TYPE|GRID\n<words>" only: no letter count after. */
    static String compat(String reply, int phone) {
        if (phone >= 2 || !(reply.startsWith("TYPE\n") || reply.startsWith("GRID\n"))) return reply;
        String[] p = reply.split("\n", -1);
        return p[0] + "\n" + (p.length > 1 ? p[1] : "");
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) return null;
        }
        return b.size() == 0 && in.available() < 0 ? null : b.toString("UTF-8");
    }

    static void reply(OutputStream out, int code, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }
}
