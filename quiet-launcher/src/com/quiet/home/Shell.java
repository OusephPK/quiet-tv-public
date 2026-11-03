package com.quiet.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * The TV's own developer connection (ADB over the network, port 5555: what the Mac uses to install),
 * opened from inside the TV, to 127.0.0.1 only. Android lets no ordinary app press keys or type in
 * another app; this is the one door that can, so the voice can type into boxes that apps like YouTube
 * and Netflix hide from it. The first time, the TV asks once ("Allow USB debugging?"); our key lives
 * only in home's private prefs. Plain ADB protocol, written by hand: CNXN, AUTH, OPEN "shell:", WRTE.
 */
final class Shell {

    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f, CLSE = 0x45534c43,
            WRTE = 0x45545257;
    static final int PORT = 5555;
    static final byte[] SHA1_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    final Context context;
    Socket socket;
    DataInputStream in;
    OutputStream out;

    Shell(Context c) {
        context = c;
    }

    /**
     * Runs one shell command on the TV and returns what it printed. asking runs if the TV puts up its
     * "Allow?" question (first time only); then this waits up to a minute for the answer.
     */
    static synchronized String run(Context c, String command, Runnable asking) throws Exception {
        Shell s = new Shell(c);
        try {
            s.connect(asking);
            return s.exec(command);
        } finally {
            s.close();
        }
    }

    void connect(Runnable asking) throws Exception {
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", PORT), 2000);
        socket.setSoTimeout(8000);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        send(CNXN, 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.UTF_8));
        KeyPair keys = keys(context);
        boolean signed = false;
        while (true) {
            int[] h = new int[6];
            byte[] data = read(h);
            if (h[0] == CNXN) return;                        // in
            if (h[0] != AUTH || h[1] != 1) throw new IOException("unexpected " + Integer.toHexString(h[0]));
            if (!signed) {                                   // the TV's question: our signature
                Signature sig = Signature.getInstance("NONEwithRSA");
                sig.initSign(keys.getPrivate());
                sig.update(SHA1_INFO);
                sig.update(data);
                send(AUTH, 2, 0, sig.sign());
                signed = true;
            } else {                                         // it doesn't know us yet: our key, and it asks you
                send(AUTH, 3, 0, publicKey((RSAPublicKey) keys.getPublic()));
                if (asking != null) asking.run();
                socket.setSoTimeout(60_000);
            }
        }
    }

    String exec(String command) throws IOException {
        // Each `input` starts a small Java program on the TV (~0.5 s here), so a route of keys takes a while.
        socket.setSoTimeout(45_000);
        send(OPEN, 1, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        StringBuilder got = new StringBuilder();
        int remote = 0;
        while (true) {
            int[] h = new int[6];
            byte[] data = read(h);
            if (h[0] == OKAY) remote = h[1];
            else if (h[0] == WRTE) {
                got.append(new String(data, StandardCharsets.UTF_8));
                send(OKAY, 1, h[1], new byte[0]);
            } else if (h[0] == CLSE) {
                send(CLSE, 1, h[1] != 0 ? h[1] : remote, new byte[0]);
                return got.toString();
            }
        }
    }

    void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xFF;
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    byte[] read(int[] h) throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 6; i++) h[i] = b.getInt();
        if (h[3] < 0 || h[3] > 1 << 20) throw new IOException("bad length");
        byte[] data = new byte[h[3]];
        in.readFully(data);
        return data;
    }

    /** Ours, made once and kept in home's private prefs (never leaves the TV). */
    static KeyPair keys(Context c) throws Exception {
        SharedPreferences p = Prefs.prefs(c);
        String pri = p.getString("shellKey", ""), pub = p.getString("shellPub", "");
        KeyFactory f = KeyFactory.getInstance("RSA");
        if (!pri.isEmpty() && !pub.isEmpty()) {
            PrivateKey k = f.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(pri, Base64.NO_WRAP)));
            return new KeyPair(f.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))), k);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        p.edit().putString("shellKey", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                .putString("shellPub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP)).apply();
        Log.i(Voice.TAG, "shell: made our key");
        return kp;
    }

    /** The public key as ADB wants it: Android's RSAPublicKey struct (little-endian words), base64, a name. */
    static byte[] publicKey(RSAPublicKey k) {
        BigInteger n = k.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv.intValue());
        for (int i = 0; i < 64; i++) b.putInt(n.shiftRight(32 * i).intValue());
        for (int i = 0; i < 64; i++) b.putInt(rr.shiftRight(32 * i).intValue());
        b.putInt(k.getPublicExponent().intValue());
        return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " quiet-voice@tv\0").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The shell commands that type these words. slow: a couple of letters per command, for apps that drop keys sent
     * in a burst (Netflix lost the "n" of "dune", 3 Nov; two at a time never lost one). Each command takes ~1.2 s here.
     */
    static String typing(String words, boolean slow) {
        String w = typed(words);
        if (w.isEmpty()) return "";
        if (!slow) return "input text '" + w + "'";
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; i < w.length(); i++) {
            if (w.startsWith("%s", i)) {
                keys.add("%s");
                i++;
            } else keys.add(String.valueOf(w.charAt(i)));
        }
        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < keys.size(); i += 2)
            cmd.append(cmd.length() > 0 ? "; " : "").append("input text '").append(keys.get(i)).append(i + 1 < keys.size() ? keys.get(i + 1) : "").append("'");
        return cmd.toString();
    }

    /** The delete key n times (60 at most); slow: two to a command, as typing(). */
    static String deleting(int n, boolean slow) {
        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < Math.min(n, 60); i++) {
            if (i == 0) cmd.append("input keyevent");
            else if (slow && i % 2 == 0) cmd.append("; input keyevent");
            cmd.append(" 67");
        }
        return cmd.toString();
    }

    /** Words as `input text` takes them: plain letters, digits and a few marks; spaces as %s. */
    static String typed(String words) {
        String plain = java.text.Normalizer.normalize(words, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replaceAll("[^A-Za-z0-9 .,&:!?-]", "").replaceAll("\\s+", " ").trim();
        return plain.replace(" ", "%s");
    }
}
