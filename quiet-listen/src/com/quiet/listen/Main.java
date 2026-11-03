package com.quiet.listen;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.quiet.px.Font;
import com.quiet.px.Groove;
import com.quiet.px.Pal;
import com.quiet.px.PxView;
import com.quiet.px.Stage;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Music — Apple Music, drawn as pixel art in the well.
 *
 * Apple's own web player (music.apple.com) runs hidden underneath: it signs you in, streams,
 * handles the DRM and answers Apple Music API calls. Everything you see is ours: a rail of
 * sections, covers turned into pixel art, album pages, search with a pixel keyboard, and a
 * Now Playing scene. Apple's page only appears for signing in. Music keeps playing when you
 * leave, and the media session lets the home screen show what's on.
 */
public class Main extends Activity implements PxView.Scene {

    static final String START = "https://music.apple.com/";
    static final long NOW_PLAYING_AFTER_MS = 25_000;
    static final int BROWSE = 0, NOW = 1, WEB = 2;

    final Handler ui = new Handler();
    FrameLayout root;
    WebView web, popup;
    PxView px;
    Stage stage;
    MediaSession session;
    Api api;
    final Covers covers = new Covers();
    Browse browse;
    int mode = BROWSE;

    String title = "", artist = "", album = "", artUrl = "", storefront = "us", notice = "";
    boolean playing, serviceUp, drmKnown, drm, authorized, stateKnown;
    float pos, dur;
    boolean live;                                       // a live radio station: no length, no skipping
    String genres = "";                                 // Apple's genres for the song, "Hip-Hop/Rap|Music" (the party reads them)
    // The sleep timer: off, end of this song, or 15/30/60 minutes; the last minute fades the music
    // and the world out together (home and the screensaver read windDown from the media session).
    static final int[] SLEEP_MIN = {0, -1, 15, 30, 60};
    int sleep;
    long sleepEnd, dimUntil;
    String sleepSong = "";
    float windDown;
    boolean loved;                                      // the song playing has your ♥
    long lastInput = SystemClock.uptimeMillis(), pausedAt, noticeUntil;
    Bitmap art;                      // the cover, fine (see Fine)
    String carved = "";
    long posAt;                      // when pos was last reported, to run the clock smoothly between reports
    boolean wantNow;
    boolean nowFromOutside;          // Now Playing was opened from home or the notification: BACK goes back there
    boolean queued;                  // something has been asked to play this session
    // Now Playing has three views: 0 the song (cover, details, lyrics), 1 just the lyrics, 2 up next.
    int npView;
    java.util.List<String[]> upNext = new java.util.ArrayList<>();
    // OK on Now Playing: a tap plays or pauses, a hold loves the song (or takes the ♥ back).
    final com.quiet.px.Hold hold = new com.quiet.px.Hold(new com.quiet.px.Hold.Target() {
        @Override
        public void tap() {
            control("toggle");
            px.now();
        }

        @Override
        public void hold() {
            if (mode != NOW) return;
            if (live) notice("♥ IS FOR SONGS, NOT STATIONS");
            else {
                boolean on = !loved;
                if (on) stage.mote.cheer();
                web.evaluateJavascript("window.__quietLove&&__quietLove(" + on + ")", null);
                notice(on ? "♥" : "...");
            }
            px.now();
        }
    });
    Groove groove;

    /** One sung line: when it starts and ends, and its words with their own timings. */
    static final class Line {
        float b, e;
        String[] words;
        float[] wb, we;
        String text;
    }
    java.util.List<Line> lyrics;      // null while loading or when the song has none
    String lyricsSong = "";

    // Typing into Apple's sign-in page. Its fields live in a frame that won't summon the TV
    // keyboard, so we type here and send the text to the field as real keystrokes.
    LinearLayout typePanel;
    EditText typer;
    TextView typeTitle, typeError;

    // ---------------------------------------------------------------- setup

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        com.quiet.px.Oops.install(this);
        // Remote debugging only on the emulator, for testing; never on a real TV.
        if (Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || devDebug())
            WebView.setWebContentsDebuggingEnabled(true);
        root = new FrameLayout(this);
        root.setBackgroundColor(Pal.VOID);
        web = newWebView();
        // The engine: a 1-pixel window, so the page counts as visible and runs at full speed
        // (Android throttles hidden pages), yet you never see it. Full screen only for sign-in.
        root.addView(web, new FrameLayout.LayoutParams(1, 1));

        stage = new Stage(0x5157);
        px = new PxView(this, stage, this);
        root.addView(px, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        buildTypePanel();
        api = new Api(web, ui);
        browse = new Browse(this);

        session = new MediaSession(this, "Listen");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { control("play"); }
            @Override public void onPause() { control("pause"); }
            @Override public void onSkipToNext() { control("next"); }
            @Override public void onSkipToPrevious() { control("prev"); }
        });
        session.setSessionActivity(nowPlayingIntent(this));
        groove = new Groove(stage);
        stage.dogMin = 214;              // on Now Playing, Pip has the bank under the tunnel
        stage.dogMax = 300;
        stage.placeDog(250);
        wantNow = getIntent().getBooleanExtra("now", false);
        nowFromOutside = wantNow;
        playFromHome(getIntent());
        String tp = getIntent().getStringExtra("testPlay");
        if (tp != null) ui.postDelayed(() -> testPlay(tp), 8000);   // once the player has woken

        // Quiet's voice: the world reacts while you speak, and "stop the music in 30 minutes" lands here.
        try {
            registerReceiver(sleepRx, new android.content.IntentFilter("com.quiet.listen.SLEEP"), "com.quiet.permission.PREFS", null);
        } catch (Exception ignored) {
        }
        if (state == null) web.loadUrl(START);
        else web.restoreState(state);
        ui.postDelayed(this::watch, 1000);
    }

    /** Opens the app on Now Playing (home's ♪, the notification). */
    static PendingIntent nowPlayingIntent(android.content.Context c) {
        return PendingIntent.getActivity(c, 1, new Intent(c, Main.class).putExtra("now", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getBooleanExtra("now", false)) {
            nowFromOutside = true;
            if (!title.isEmpty() && mode != WEB) setMode(NOW);
            else wantNow = true;
        }
        testPlay(i.getStringExtra("testPlay"));
        playFromHome(i);
    }

    // Something chosen from home's LISTEN shelf: played as soon as Apple's player is ready.
    String wantKind, wantId;

    // While something chosen from home is on its way: shown on Now Playing straight away.
    String startTitle = "", startSub = "";
    long startAt;

    void playFromHome(Intent i) {
        String id = i.getStringExtra("playId"), q = i.getStringExtra("playQuery");
        // Only through the FromHome door, which only Quiet apps can open: nothing else starts music here.
        if ((id == null && q == null) || i.getComponent() == null || !i.getComponent().getClassName().endsWith(".FromHome")) return;
        wantKind = i.getStringExtra("playKind");
        wantId = q == null ? id : null;
        wantQuery = q;                                       // spoken: "play some Arijit Singh"
        String t = i.getStringExtra("playTitle"), sub = i.getStringExtra("playSub");
        startTitle = t != null && !t.isEmpty() ? t : q != null ? Font.upper(q) : "YOUR MUSIC";
        startSub = sub != null ? sub : q != null ? "FINDING IT" : "";
        startAt = SystemClock.uptimeMillis();
        npView = 0;
        nowFromOutside = true;
        if (mode != WEB) setMode(NOW);                      // straight to it, with word that it's coming
        else wantNow = true;
        if (stateKnown && authorized) playWanted();
    }

    void playWanted() {
        if (wantQuery != null) {
            playQuery();
            return;
        }
        if (wantId == null) return;
        String k = wantKind, id = wantId;
        wantKind = wantId = null;
        play(k, id, 0, false);
    }

    String wantQuery;

    /**
     * Something asked for by voice: find the best match in Apple Music and play it. An artist plays
     * their top songs (and autoplay carries on from there); anything else plays as itself.
     */
    void playQuery() {
        String q = wantQuery, k = wantKind == null ? "any" : wantKind;
        wantQuery = null;
        if (k.equals("mine")) {                              // "my favourites", "good music": their own taste
            playMine("/v1/me/history/heavy-rotation", true);
            return;
        }
        String types = k.equals("song") ? "songs" : k.equals("album") ? "albums" : k.equals("playlist") ? "playlists"
                : k.equals("artist") ? "artists" : k.equals("station") ? "stations" : "songs,albums,artists,playlists,stations";
        api.get("/v1/catalog/" + storefront + "/search", Api.params("term", q, "types", types, "limit", 3, "with", "topResults"),
                ((Api.Done) r -> {
                    JSONObject res = r.optJSONObject("results");
                    JSONObject pick = null;
                    if (res != null) {
                        JSONObject top = res.optJSONObject("top");
                        if (top != null && top.optJSONArray("data") != null) pick = top.optJSONArray("data").optJSONObject(0);
                        for (String t : types.split(",")) {
                            if (pick != null) break;
                            JSONObject g = res.optJSONObject(t);
                            if (g != null && g.optJSONArray("data") != null) pick = g.optJSONArray("data").optJSONObject(0);
                        }
                    }
                    if (pick == null) {
                        startTitle = "";
                        notice("COULDN'T FIND " + Font.upper(q));
                        return;
                    }
                    JSONObject a = pick.optJSONObject("attributes");
                    String name = a == null ? q : a.optString("name", q), id = pick.optString("id"), type = pick.optString("type");
                    startTitle = Font.upper(name);
                    startSub = a == null ? "" : Font.upper(a.optString("artistName", type.equals("artists") ? "TOP SONGS" : ""));
                    startAt = SystemClock.uptimeMillis();
                    if (type.equals("artists")) playArtist(id, name);
                    else play(type.equals("songs") ? "song" : type.equals("albums") ? "album" : type.equals("playlists") ? "playlist" : "station", id, 0, false);
                }).withFail(why -> {
                    startTitle = "";
                    notice("COULDN'T SEARCH · " + Font.upper(why));
                }));
    }

    /** What they play most (else what they played lately): one of the top few, shuffled. */
    void playMine(String path, boolean thenRecent) {
        api.get(path, Api.params("limit", 10), ((Api.Done) r -> {
            java.util.List<Item> items = new java.util.ArrayList<>();
            for (Item it : Api.items(r)) if (it.playId != null && !it.playId.isEmpty() && (it.playable() || it.container())) items.add(it);
            if (items.isEmpty()) {
                if (thenRecent) playMine("/v1/me/recent/played", false);
                else {
                    startTitle = "";
                    notice("NOTHING OF YOURS TO PLAY YET");
                }
                return;
            }
            Item it = items.get(new java.util.Random().nextInt(Math.min(3, items.size())));
            startTitle = Font.upper(it.title);
            startSub = thenRecent ? "WHAT YOU PLAY MOST" : "WHAT YOU PLAYED LATELY";
            startAt = SystemClock.uptimeMillis();
            play(it.kind, it.playId, 0, !it.playable());
        }).withFail(why -> {
            if (thenRecent) playMine("/v1/me/recent/played", false);
            else {
                startTitle = "";
                notice("COULDN'T REACH YOUR MUSIC");
            }
        }));
    }

    void playArtist(String id, String name) {
        api.get("/v1/catalog/" + storefront + "/artists/" + id + "/view/top-songs", Api.params("limit", 20), ((Api.Done) r -> {
            org.json.JSONArray d = r.optJSONArray("data");
            StringBuilder ids = new StringBuilder();
            for (int j = 0; d != null && j < d.length(); j++) {
                String sid = d.optJSONObject(j) == null ? "" : d.optJSONObject(j).optString("id");
                if (!sid.isEmpty()) ids.append(ids.length() > 0 ? "," : "").append(sid);
            }
            if (ids.length() == 0) {
                startTitle = "";
                notice("COULDN'T FIND " + Font.upper(name));
            } else play("songs", ids.toString(), 0, false);
        }).withFail(why -> {
            startTitle = "";
            notice("COULDN'T FIND " + Font.upper(name));
        }));
    }

    /** Quiet Settings → MUSIC PARTY, kept by the home screen. */
    boolean partyAllowed() {
        try (android.database.Cursor c = getContentResolver().query(android.net.Uri.parse("content://com.quiet.home.prefs/all"), null, null, null, null)) {
            while (c != null && c.moveToNext()) if ("party".equals(c.getString(0))) return !"off".equals(c.getString(1));
        } catch (Exception ignored) {
        }
        return true;
    }

    /**
     * For tuning the party: play the top search result for a phrase. Only while the TV has
     * debug.quiet.test set to 1, so nothing else can start music through this.
     */
    void testPlay(String q) {
        if (q == null || q.isEmpty()) return;
        String on = "";
        try {
            on = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "debug.quiet.test");
        } catch (Throwable ignored) {
        }
        if (!"1".equals(on)) {
            Log.i("QuietMusic", "test play: ignored (debug.quiet.test not set)");
            return;
        }
        Log.i("QuietMusic", "test play: searching " + q);
        api.get("/v1/catalog/" + storefront + "/search", Api.params("term", q, "types", "songs", "limit", 1), ((Api.Done) r -> {
            try {
                org.json.JSONObject songs = r.optJSONObject("results") != null ? r.optJSONObject("results").optJSONObject("songs") : null;
                String id = songs.getJSONArray("data").getJSONObject(0).getString("id");
                Log.i("QuietMusic", "test play: " + q + " → " + id);
                play("songs", id, 0, false);
            } catch (Exception e) {
                Log.i("QuietMusic", "test play: nothing for " + q + " · " + r);
            }
        }).withFail(why -> Log.i("QuietMusic", "test play: search failed · " + why)));
    }

    void buildTypePanel() {
        typePanel = new LinearLayout(this);
        typePanel.setOrientation(LinearLayout.VERTICAL);
        typePanel.setBackgroundColor(0xF00B0B10);
        typePanel.setPadding(120, 70, 120, 60);
        typePanel.setVisibility(View.GONE);
        android.graphics.Typeface mono = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD);
        typeTitle = new TextView(this);
        typeTitle.setTypeface(mono);
        typeTitle.setTextColor(Pal.AMBER);
        typeTitle.setTextSize(22);
        typeTitle.setLetterSpacing(0.15f);
        typePanel.addView(typeTitle);
        typeError = new TextView(this);
        typeError.setTypeface(mono);
        typeError.setTextColor(Pal.PINK);
        typeError.setTextSize(16);
        typeError.setPadding(0, 14, 0, 0);
        typePanel.addView(typeError);
        typer = new EditText(this);
        typer.setTypeface(mono);
        typer.setTextColor(Pal.TEXT);
        typer.setTextSize(30);
        typer.setSingleLine(true);
        typer.setBackgroundColor(0x00000000);
        typer.setPadding(0, 30, 0, 20);
        typer.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        typer.setOnEditorActionListener((v, action, ev) -> {
            sendTyped();
            return true;
        });
        typePanel.addView(typer, new LinearLayout.LayoutParams(-1, -2));
        View rule = new View(this);
        rule.setBackgroundColor(Pal.AMBER);
        typePanel.addView(rule, new LinearLayout.LayoutParams(-1, 4));
        TextView hint = new TextView(this);
        hint.setTypeface(mono);
        hint.setTextColor(Pal.DIM);
        hint.setTextSize(16);
        hint.setPadding(0, 24, 0, 0);
        hint.setText("DONE ON THE KEYBOARD SENDS IT TO APPLE'S PAGE  \u00B7  BACK TO CANCEL");
        typePanel.addView(hint);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(typePanel, lp);
    }

    /** Open the typing panel, labelled for the field Apple is actually showing. */
    void openTypePanel(String kind, String label, String error) {
        if (typePanel.getVisibility() == View.VISIBLE) return;
        switch (kind) {
            case "email":
                typeTitle.setText("YOUR APPLE ACCOUNT EMAIL");
                typer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
                break;
            case "password":
                typeTitle.setText("YOUR APPLE ACCOUNT PASSWORD");
                typer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                break;
            case "code":
                typeTitle.setText("THE 6-DIGIT CODE APPLE SHOWS ON YOUR IPHONE OR MAC");
                typer.setInputType(InputType.TYPE_CLASS_NUMBER);
                break;
            default:
                typeTitle.setText(label.isEmpty() ? "TYPE HERE" : label.toUpperCase(Locale.ROOT));
                typer.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        typeError.setText(error.toUpperCase(Locale.ROOT));
        typeError.setVisibility(error.isEmpty() ? View.GONE : View.VISIBLE);
        typer.setText("");
        typePanel.setVisibility(View.VISIBLE);
        typePanel.bringToFront();
        typer.requestFocus();
        ui.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showSoftInput(typer, InputMethodManager.SHOW_IMPLICIT);
        }, 120);
    }

    void closeTypePanel() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(typer.getWindowToken(), 0);
        typePanel.setVisibility(View.GONE);
        (popup != null ? popup : web).requestFocus();
    }

    /** Put the text into Apple's field and press Apple's own Continue button. */
    void sendTyped() {
        String text = typer.getText().toString();
        WebView target = popup != null ? popup : web;
        closeTypePanel();
        if (text.isEmpty()) {                               // Done on an empty box: send what's already there
            target.evaluateJavascript("window.__quietSubmit&&__quietSubmit()", null);
            ui.postDelayed(this::maybeType, 2500);
            return;
        }
        target.evaluateJavascript("window.__quietFill?__quietFill(" + JSONObject.quote(text) + "):'none'", r -> {
            if (r == null || r.contains("none")) {
                // No readable field (shouldn't happen on Apple's page): fall back to real keystrokes.
                KeyEvent[] keys = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
                if (keys != null) for (KeyEvent k : keys) target.dispatchKeyEvent(k);
                target.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                target.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            }
        });
        ui.postDelayed(this::maybeType, 2500);           // whatever Apple asks for next
    }

    /** Ask Apple's page what it wants typed, if anything, and open the panel for it. */
    void maybeType() {
        maybeType(false);
    }

    /** fromKey: the person pressed OK, so a fully filled form means "send it". */
    void maybeType(boolean fromKey) {
        WebView target = popup != null ? popup : web;
        if (mode != WEB || typePanel.getVisibility() == View.VISIBLE) return;
        target.evaluateJavascript("window.__quietField?__quietField():''", r -> {
            try {
                Object v = new org.json.JSONTokener(r).nextValue();
                if (!(v instanceof String) || ((String) v).isEmpty()) return;
                JSONObject f = new JSONObject((String) v);
                String kind = f.optString("kind", "none");
                if (kind.equals("ready")) {
                    if (fromKey) {
                        target.evaluateJavascript("window.__quietSubmit&&__quietSubmit()", null);
                        ui.postDelayed(this::maybeType, 2500);
                    }
                } else if (!kind.equals("none")) openTypePanel(kind, f.optString("label"), f.optString("error"));
            } catch (Exception ignored) {
            }
        });
    }

    /** Developer switch, only reachable over adb: setprop debug.quiet.webdebug 1 */
    static boolean devDebug() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return "1".equals(sp.getMethod("get", String.class).invoke(null, "debug.quiet.webdebug"));
        } catch (Throwable t) {
            return false;
        }
    }

    volatile int movingCovers;

    WebView newWebView() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);                          // nothing on the TV is the page's business
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);                    // Apple sign-in opens a popup
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(desktopAgent());
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);
        s.setForceDark(WebSettings.FORCE_DARK_ON);            // Apple's dark theme, for the sign-in page
        // The player's page is never seen (we draw our own covers): no pictures, so the first song
        // isn't kept waiting behind them. Sign-in (shown full screen, or its popup) has them.
        s.setBlockNetworkImage(web == null);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setBackgroundColor(0xFF000000);
        w.addJavascriptInterface(new Bridge(), "QuietBridge");
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !apple(r.getUrl());            // stay on Apple's pages
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                v.evaluateJavascript(Page.SCRIPT, null);
            }

            /**
             * Apple's home page plays ~20 moving album covers (video, from mvod.itunes.apple.com) in the
             * hidden page; on a cold start they crowded out the first song (32 s instead of ~5). Listen
             * plays no videos, so they're left out. (Songs come from elsewhere: aod.)
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (!"mvod.itunes.apple.com".equals(r.getUrl().getHost())) return null;
                if (movingCovers++ == 0) Log.i("QuietMusic", "moving covers left out of the hidden page");
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, new java.io.ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(Page.SCRIPT, null);
                if (v == popup) ui.postDelayed(Main.this::maybeType, 1500);   // sign-in page: ready to type
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest r) {
                if (apple(r.getOrigin())) r.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                else r.deny();
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message msg) {
                closePopup();
                popup = newWebView();
                root.addView(popup, root.indexOfChild(px), new FrameLayout.LayoutParams(-1, -1));
                ((WebView.WebViewTransport) msg.obj).setWebView(popup);
                msg.sendToTarget();
                popup.requestFocus();
                return true;
            }

            @Override
            public void onCloseWindow(WebView v) {
                if (v == popup) closePopup();
                ui.postDelayed(Main.this::adoptSignIn, 1500);
            }
        });
        return w;
    }

    static boolean apple(Uri u) {
        String h = u == null || u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        return h.equals("apple.com") || h.endsWith(".apple.com") || h.endsWith(".mzstatic.com") || h.endsWith(".apple-cloudkit.com");
    }

    /** Apple serves its full web player to desktop browsers; present as Chrome on Windows. */
    String desktopAgent() {
        Matcher m = Pattern.compile("Chrome/([0-9.]+)").matcher(WebSettings.getDefaultUserAgent(this));
        String v = m.find() ? m.group(1) : "126.0.0.0";
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + v + " Safari/537.36";
    }

    /**
     * Apple's sign-in window saves the session on authorize.music.apple.com and is meant to hand
     * it to the player by messaging its opener, which doesn't cross WebViews. Copy it over instead.
     */
    void adoptSignIn() {
        if (authorized) return;
        CookieManager cm = CookieManager.getInstance();
        String token = cookie(cm.getCookie("https://authorize.music.apple.com"), "media-user-token");
        if (token == null || token.equals(cookie(cm.getCookie("https://music.apple.com"), "media-user-token"))) return;
        for (String name : new String[]{"media-user-token", "itua", "itre"}) {
            String v = cookie(cm.getCookie("https://authorize.music.apple.com"), name);
            if (v != null) cm.setCookie("https://music.apple.com", name + "=" + v + "; Domain=.music.apple.com; Path=/; Secure; Max-Age=15552000");
        }
        cm.flush();
        Log.i("QuietMusic", "adopted sign-in from Apple's sign-in window");
        web.reload();
    }

    static String cookie(String all, String name) {
        if (all == null) return null;
        for (String c : all.split(";")) {
            c = c.trim();
            if (c.startsWith(name + "=")) return c.substring(name.length() + 1);
        }
        return null;
    }

    void closePopup() {
        if (popup == null) return;
        root.removeView(popup);
        popup.destroy();
        popup = null;
    }

    void setMode(int m) {
        mode = m;
        boolean pixels = m != WEB;
        web.getSettings().setBlockNetworkImage(pixels);
        web.setLayoutParams(pixels ? new FrameLayout.LayoutParams(1, 1) : new FrameLayout.LayoutParams(-1, -1));
        px.setVisibility(pixels ? View.VISIBLE : View.GONE);
        if (pixels) {
            closePopup();
            px.poke();
            px.start();
            px.requestFocus();
        } else {
            px.stop();
            web.requestFocus();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.quiet.px.Oops.resumed(this);
        web.onResume();
        lastInput = SystemClock.uptimeMillis();
        try {
            registerReceiver(voiceRx, new android.content.IntentFilter("com.quiet.VOICE"), "com.quiet.permission.PREFS", null);
        } catch (Exception ignored) {
        }
        box.resume();
        com.quiet.px.Weather.refresh(this);
        if (mode != WEB) px.start();
        groove.start();
        stage.partyAllowed = partyAllowed();
        com.quiet.px.World.load(this, stage);
    }

    @Override
    protected void onPause() {
        super.onPause();
        com.quiet.px.Oops.paused(this);
        try {
            unregisterReceiver(voiceRx);
        } catch (Exception ignored) {
        }
        box.pause();
        groove.stop();
        com.quiet.px.World.save(this, stage);
    }

    @Override
    protected void onStop() {
        super.onStop();
        px.stop();
        if (title.isEmpty() && !queued) web.onPause();     // keep the page alive once anything is queued, even mid-load
    }

    final android.content.BroadcastReceiver voiceRx = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            stage.voice(i.getIntExtra("state", 0));
            px.poke();
        }
    };

    final android.content.BroadcastReceiver sleepRx = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            sleepFor(i.getIntExtra("minutes", 0));
        }
    };

    /** A sleep timer of any length (by voice): 0 cancels, -1 means at the end of this song. */
    void sleepFor(int min) {
        if (windDown > 0) setVolume(1f);
        windDown = 0;
        stage.windDown = 0;
        dimUntil = 0;
        if (min == 0) sleep = 0;
        else if (min < 0) {
            sleep = 1;
            sleepSong = title;
        } else {
            sleep = SLEEP_MIN.length - 1;                    // a timed setting, with our own end
            sleepEnd = SystemClock.uptimeMillis() + min * 60_000L;
            sleepSong = title;
        }
        notice(sleepLabel());
        browse.refreshBar();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(sleepRx);
        } catch (Exception ignored) {
        }
        ui.removeCallbacksAndMessages(null);
        session.release();
        Playback.hide(this);
        closePopup();
        web.destroy();
        covers.pool.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- what the rest of the app asks for

    void play(String kind, String id, int start, boolean shuffle) {
        web.evaluateJavascript("window.__quietPlay&&__quietPlay(" + JSONObject.quote(kind) + "," + JSONObject.quote(id)
                + "," + start + "," + shuffle + ")", null);
    }

    void control(String c) {
        web.evaluateJavascript("window.__quietCtl&&__quietCtl('" + c + "')", null);
    }

    void signIn() {
        setMode(WEB);
        ui.postDelayed(this::maybeType, 4000);
        web.evaluateJavascript("window.__quietAuth&&__quietAuth()", null);
    }

    void leave() {
        moveTaskToBack(true);                                // music keeps playing
    }

    void showNowPlaying(boolean show) {
        if (show) nowFromOutside = false;
        setMode(show ? NOW : BROWSE);
    }

    void notice(String s) {
        notice = s;
        noticeUntil = SystemClock.uptimeMillis() + 5000;
    }

    // ---------------------------------------------------------------- the page talks back

    final class Bridge {
        @JavascriptInterface
        public void lyrics(String song, String json) {
            java.util.List<Line> list = new java.util.ArrayList<>();
            try {
                org.json.JSONArray a = new org.json.JSONArray(json);
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONObject o = a.getJSONObject(i);
                    org.json.JSONArray w = o.getJSONArray("w");
                    Line l = new Line();
                    l.b = (float) o.optDouble("b");
                    l.e = (float) o.optDouble("e");
                    l.words = new String[w.length()];
                    l.wb = new float[w.length()];
                    l.we = new float[w.length()];
                    StringBuilder all = new StringBuilder();
                    for (int k = 0; k < w.length(); k++) {
                        org.json.JSONArray x = w.getJSONArray(k);
                        l.words[k] = Font.upper(x.getString(0).replace('\n', ' '));
                        l.wb[k] = (float) x.optDouble(1);
                        l.we[k] = (float) x.optDouble(2);
                        all.append(l.words[k]);
                    }
                    l.text = all.toString().trim();
                    if (l.words.length == 1 && l.text.contains(" ")) sweep(l);   // line-timed only
                    if (!l.text.isEmpty()) list.add(l);
                }
            } catch (Exception ignored) {
            }
            ui.post(() -> {
                lyrics = list.isEmpty() ? null : list;
                lyricsSong = song;
                Log.i("QuietMusic", "lyrics: " + list.size() + " lines");
            });
        }

        @JavascriptInterface
        public void now(String t, String a, String al, String artwork, boolean p, double position, double duration, boolean live, String genres) {
            ui.post(() -> {
                Main.this.live = live;
                Main.this.genres = genres == null ? "" : genres;
                stage.genre = live ? "Radio" : Main.this.genres;
                update(t, a, al, artwork, p, (float) position, (float) duration);
            });
        }

        @JavascriptInterface
        public void log(String s) {
            Log.i("QuietMusic", "js: " + s);
        }

        /** A text field on Apple's page took focus: raise the TV keyboard (only while signing in). */
        @JavascriptInterface
        public void editing(boolean on) {
            ui.post(() -> {
                if (mode != WEB || !on) return;
                maybeType();
            });
        }

        @JavascriptInterface
        public void drm(boolean ok) {
            Log.i("QuietMusic", "page ready, protected playback " + (ok ? "available" : "unavailable"));
            ui.post(() -> {
                drmKnown = true;
                drm = ok;
            });
        }

        @JavascriptInterface
        public void state(boolean auth, String sf) {
            ui.post(() -> {
                boolean changed = !stateKnown || auth != authorized || !sf.equals(storefront);
                stateKnown = true;
                authorized = auth;
                storefront = sf;
                if (changed) {
                    Log.i("QuietMusic", "signed " + (auth ? "in" : "out") + ", storefront " + sf);
                    if (!auth) adoptSignIn();                // a sign-in that never got handed over
                    browse.refresh();
                    if (auth && mode == WEB) setMode(BROWSE);
                }
                if (auth) playWanted();
            });
        }

        @JavascriptInterface
        public void auth(boolean ok) {
            ui.post(() -> {
                if (mode == WEB) setMode(BROWSE);
                if (!ok) notice("SIGN-IN DIDN'T FINISH");
            });
        }

        @JavascriptInterface
        public void reply(int id, String json) {
            ui.post(() -> api.reply(id, json));
        }

        @JavascriptInterface
        public void fail(int id, String why) {
            ui.post(() -> api.fail(id, why == null ? "" : why.toUpperCase(Locale.ROOT)));
        }

        @JavascriptInterface
        public void loved(boolean ok, boolean on, String why) {
            ui.post(() -> notice(ok ? (on ? "♥ LOVED · IN YOUR LIBRARY" : "NO LONGER LOVED")
                    : "COULDN'T " + (on ? "LOVE" : "UNLOVE") + " IT · " + why.toUpperCase(Locale.ROOT)));
        }

        @JavascriptInterface
        public void rating(String song, int value) {
            ui.post(() -> {
                loved = value == 1;
                px.now();
            });
        }

        @JavascriptInterface
        public void queued() {
            queued = true;
        }

        @JavascriptInterface
        public void played(boolean ok, String why) {
            ui.post(() -> {
                if (!ok) {
                    startTitle = "";
                    notice("COULDN'T PLAY · " + Font.upper(why));
                }
                else {
                    if (!authorized) notice("PREVIEW · SIGN IN FOR FULL SONGS");
                    if (mode == BROWSE) {                       // you picked something: straight to it
                        npView = 0;
                        nowFromOutside = false;
                        setMode(NOW);
                    }
                }
            });
        }

        /** The queue ran out and similar music took over (or couldn't). */
        @JavascriptInterface
        public void autoplay(boolean ok) {
            ui.post(() -> notice(ok ? "AUTOPLAY · SIMILAR MUSIC" : "NOTHING MORE TO PLAY"));
        }
    }

    void update(String t, String a, String al, String artwork, boolean p, float position, float duration) {
        boolean changed = !t.equals(title) || !a.equals(artist);
        boolean started = p && !playing;
        if (p != playing && !changed) Log.i("QuietMusic", p ? "playing" : "paused");
        title = t;
        artist = a;
        album = al;
        playing = p;
        pos = position;
        dur = duration;
        posAt = SystemClock.uptimeMillis();
        if (wantNow && !t.isEmpty() && mode != WEB) {
            wantNow = false;
            setMode(NOW);
        }
        if (p && !t.isEmpty()) startTitle = "";             // it's here
        if (!p && pausedAt == 0) pausedAt = SystemClock.uptimeMillis();
        if (p) pausedAt = 0;
        if (!artwork.equals(artUrl)) {
            artUrl = artwork;
            art = null;
            loadArt(artwork);
        }
        if (changed) {
            Log.i("QuietMusic", "now playing: " + t + " / " + a + (p ? " (playing)" : " (paused)"));
            lyrics = null;
            lyricsSong = "";
            loved = false;
            if (!t.isEmpty()) web.evaluateJavascript("window.__quietRating&&__quietRating()", null);
            if (npView == 2) fetchUpNext();
            if (!t.isEmpty()) web.evaluateJavascript("window.__quietLyrics&&__quietLyrics()", null);
            publishMetadata(null);
        }
        publishState(false);
        if (session.isActive() == t.isEmpty()) session.setActive(!t.isEmpty());
        if ((started || changed) && p) {
            Playback.show(this, title, artist);              // keeps the music going while you're elsewhere
            serviceUp = true;
        }
    }

    void publishMetadata(Bitmap cover) {
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putString(MediaMetadata.METADATA_KEY_GENRE, genres)            // so home's party knows the kind of music
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (dur * 1000));
        if (cover != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover);
        session.setMetadata(b.build());
    }

    /** Once a second: drift into Now Playing when idle; let the service go after a long pause. */
    /** The media session's state, with the sleep timer's wind-down for home and the screensaver. */
    // What was last told to the rest of the TV: only real changes are announced (a play/pause, a new
    // song, a jump in position, the wind-down), not the page's once-a-second report.
    int pubState = -1;
    float pubPos, pubWind = -1;
    long pubAt;

    void publishState() {
        publishState(true);
    }

    void publishState(boolean force) {
        int st = playing ? PlaybackState.STATE_PLAYING : title.isEmpty() ? PlaybackState.STATE_NONE : PlaybackState.STATE_PAUSED;
        long now = SystemClock.uptimeMillis();
        float expected = pubPos + (pubState == PlaybackState.STATE_PLAYING ? (now - pubAt) / 1000f : 0f);
        if (!force && st == pubState && windDown == pubWind && Math.abs(pos - expected) < 2f) return;
        pubState = st;
        pubPos = pos;
        pubAt = now;
        pubWind = windDown;
        Bundle x = new Bundle();
        x.putFloat("quiet.winddown", windDown);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? PlaybackState.STATE_PLAYING : title.isEmpty() ? PlaybackState.STATE_NONE : PlaybackState.STATE_PAUSED,
                        (long) (pos * 1000), playing ? 1f : 0f)
                .setExtras(x)
                .build());
    }

    /** ☾ in the icon bar: the next sleep timer setting. */
    void cycleSleep() {
        sleep = (sleep + 1) % SLEEP_MIN.length;
        int min = SLEEP_MIN[sleep];
        sleepEnd = min > 0 ? SystemClock.uptimeMillis() + min * 60_000L : 0;
        sleepSong = title;
        if (windDown > 0) setVolume(1f);
        windDown = 0;
        stage.windDown = 0;
        dimUntil = 0;
        notice(sleepLabel());
        browse.refreshBar();
    }

    String sleepLabel() {
        int min = SLEEP_MIN[sleep];
        if (min == 0) return "SLEEP TIMER · OFF";
        if (min < 0) return "SLEEP · AT THE END OF THIS SONG";
        long left = Math.max(0, (sleepEnd - SystemClock.uptimeMillis()) / 60_000) + 1;
        return "SLEEP · " + left + " MIN LEFT";
    }

    void setVolume(float v) {
        web.evaluateJavascript("try{MusicKit.getInstance().volume=" + v + "}catch(e){}", null);
    }

    /** Once a second: while a timer runs, the last minute (or the song's last 20 seconds) winds down. */
    void sleepTick() {
        long now = SystemClock.uptimeMillis();
        float wd = 0;
        boolean done = false;
        if (SLEEP_MIN[sleep] > 0) {
            long left = sleepEnd - now;
            wd = 1f - Math.max(0, Math.min(1f, left / 60_000f));
            done = left <= 0;
        } else if (SLEEP_MIN[sleep] < 0) {
            if (!title.equals(sleepSong) && !title.isEmpty()) done = true;      // on to the next: it has ended
            else if (dur > 0) wd = 1f - Math.max(0, Math.min(1f, (dur - pos) / 20f));
        }
        if (done) {
            control("pause");
            setVolume(1f);
            sleep = 0;
            wd = 1f;
            dimUntil = now + 5 * 60_000;                      // stays dark a while; any key brings it back
            browse.refreshBar();
        } else if (sleep == 0) {
            wd = now < dimUntil && now - lastInput > 1500 ? 1f : 0f;
            if (wd == 0) dimUntil = 0;
        } else if (wd > 0) setVolume(Math.max(0.05f, 1f - wd));
        if (Math.abs(wd - windDown) > 0.01f) {
            windDown = wd;
            stage.windDown = wd;
            publishState();
        }
    }

    boolean nudged;

    void watch() {
        long now = SystemClock.uptimeMillis();
        sleepTick();
        if (!startTitle.isEmpty()) {
            // Loaded but holding still (a start nobody pressed play on can wait for a gesture): nudge it.
            if (!nudged && !title.isEmpty() && !playing && now - startAt > 6000) {
                nudged = true;
                control("play");
            }
            if (now - startAt > 60_000) {                    // a minute and nothing: say so
                startTitle = "";
                notice("COULDN'T START IT · TRY AGAIN");
            }
        } else nudged = false;
        if (playing && mode == BROWSE && now - lastInput > NOW_PLAYING_AFTER_MS && hasWindowFocus()) {
            nowFromOutside = false;
            setMode(NOW);
        }
        // Stay loaded for 3 hours after the music stops: Apple's player is then ready in ~3 s
        // instead of a 15-40 s cold start the next time you ask for music.
        if (serviceUp && !playing && pausedAt > 0 && now - pausedAt > 3 * 3_600_000L) {
            Playback.hide(this);
            serviceUp = false;
        }
        ui.postDelayed(this::watch, 1000);
    }

    void loadArt(String url) {
        if (url.isEmpty()) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                Bitmap b;
                try (InputStream in = c.getInputStream()) {
                    b = BitmapFactory.decodeStream(in);
                }
                if (b == null) return;
                Bitmap pixels = com.quiet.px.Fine.make(b, 56, 56);
                Bitmap cover = Bitmap.createScaledBitmap(b, 160, 160, true);
                ui.post(() -> {
                    if (!url.equals(artUrl)) return;
                    art = pixels;
                    publishMetadata(cover);
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    // ---------------------------------------------------------------- drawing

    /** The search box, for the voice: open while Search shows; words it hears go into it. */
    final com.quiet.px.Box box = new com.quiet.px.Box(this, new com.quiet.px.Box.Page() {
        @Override
        public boolean searching() {
            return mode == BROWSE && browse.current.equals(Browse.SEARCH) && (browse.area == Browse.KEYS || browse.area == Browse.RESULTS);
        }

        @Override
        public String query() {
            return browse.query;
        }

        @Override
        public void typed(String words) {
            if (mode != BROWSE) setMode(BROWSE);
            browse.typedIn(words);
            px.poke();
            px.now();
        }
    });

    @Override
    public void frame(Stage s) {
        box.check();
        s.music = playing;
        // Pip's always out (one world across pages): under the tunnel on Now Playing; while browsing,
        // on the bank under the rail, where the album grid doesn't reach.
        s.dogOut = true;
        if (mode == NOW) s.dogPatch(214, 300);
        else s.dogPatch(8, 92);
        s.camera(0.5f);
        if (mode == NOW) {
            nowPlaying(s);
            return;
        }
        if (!carved.isEmpty()) {
            carved = "";
            s.carve("", 298, 150, 3);
        }
        browse.lights(s);
        s.resolve();
        s.decor();
        browse.draw(s);
        s.actors();
        s.pool();
        if (!notice.isEmpty() && SystemClock.uptimeMillis() < noticeUntil)        // after the water: not reflected
            s.small(notice, Browse.CX, 160, 1, notice.startsWith("PREVIEW") ? Pal.AMBER : Pal.PINK);
    }

    void fetchUpNext() {
        web.evaluateJavascript("window.__quietQueue?__quietQueue():'[]'", r -> {
            java.util.List<String[]> list = new java.util.ArrayList<>();
            try {
                Object v = new org.json.JSONTokener(r).nextValue();
                org.json.JSONArray a = new org.json.JSONArray(v instanceof String ? (String) v : "[]");
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONArray it = a.getJSONArray(i);
                    list.add(new String[]{it.optString(0), it.optString(1)});
                }
            } catch (Exception ignored) {
            }
            upNext = list;
            px.now();
        });
    }

    void nowPlaying(Stage s) {
        // Carve the artist into the tunnel; just the surname if the whole name won't fit the arch.
        String carve = Font.upper(artist.isEmpty() ? "LISTEN" : artist);
        if (Font.width(carve, 2, 1) > 108 && carve.contains(" ")) carve = carve.substring(carve.lastIndexOf(' ') + 1);
        if (!carve.equals(carved)) {
            carved = carve;
            s.carve(carve, 298, 150, Font.width(carve, 3, 1) <= 108 ? 3 : 2);
        }
        s.light(52, 70, 74, playing ? 0.4f : 0.25f, true);        // the cover glows
        s.light(250, 138, 70, 0.16f, false);
        s.resolve();
        s.decor();

        s.small("NOW PLAYING", 22, 29, 1, Pal.DIM);
        s.rect(21, 37, 62, 62, Pal.COOL[3]);
        s.rect(22, 38, 60, 60, Pal.VOID);
        if (art != null) {
            s.fine(art, 24, 40, 56, 56, 0f);
        } else {
            s.text("♪", 46, 58, 2, Pal.FAINT);
        }
        int tx = 94, maxW = 214;
        boolean starting = !startTitle.isEmpty();
        String t = Font.upper(starting ? startTitle : title.isEmpty() ? "NOTHING PLAYING" : title);
        if (Font.width(t, 2, 1) <= maxW) s.text(t, tx, 42, 2, Pal.TEXT);
        else s.text(fit(t, maxW), tx, 46, 1, Pal.TEXT);
        if (starting) {                                      // on its way: what it is, and that it's coming
            s.small(Font.upper(startSub), tx, 62, 1, Pal.SOFT);
            s.rect(tx, 84, maxW, 1, Pal.FAINT);
            int w = maxW / 5, at = (int) ((s.t * 70) % (maxW + w)) - w;     // a light running along the bar
            s.rect(tx + Math.max(0, at), 84, Math.max(0, Math.min(w, maxW - at) - Math.max(0, -at)), 1, Pal.AMBER);
            String why = !stateKnown ? "WAKING THE PLAYER" : !authorized ? "SIGNING IN" : "STARTING";
            s.small(why + Browse.dots(s), tx, 88, 1, Pal.AMBER);
            s.mote.perch(88, 34);
            s.actors();
            s.pool();
            return;
        }
        int ax = s.small(Font.upper(artist), tx, 62, 1, Pal.SOFT);
        if (loved) s.small("♥", ax + 4, 62, 1, Pal.PINK);
        if (!album.equals(title)) s.small(Font.upper(album), tx, 70, 1, Pal.DIM);
        s.rect(tx, 84, maxW, 1, Pal.FAINT);
        if (live) {
            boolean on = !playing || (int) (s.t * 1.5f) % 2 == 0;
            s.rect(tx, 89, 3, 3, on ? Pal.PINK : Pal.FAINT);
            s.small("LIVE", tx + 6, 88, 1, Pal.PINK);
        } else {
            if (dur > 0) s.rect(tx, 84, Math.round(maxW * Math.min(1f, pos / dur)), 1, Pal.AMBER);
            s.small(time(pos), tx, 88, 1, Pal.DIM);
            String d = time(dur);
            s.small(d, tx + maxW - Font.microWidth(d, 1), 88, 1, Pal.DIM);
        }
        if (npView == 0) lyrics(s, tx);
        else if (npView == 1) bigLyrics(s);
        else upNextList(s);
        if (playing) {                                   // a little pixel equaliser
            for (int b = 0; b < 7; b++) {
                int h = 1 + (int) (4.5f * (0.5f + 0.5f * (float) Math.sin(s.t * (5 + b * 1.7f) + b * 2.1f)));
                s.rect(tx + b * 3, 104 - h, 2, h, Pal.MINT);
            }
        } else {
            s.small("PAUSED", tx, 99, 2, Pal.AMBER);
        }
        if (sleep != 0) s.small(SLEEP_MIN[sleep] < 0 ? "SLEEP · END OF SONG" : sleepLabel(), tx + 30, 99, 1, Pal.AMBER);
        else if (drmKnown && !drm) s.small("PREVIEWS ONLY ON THIS TV", tx + 30, 99, 1, Pal.PINK);
        else if (stateKnown && !authorized) s.small("PREVIEWS · SIGN IN FOR FULL SONGS", tx + 30, 99, 1, Pal.AMBER);
        s.mote.perch(88, 34);
        s.actors();
        s.pool();
        // Two short lines, clear of the bank and after the water so they aren't reflected
        // (a notice takes the first line's place for a moment).
        if (!notice.isEmpty() && SystemClock.uptimeMillis() < noticeUntil)
            s.small(notice, 22, 148, 1, notice.startsWith("♥") ? Pal.PINK : Pal.AMBER);
        else s.small("OK " + (playing ? "PAUSE" : "PLAY") + (live ? "" : " · HOLD OK ♥ · < > SKIP"), 22, 148, 1, Pal.FAINT);
        s.small("▼ " + (npView == 0 ? "LYRICS" : npView == 1 ? "UP NEXT" : "SONG") + " · ▲ BROWSE", 22, 156, 1, Pal.FAINT);
    }

    /**
     * Lyrics under the progress bar: the line just sung (faint), the line being sung with each word
     * lighting up as it's sung, and the next two lines waiting below.
     */
    void lyrics(Stage s, int tx) {
        if (lyrics == null || lyrics.isEmpty()) return;
        float now = pos + (playing ? (SystemClock.uptimeMillis() - posAt) / 1000f : 0f);
        int cur = -1;
        for (int i = 0; i < lyrics.size(); i++) if (lyrics.get(i).b <= now + 0.15f) cur = i;
        int x = 22, maxW = 276, y = 110;
        if (cur > 0) s.small(clip(lyrics.get(cur - 1).text, maxW), x, y, 1, Pal.FAINT);
        y += 9;
        if (cur < 0) {                                   // the intro: what's coming
            s.text("♪", x, y, 1, Pal.DIM);
        } else {
            Line l = lyrics.get(cur);
            boolean big = Font.width(l.text, 1, 1) <= maxW;
            boolean done = now > l.e + 0.3f;
            int wx = x;
            for (int k = 0; k < l.words.length; k++) {
                String w = k == 0 ? l.words[k].replaceAll("^\\s+", "") : l.words[k];
                int col = done ? Pal.SOFT : now >= l.wb[k] ? Pal.TEXT : Pal.DIM;
                if (big) wx = s.text(w, wx, y, 1, col);
                else wx = s.small(w, wx, y + 1, 1, col);
                if (wx > x + maxW) break;
            }
        }
        y += 12;
        if (cur + 1 < lyrics.size()) s.small(clip(lyrics.get(cur + 1).text, 164), x, y, 1, Pal.DIM);   // clear of the carving
    }

    /** A line with no word timings: share its time out across the words, by length. */
    static void sweep(Line l) {
        String[] ws = l.text.split("(?<= )");
        int total = l.text.length();
        float span = Math.max(0.3f, (l.e - l.b) * 0.9f), at = l.b;
        l.words = ws;
        l.wb = new float[ws.length];
        l.we = new float[ws.length];
        for (int k = 0; k < ws.length; k++) {
            l.wb[k] = at;
            at += span * ws[k].length() / total;
            l.we[k] = at;
        }
    }

    /** Just the lyrics, large, across the width (the song's details stay above). */
    void bigLyrics(Stage s) {
        if (lyrics == null || lyrics.isEmpty()) {
            s.small("NO LYRICS FOR THIS ONE", 22, 112, 1, Pal.DIM);
            return;
        }
        s.scrim(18, 106, 284, 19);                      // under the big line
        s.scrim(18, 125, 206, 19);                      // under the next two, short of the carving
        float now = pos + (playing ? (SystemClock.uptimeMillis() - posAt) / 1000f : 0f);
        int cur = -1;
        for (int i = 0; i < lyrics.size(); i++) if (lyrics.get(i).b <= now + 0.15f) cur = i;
        if (cur < 0) {
            s.text("♪", 22, 116, 2, Pal.DIM);
            if (!lyrics.isEmpty()) s.small(clip(lyrics.get(0).text, 200), 22, 128, 1, Pal.DIM);
            return;
        }
        Line l = lyrics.get(cur);
        int scale = Font.width(l.text, 2, 1) <= 276 ? 2 : 1;
        boolean done = now > l.e + 0.3f;
        int wx = 22;
        for (int k = 0; k < l.words.length; k++) {
            String w = k == 0 ? l.words[k].replaceAll("^\\s+", "") : l.words[k];
            int col = done ? Pal.SOFT : now >= l.wb[k] ? Pal.TEXT : Pal.DIM;
            wx = s.text(w, wx, scale == 2 ? 112 : 116, scale, col);
            if (wx > 298) break;
        }
        if (cur + 1 < lyrics.size()) s.small(clip(lyrics.get(cur + 1).text, 200), 22, 128, 1, Pal.DIM);
        if (cur + 2 < lyrics.size()) s.small(clip(lyrics.get(cur + 2).text, 200), 22, 136, 1, Pal.FAINT);
    }

    /** What's coming up in the queue. */
    void upNextList(Stage s) {
        s.small("UP NEXT", 22, 108, 1, Pal.DIM);
        if (upNext.isEmpty()) {
            s.small(live ? "LIVE RADIO · NO QUEUE" : "NOTHING QUEUED AFTER THIS", 22, 118, 1, Pal.DIM);
            return;
        }
        for (int i = 0; i < Math.min(3, upNext.size()); i++) {
            String[] it = upNext.get(i);
            int y = 118 + i * 8;
            s.small(String.valueOf(i + 1), 22, y, 1, Pal.DIM);
            s.small(clip(Font.upper(it[0]) + (it[1].isEmpty() ? "" : "  ·  " + Font.upper(it[1])), 160), 30, y, 1, i == 0 ? Pal.SOFT : Pal.DIM);
        }
    }

    static String clip(String s, int w) {
        if (Font.microWidth(s, 1) <= w) return s;
        while (s.length() > 1 && Font.microWidth(s + "...", 1) > w) s = s.substring(0, s.length() - 1);
        return s.trim() + "...";
    }

    static String fit(String s, int w) {
        if (Font.width(s, 1, 1) <= w) return s;
        while (s.length() > 1 && Font.width(s + "...", 1, 1) > w) s = s.substring(0, s.length() - 1);
        return s.trim() + "...";
    }

    static String time(float sec) {
        int t = Math.max(0, Math.round(sec));
        return t / 60 + ":" + (t % 60 < 10 ? "0" : "") + t % 60;
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        lastInput = SystemClock.uptimeMillis();
        switch (k) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (down) control("toggle");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down) control("play");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down) control("pause");
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (down) control("next");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (down) control("prev");
                return true;
        }
        if (typePanel.getVisibility() == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK) {
                if (!down) closeTypePanel();
                return true;
            }
            return super.dispatchKeyEvent(e);              // the keyboard and the text box
        }
        if (mode == WEB) {
            if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && !down) {
                boolean r = super.dispatchKeyEvent(e);
                ui.postDelayed(() -> maybeType(true), 250);
                return r;
            }
            if (k == KeyEvent.KEYCODE_BACK) {
                if (!down) {
                    if (popup != null && popup.canGoBack()) popup.goBack();
                    else setMode(BROWSE);                      // back out of sign-in
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (!navigation(k)) return super.dispatchKeyEvent(e);   // volume, home, etc. behave as usual
        if (mode == NOW) {
            boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (ok) {                                      // tap: play/pause · hold: love it
                hold.key(e);
                px.now();
                return true;
            }
            if (down && e.getRepeatCount() == 0) {
                switch (k) {
                    case KeyEvent.KEYCODE_DPAD_DOWN:      // the song → just the lyrics → up next
                        npView = (npView + 1) % 3;
                        if (npView == 2) fetchUpNext();
                        break;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        control("prev");
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        control("next");
                        break;
                    case KeyEvent.KEYCODE_BACK:              // one step out: to where you came from
                        if (nowFromOutside) leave();
                        nowFromOutside = false;
                        setMode(BROWSE);
                        break;
                    default:
                        setMode(BROWSE);
                }
                px.now();
            }
            return true;
        }
        if (down) {
            px.poke();
            browse.key(k);
            px.now();
        }
        return true;
    }

    static boolean navigation(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
                return true;
        }
        return false;
    }
}
