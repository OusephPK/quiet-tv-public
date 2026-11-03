package com.quiet.px;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The real weather outside, so the sky over the well matches yours: rain when it's raining,
 * cloud when it's grey, mist, storms. Location comes from your internet address (ipwho.is) once a
 * week; the weather itself from Open-Meteo every half hour. No accounts, nothing stored but the
 * last reading. Test any weather with: adb shell setprop debug.quiet.weather 61   ('' to clear)
 */
public final class Weather {
    /** WMO weather code; -1 while unknown. */
    public static volatile int code = -1;
    public static volatile float cloud, temp = Float.NaN, wind;
    public static volatile long at;
    static volatile boolean fetching;
    static final long EVERY = 30 * 60_000L, STALE = 3 * 3600_000L;

    public static boolean known() {
        return debug() >= 0 || (code >= 0 && System.currentTimeMillis() - at < STALE);
    }

    static int debug() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class).invoke(null, "debug.quiet.weather");
            return v == null || v.isEmpty() ? -1 : Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int now() {
        int d = debug();
        return d >= 0 ? d : known() ? code : -1;
    }

    public static boolean rain() {
        int c = now();
        return (c >= 51 && c <= 67) || (c >= 80 && c <= 82) || c >= 95;
    }

    public static boolean heavy() {
        int c = now();
        return c == 63 || c == 65 || c == 67 || c == 81 || c == 82 || c >= 95;
    }

    public static boolean storm() {
        return now() >= 95;
    }

    public static boolean fog() {
        int c = now();
        return c == 45 || c == 48;
    }

    public static boolean snow() {
        int c = now();
        return (c >= 71 && c <= 77) || c == 85 || c == 86;
    }

    /** 0 clear .. 1 overcast. */
    public static float overcast() {
        int c = now();
        if (debug() >= 0) return c >= 3 ? 1f : c == 2 ? 0.55f : c == 1 ? 0.25f : 0f;
        if (!known()) return 0.3f;
        return Math.max(cloud, rain() || fog() ? 0.85f : 0f);
    }

    /** "27°", or "" if we don't know. */
    public static String temperature() {
        return known() && !Float.isNaN(temp) && debug() < 0 ? Math.round(temp) + "°" : "";
    }

    /** Read the last reading straight away, and fetch a fresh one if it's due. */
    public static void refresh(Context c) {
        SharedPreferences p = c.getSharedPreferences("quiet.weather", Context.MODE_PRIVATE);
        if (code < 0 && p.contains("code")) {
            code = p.getInt("code", -1);
            cloud = p.getFloat("cloud", 0);
            temp = p.getFloat("temp", Float.NaN);
            wind = p.getFloat("wind", 0);
            at = p.getLong("at", 0);
        }
        if (fetching || System.currentTimeMillis() - at < EVERY) return;
        fetching = true;
        new Thread(() -> {
            try {
                double lat, lon;
                if (System.currentTimeMillis() - p.getLong("placeAt", 0) > 7 * 86400_000L) {
                    JSONObject here = new JSONObject(get("https://ipwho.is/?fields=success,latitude,longitude"));
                    if (!here.optBoolean("success")) return;
                    lat = here.getDouble("latitude");
                    lon = here.getDouble("longitude");
                    p.edit().putFloat("lat", (float) lat).putFloat("lon", (float) lon)
                            .putLong("placeAt", System.currentTimeMillis()).apply();
                } else {
                    lat = p.getFloat("lat", 0);
                    lon = p.getFloat("lon", 0);
                }
                JSONObject cur = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code,cloud_cover,wind_speed_10m&timezone=auto")).getJSONObject("current");
                code = cur.getInt("weather_code");
                cloud = (float) cur.optDouble("cloud_cover", 0) / 100f;
                temp = (float) cur.optDouble("temperature_2m", Double.NaN);
                wind = (float) cur.optDouble("wind_speed_10m", 0);
                at = System.currentTimeMillis();
                p.edit().putInt("code", code).putFloat("cloud", cloud).putFloat("temp", temp).putFloat("wind", wind)
                        .putLong("at", at).apply();
                android.util.Log.i("QuietWeather", "code " + code + " cloud " + cloud + " " + temp + "°");
            } catch (Exception e) {
                android.util.Log.i("QuietWeather", "no weather: " + e);
            } finally {
                fetching = false;
            }
        }, "weather").start();
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private Weather() {}
}
