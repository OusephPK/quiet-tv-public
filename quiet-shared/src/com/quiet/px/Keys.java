package com.quiet.px;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Keys: presses the TV's buttons through Google's own remote service on it (Android TV Remote,
 * protocol v2: port 6467 to pair, 6466 for keys), the way Google's phone remote does, so arrows,
 * OK and BACK work in every app, YouTube included. Paired once: the TV shows a 6-character code
 * that is typed on the phone. Plain Java (no Android), so it runs on the Mac for testing too.
 *
 * Messages are protobufs, each sent after its length (a varint); the few fields used are written
 * and read by hand below. The identity (an RSA key and its self-signed certificate) is made here
 * once and kept by the caller: it's what the TV remembers as paired.
 */
public final class Keys {

    public static final int HOME = 3, BACK = 4, UP = 19, DOWN = 20, LEFT = 21, RIGHT = 22, OK = 23, VOLUME_UP = 24,
            VOLUME_DOWN = 25, ENTER = 66, DEL = 67, PLAY_PAUSE = 85, NEXT = 87, PREVIOUS = 88, REWIND = 89, FORWARD = 90, MUTE = 164;

    /** What this remote says it can do: ping, keys, power, volume, app links. */
    static final int FEATURES = 622;

    /** Where messages are told (tests, the log); null: nowhere. */
    public static java.util.function.Consumer<String> log;

    static void tell(String s) {
        if (log != null) log.accept(s);
    }

    static String show(List<Object[]> m) {
        StringBuilder b = new StringBuilder("{");
        for (Object[] f : m) {
            b.append(f[0]).append(':');
            if (f[1] instanceof byte[]) {
                try {
                    b.append(show(Pb.parse((byte[]) f[1])));
                } catch (IOException e) {
                    b.append('"').append(new String((byte[]) f[1], StandardCharsets.UTF_8)).append('"');
                }
            } else b.append(f[1]);
            b.append(' ');
        }
        return b.append('}').toString();
    }

    final String host;
    final PrivateKey key;
    final X509Certificate cert;

    /** For the TV at host, as the identity saved earlier (see identity()). */
    public Keys(String host, String identity) throws GeneralSecurityException {
        this.host = host;
        String[] parts = identity.split("\\|");
        key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(parts[0])));
        cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(parts[1])));
    }

    /** A new identity, to be kept: "key|certificate" (base64). */
    public static String identity(String name) throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048, new SecureRandom());
        KeyPair kp = g.generateKeyPair();
        return Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()) + "|"
                + Base64.getEncoder().encodeToString(selfSigned(kp, name));
    }

    // ---------------------------------------------------------------- pairing (once)

    /** Pairing under way: the TV is showing its code. finish() it with what was typed, or close() it. */
    public final class Pairing implements Closeable {
        final Line line;

        Pairing(Line line) {
            this.line = line;
        }

        /** The code from the TV (6 characters, 0-9 and A-F). Throws if it's wrong; then pair again. */
        public void finish(String code) throws IOException {
            try {
                code = code.trim().toUpperCase(Locale.ROOT);
                if (!code.matches("[0-9A-F]{6}")) throw new IOException("THAT'S NOT THE CODE");
                RSAPublicKey server = (RSAPublicKey) line.socket.getSession().getPeerCertificates()[0].getPublicKey();
                MessageDigest h = MessageDigest.getInstance("SHA-256");
                RSAPublicKey mine = (RSAPublicKey) cert.getPublicKey();
                h.update(unsigned(mine.getModulus()));
                h.update(unsigned(mine.getPublicExponent()));
                h.update(unsigned(server.getModulus()));
                h.update(unsigned(server.getPublicExponent()));
                h.update(hex(code.substring(2)));                 // the last 4 characters; the first 2 check it
                byte[] secret = h.digest();
                if ((secret[0] & 0xFF) != Integer.parseInt(code.substring(0, 2), 16)) throw new IOException("THAT'S NOT THE CODE");
                line.send(outer(40, pb().bytes(1, secret)));
                line.expect(41);
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            line.close();
        }
    }

    /** Asks the TV to pair: it shows a code, then finish() the Pairing with it. */
    public Pairing pair(String client) throws IOException {
        Line l = new Line(6467, 20_000);
        try {
            l.send(outer(10, pb().str(1, "atvremote").str(2, client)));
            l.expect(11);
            Pb hexSix = pb().num(1, 3).num(2, 6);                  // hexadecimal, 6 symbols; we type it in
            l.send(outer(20, pb().msg(1, hexSix).num(3, 1)));
            l.expect(20);
            l.send(outer(30, pb().msg(1, hexSix).num(2, 1)));
            l.expect(31);
            l.socket.setSoTimeout(120_000);                        // as long as it takes to read and type it
            return new Pairing(l);
        } catch (IOException e) {
            l.close();
            throw e;
        }
    }

    /** OuterMessage: protocol 2, status OK, and one field. */
    static Pb outer(int field, Pb inner) {
        return pb().num(1, 2).num(2, 200).msg(field, inner);
    }

    // ---------------------------------------------------------------- keys

    /** The open line for keys: answers the TV's pings by itself; press() as often as you like. */
    public final class Remote implements Closeable {
        final Line line;
        final String name;
        volatile long readyAt;                                     // when the TV took us on

        Remote(Line line, String name) {
            this.line = line;
            this.name = name;
            Thread t = new Thread(this::listen, "keys");
            t.setDaemon(true);
            t.start();
        }

        void listen() {
            try {
                while (true) {
                    List<Object[]> m = Pb.parse(line.read());
                    tell("tv: " + show(m));
                    if (!Pb.has(m, 8)) heard(show(m));
                    if (Pb.has(m, 1)) {                            // the TV's configure: ours back
                        line.send(pb().msg(1, pb().num(1, FEATURES).msg(2, pb().str(1, name).str(2, "Quiet")
                                .num(3, 1).str(4, "1").str(5, "com.quiet").str(6, "1.0.0"))));
                    } else if (Pb.has(m, 2)) {                     // set active
                        line.send(pb().msg(2, pb().num(1, FEATURES)));
                        if (readyAt == 0) readyAt = System.currentTimeMillis();
                    } else if (Pb.has(m, 8)) {                     // ping: pong, with its number
                        List<Object[]> ping = Pb.parse(Pb.bytes(m, 8));
                        line.send(pb().msg(9, pb().num(1, Pb.num(ping, 1))));
                    } else if (Pb.has(m, 20) || Pb.has(m, 22)) {   // typing moved: an app took input / a box showed
                        List<Object[]> f = Pb.parse(Pb.bytes(m, Pb.has(m, 20) ? 20 : 22));
                        if (Pb.has(f, 2)) {
                            List<Object[]> status = Pb.parse(Pb.bytes(f, 2));
                            if (Pb.has(status, 1)) imeCounter = (int) Pb.num(status, 1);
                        }
                        boxAt = System.currentTimeMillis();
                    } else if (Pb.has(m, 21)) {                    // the box's counters, as the TV keeps them
                        List<Object[]> e = Pb.parse(Pb.bytes(m, 21));
                        if (Pb.has(e, 1)) imeCounter = (int) Pb.num(e, 1);
                        if (Pb.has(e, 2)) fieldCounter = (int) Pb.num(e, 2);
                        boxAt = System.currentTimeMillis();
                    }
                }
            } catch (IOException e) {
                close();
            }
        }

        /**
         * One press of a button (KEYCODE_*, e.g. Keys.OK). Off the UI thread: the first one may wait for
         * the line to be ready.
         */
        public void press(int keyCode) throws IOException {
            try {
                if (!waitReady(4000)) throw new IOException("THE TV'S REMOTE ISN'T ANSWERING");
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            line.send(pb().msg(10, pb().num(1, keyCode).num(2, 3)));
        }

        // The TV's open text box (its counters say which box and which edit, as the TV keeps them).
        volatile int imeCounter, fieldCounter;
        volatile long boxAt;
        final java.util.ArrayDeque<String> lately = new java.util.ArrayDeque<>();
        final long born = System.currentTimeMillis();

        /** The TV has told this line about typing (an app took input, a box showed, its counters). */
        public boolean sawBox() {
            return boxAt != 0;
        }

        synchronized void heard(String m) {
            lately.add((System.currentTimeMillis() - born) / 100 / 10f + "s " + (m.length() > 300 ? m.substring(0, 300) : m));
            while (lately.size() > 12) lately.removeFirst();
        }

        /** What the TV said on this line lately (not pings), and the typing counters: for finding out what works. */
        public synchronized String lately() {
            return "line " + (System.currentTimeMillis() - born) / 1000 + " s old, counters " + imeCounter + "/" + fieldCounter
                    + ", the TV said: " + String.join(" | ", lately);
        }

        /**
         * Puts this text into the TV's open text box, replacing what's there: Google's own way of typing
         * from a phone, so it works in any app whose box uses the TV's keyboard (whether or not the app
         * lets anything read it). Then ENTER (the keyboard's search) if asked.
         */
        public void type(String text, boolean enter) throws IOException {
            if (text.isEmpty()) return;                            // an empty box is ignored (YouTube, 2 Nov)
            try {
                if (!waitReady(4000)) throw new IOException("THE TV'S REMOTE ISN'T ANSWERING");
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            int at = text.length() - 1;                            // as Google's remote sends it (-1: empty)
            line.send(pb().msg(21, pb().num(1, imeCounter).num(2, fieldCounter)
                    .msg(3, pb().num(1, 1).msg(2, pb().num(1, at).num(2, at).str(3, text)))));
            if (!enter || text.isEmpty()) return;
            try {
                Thread.sleep(500);                                 // the box takes the words first
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            press(ENTER);
        }

        /** Opens a link on the TV in its app (a YouTube address plays it there). */
        public void open(String link) throws IOException {
            line.send(pb().msg(90, pb().str(1, link)));
        }

        public boolean alive() {
            return !line.socket.isClosed();
        }

        /**
         * Waits (up to ms) for the TV to set up the line; true once it takes keys. The TV makes a virtual
         * remote for us when we arrive, and keys sent in its first moments are lost (tested: ~1 s is safe).
         */
        public boolean waitReady(long ms) throws InterruptedException {
            long until = System.currentTimeMillis() + ms;
            while (readyAt == 0 && alive() && System.currentTimeMillis() < until) Thread.sleep(50);
            if (readyAt == 0) return false;
            long settle = readyAt + 1000 - System.currentTimeMillis();
            if (settle > 0) Thread.sleep(settle);
            return alive();
        }

        @Override
        public void close() {
            line.close();
        }
    }

    /**
     * Connects for keys, as name (what the TV lists it as). Fails at once (the handshake is refused) if
     * this identity isn't paired with the TV.
     */
    public Remote connect(String name) throws IOException {
        return new Remote(new Line(6466, 0), name);
    }

    // ---------------------------------------------------------------- the line: TLS, length-prefixed messages

    final class Line implements Closeable {
        final SSLSocket socket;
        final InputStream in;
        final OutputStream out;

        Line(int port, int timeout) throws IOException {
            try {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(null, null);
                char[] pw = "quiet".toCharArray();
                ks.setKeyEntry("me", key, pw, new Certificate[]{cert});
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, pw);
                SSLContext c = SSLContext.getInstance("TLS");
                // The TV's certificate is its own (self-signed): the pairing code binds both keys instead.
                c.init(kmf.getKeyManagers(), new TrustManager[]{new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String type) {}
                    @Override public void checkServerTrusted(X509Certificate[] chain, String type) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, new SecureRandom());
                socket = (SSLSocket) c.getSocketFactory().createSocket();
                socket.connect(new InetSocketAddress(host, port), 4000);
                socket.setSoTimeout(timeout);
                socket.setTcpNoDelay(true);
                socket.startHandshake();
                in = socket.getInputStream();
                out = socket.getOutputStream();
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }

        synchronized void send(Pb m) throws IOException {
            byte[] b = m.toBytes();
            if (log != null) tell("us: " + show(Pb.parse(b)));
            ByteArrayOutputStream o = new ByteArrayOutputStream(b.length + 5);
            Pb.varint(o, b.length);
            o.write(b, 0, b.length);
            out.write(o.toByteArray());
            out.flush();
        }

        byte[] read() throws IOException {
            long n = 0;
            for (int shift = 0; ; shift += 7) {
                int c = in.read();
                if (c < 0) throw new EOFException();
                n |= (long) (c & 0x7F) << shift;
                if ((c & 0x80) == 0) break;
            }
            byte[] b = new byte[(int) n];
            for (int got = 0; got < n; ) {
                int r = in.read(b, got, (int) n - got);
                if (r < 0) throw new EOFException();
                got += r;
            }
            return b;
        }

        /** The next pairing message must be OK and carry this field. */
        void expect(int field) throws IOException {
            List<Object[]> m = Pb.parse(read());
            long status = Pb.num(m, 2);
            if (status != 200) throw new IOException(status == 402 ? "THAT'S NOT THE CODE" : "THE TV SAID NO (" + status + ")");
            if (!Pb.has(m, field)) throw new IOException("UNEXPECTED REPLY");
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- protobuf, by hand

    static Pb pb() {
        return new Pb();
    }

    /** A protobuf message being written: fields in order. parse() reads one into [field, value] pairs. */
    static final class Pb {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();

        Pb num(int field, long v) {
            varint(b, (long) field << 3);
            varint(b, v);
            return this;
        }

        Pb bytes(int field, byte[] v) {
            varint(b, (long) field << 3 | 2);
            varint(b, v.length);
            b.write(v, 0, v.length);
            return this;
        }

        Pb str(int field, String v) {
            return bytes(field, v.getBytes(StandardCharsets.UTF_8));
        }

        Pb msg(int field, Pb v) {
            return bytes(field, v.toBytes());
        }

        byte[] toBytes() {
            return b.toByteArray();
        }

        static void varint(ByteArrayOutputStream o, long v) {
            while ((v & ~0x7FL) != 0) {
                o.write((int) (v & 0x7F) | 0x80);
                v >>>= 7;
            }
            o.write((int) v);
        }

        static List<Object[]> parse(byte[] d) throws IOException {
            List<Object[]> out = new ArrayList<>();
            int[] p = {0};
            while (p[0] < d.length) {
                long tag = readVarint(d, p);
                int field = (int) (tag >>> 3), wire = (int) (tag & 7);
                if (wire == 0) out.add(new Object[]{field, readVarint(d, p)});
                else if (wire == 2) {
                    int n = (int) readVarint(d, p);
                    if (n < 0 || p[0] + n > d.length) throw new IOException("BAD MESSAGE");
                    byte[] v = new byte[n];
                    System.arraycopy(d, p[0], v, 0, n);
                    p[0] += n;
                    out.add(new Object[]{field, v});
                } else if (wire == 5) p[0] += 4;
                else if (wire == 1) p[0] += 8;
                else throw new IOException("BAD MESSAGE");
            }
            return out;
        }

        static long readVarint(byte[] d, int[] p) throws IOException {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (p[0] >= d.length) throw new IOException("BAD MESSAGE");
                int c = d[p[0]++] & 0xFF;
                v |= (long) (c & 0x7F) << shift;
                if ((c & 0x80) == 0) return v;
            }
            throw new IOException("BAD MESSAGE");
        }

        static boolean has(List<Object[]> m, int field) {
            for (Object[] f : m) if ((int) f[0] == field) return true;
            return false;
        }

        static long num(List<Object[]> m, int field) {
            for (Object[] f : m) if ((int) f[0] == field && f[1] instanceof Long) return (Long) f[1];
            return 0;
        }

        static byte[] bytes(List<Object[]> m, int field) {
            for (Object[] f : m) if ((int) f[0] == field && f[1] instanceof byte[]) return (byte[]) f[1];
            return new byte[0];
        }
    }

    // ---------------------------------------------------------------- the certificate, by hand (DER)

    /** A minimal self-signed X.509 v3 certificate (CN=name, 20 years, SHA256withRSA). */
    static byte[] selfSigned(KeyPair kp, String name) throws GeneralSecurityException {
        byte[] algo = seq(oid(1, 2, 840, 113549, 1, 1, 11), new byte[]{5, 0});
        byte[] who = seq(set(seq(oid(2, 5, 4, 3), tlv(0x0C, name.getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] tbs = seq(tlv(0xA0, tlv(0x02, new byte[]{2})), tlv(0x02, BigInteger.valueOf(now).toByteArray()), algo, who,
                seq(time(now - 86_400_000L), time(now + 20L * 365 * 86_400_000L)), who, kp.getPublic().getEncoded());
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign(), bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        return seq(tbs, algo, tlv(0x03, bits));
    }

    static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        int n = body.size();
        ByteArrayOutputStream o = new ByteArrayOutputStream(n + 4);
        o.write(tag);
        if (n < 128) o.write(n);
        else if (n < 256) {
            o.write(0x81);
            o.write(n);
        } else {
            o.write(0x82);
            o.write(n >> 8);
            o.write(n & 0xFF);
        }
        o.write(body.toByteArray(), 0, n);
        return o.toByteArray();
    }

    static byte[] seq(byte[]... p) {
        return tlv(0x30, p);
    }

    static byte[] set(byte[]... p) {
        return tlv(0x31, p);
    }

    static byte[] oid(int... a) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(40 * a[0] + a[1]);
        for (int i = 2; i < a.length; i++) {
            int v = a[i], n = 0;
            byte[] tmp = new byte[5];
            do {
                tmp[n++] = (byte) (v & 0x7F);
                v >>>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) o.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }

    static byte[] time(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(new Date(t)).getBytes(StandardCharsets.US_ASCII));
    }

    /** A number's bytes without the sign byte Java adds (what the pairing secret hashes). */
    static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] t = new byte[b.length - 1];
            System.arraycopy(b, 1, t, 0, t.length);
            return t;
        }
        return b;
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
