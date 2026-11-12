package com.fireframe.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

// Fetches the current weather from Open-Meteo (https://open-meteo.com/) and caches it.
// Polled at most once every 30 minutes.
public class Weather {
    static final long POLL_INTERVAL = 30 * 60 * 1000L; // 30 minutes
    static final String PREF_LAST_FETCH = "weather_last_fetch";
    static final String PREF_TEXT = "weather_text";
    static final String PREF_LAT = "weather_lat";
    static final String PREF_LON = "weather_lon";
    static final String PREF_UNIT = "weather_unit";

    static String cachedText;
    static boolean fetching;

    public interface Callback {
        void onWeather(String text);
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("fireframe", Context.MODE_PRIVATE);
    }

    static String cached(Context c) {
        if (cachedText != null) return cachedText;
        cachedText = prefs(c).getString(PREF_TEXT, "");
        return cachedText;
    }

    static void setLocation(Context c, String lat, String lon) {
        prefs(c).edit()
                .putString(PREF_LAT, lat)
                .putString(PREF_LON, lon)
                .putLong(PREF_LAST_FETCH, 0)
                .apply();
    }

    static void setCity(Context c, String city) {
        new Thread(() -> {
            try {
                String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                        + URLEncoder.encode(city, "UTF-8") + "&count=1&format=json";
                JSONObject json = new JSONObject(read(url));
                JSONArray results = json.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject first = results.getJSONObject(0);
                    double lat = first.getDouble("latitude");
                    double lon = first.getDouble("longitude");
                    setLocation(c, String.valueOf(lat), String.valueOf(lon));
                }
            } catch (Exception ignored) {}
        }).start();
    }

    static void setUnit(Context c, String unit) {
        prefs(c).edit()
                .putString(PREF_UNIT, unit)
                .putLong(PREF_LAST_FETCH, 0)
                .apply();
    }

    static boolean isFahrenheit(SharedPreferences p) {
        String u = p.getString(PREF_UNIT, null);
        if (u != null) return u.toLowerCase().startsWith("f");
        String country = Locale.getDefault().getCountry();
        return "US".equalsIgnoreCase(country) || "BS".equalsIgnoreCase(country)
                || "BZ".equalsIgnoreCase(country) || "KY".equalsIgnoreCase(country);
    }

    // Fetches weather if at least 30 minutes have elapsed since last fetch, or if force is true.
    static void update(final Context c, final boolean force, final Callback callback) {
        final SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        long last = p.getLong(PREF_LAST_FETCH, 0);

        if (!force && last > 0 && (now - last) < POLL_INTERVAL) {
            String existing = cached(c);
            if (callback != null && existing != null && !existing.isEmpty()) {
                callback.onWeather(existing);
            }
            return;
        }

        if (fetching) return;
        fetching = true;

        new Thread(() -> {
            String result = null;
            try {
                String lat = p.getString(PREF_LAT, null);
                String lon = p.getString(PREF_LON, null);

                if (lat == null || lon == null) {
                    // Auto-resolve coordinates by IP
                    try {
                        JSONObject geo = new JSONObject(read("https://ipwho.is/"));
                        if (geo.optBoolean("success", false)) {
                            lat = String.valueOf(geo.getDouble("latitude"));
                            lon = String.valueOf(geo.getDouble("longitude"));
                            p.edit().putString(PREF_LAT, lat).putString(PREF_LON, lon).apply();
                        }
                    } catch (Exception ignored) {}

                    if (lat == null || lon == null) {
                        try {
                            JSONObject geo = new JSONObject(read("https://ipapi.co/json/"));
                            if (geo.has("latitude") && geo.has("longitude")) {
                                lat = String.valueOf(geo.getDouble("latitude"));
                                lon = String.valueOf(geo.getDouble("longitude"));
                                p.edit().putString(PREF_LAT, lat).putString(PREF_LON, lon).apply();
                            }
                        } catch (Exception ignored) {}
                    }
                }

                if (lat != null && lon != null) {
                    boolean fahrenheit = isFahrenheit(p);
                    String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                            + "&current=temperature_2m,weather_code" + (fahrenheit ? "&temperature_unit=fahrenheit" : "");
                    JSONObject weatherJson = new JSONObject(read(url));
                    JSONObject current = weatherJson.optJSONObject("current");
                    if (current == null) current = weatherJson.optJSONObject("current_weather");
                    if (current != null) {
                        double temp = current.optDouble("temperature_2m", current.optDouble("temperature", Double.NaN));
                        if (!Double.isNaN(temp)) {
                            int code = current.optInt("weather_code", current.optInt("weathercode", -1));
                            String cond = condition(code);
                            result = Math.round(temp) + "°" + (cond.isEmpty() ? "" : "  ·  " + cond);
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                fetching = false;
            }

            final String weatherText = result;
            if (weatherText != null && !weatherText.isEmpty()) {
                cachedText = weatherText;
                p.edit().putLong(PREF_LAST_FETCH, System.currentTimeMillis())
                        .putString(PREF_TEXT, weatherText)
                        .apply();
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> callback.onWeather(weatherText));
                }
            }
        }).start();
    }

    static long nextPollDelay(Context c) {
        long last = prefs(c).getLong(PREF_LAST_FETCH, 0);
        long elapsed = System.currentTimeMillis() - last;
        if (elapsed >= POLL_INTERVAL || last == 0) return 0;
        return POLL_INTERVAL - elapsed;
    }

    static String condition(int code) {
        switch (code) {
            case 0: return "Clear sky";
            case 1: return "Mainly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: return "Fog";
            case 48: return "Depositing rime fog";
            case 51: return "Light drizzle";
            case 53: return "Moderate drizzle";
            case 55: return "Dense drizzle";
            case 56: return "Light freezing drizzle";
            case 57: return "Dense freezing drizzle";
            case 61: return "Slight rain";
            case 63: return "Moderate rain";
            case 65: return "Heavy rain";
            case 66: return "Light freezing rain";
            case 67: return "Heavy freezing rain";
            case 71: return "Slight snow";
            case 73: return "Moderate snow";
            case 75: return "Heavy snow";
            case 77: return "Snow grains";
            case 80: return "Slight rain showers";
            case 81: return "Moderate rain showers";
            case 82: return "Violent rain showers";
            case 85: return "Slight snow showers";
            case 86: return "Heavy snow showers";
            case 95: return "Thunderstorm";
            case 96: return "Thunderstorm with hail";
            case 99: return "Thunderstorm with heavy hail";
            default: return "";
        }
    }

    static String read(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestProperty("User-Agent", "FireFrame");
        h.setConnectTimeout(15000);
        h.setReadTimeout(15000);
        if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
