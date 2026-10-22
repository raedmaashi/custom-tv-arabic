package tv.customhome.launcher.dashboard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

public final class DashboardRepository {
    public static final String FIXTURE_URL = "https://alhilal.com/ar/matches/mens-team/fixtures";
    private static final long HOUR = 3_600_000L;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private final File directory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile Snapshot snapshot = new Snapshot();
    private volatile Runnable listener;
    private long lastAttempt;

    public static final class Snapshot {
        public List<DashboardData.PrayerDay> prayers = Collections.emptyList();
        public List<DashboardData.Fixture> fixtures = Collections.emptyList();
        public long prayerUpdated, fixtureUpdated;
        public boolean prayerError, fixtureError;
    }

    public DashboardRepository(Context context) {
        directory = new File(context.getFilesDir(), "dashboard");
    }

    public Snapshot snapshot() { return snapshot; }
    public void listen(Runnable listener) { this.listener = listener; }

    public void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if ((now >= lastAttempt && now - lastAttempt < 600_000L) || !loading.compareAndSet(false, true)) return;
        lastAttempt = now;
        worker.execute(() -> {
            try { refresh(); }
            finally { loading.set(false); }
        });
    }

    private void refresh() {
        Snapshot state = new Snapshot();
        LocalDate today = Instant.now().atZone(DashboardData.ZONE).toLocalDate();
        List<YearMonth> months = new ArrayList<>();
        months.add(YearMonth.from(today));
        if (today.getDayOfMonth() >= today.lengthOfMonth() - 1) months.add(YearMonth.from(today).plusMonths(1));
        List<DashboardData.PrayerDay> prayers = new ArrayList<>();
        for (YearMonth month : months) {
            Cache cached = read(prayerKey(month));
            if (cached != null) {
                try {
                    prayers.addAll(DashboardParser.prayers(cached.body));
                    state.prayerUpdated = Math.max(state.prayerUpdated, cached.at);
                } catch (Exception ignored) { }
            }
        }
        state.prayers = Collections.unmodifiableList(prayers);
        List<String> feeds = "alhilal".equals(DashboardProfile.PROVIDER)
                ? Collections.singletonList("official") : Arrays.asList(DashboardProfile.LEAGUES);
        Map<String, List<DashboardData.Fixture>> loaded = new LinkedHashMap<>();
        Map<String, Long> updated = new LinkedHashMap<>();
        for (String feed : feeds) {
            Cache cache = read(fixtureKey(feed));
            if (cache != null) try {
                loaded.put(feed, parseFixtures(cache.body));
                updated.put(feed, cache.at);
            } catch (Exception ignored) { }
        }
        mergeFixtures(state, loaded, updated);
        publish(state);

        for (YearMonth month : months) {
            Cache cached = read(prayerKey(month));
            if (cached != null && System.currentTimeMillis() - cached.at < 24 * HOUR
                    && state.prayers.stream().anyMatch(day -> YearMonth.from(day.date).equals(month))) continue;
            try {
                String body = download("https://api.aladhan.com/v1/calendar/" + month.getYear() + "/" + month.getMonthValue()
                        + "?latitude=" + DashboardProfile.LATITUDE + "&longitude=" + DashboardProfile.LONGITUDE
                        + "&method=" + DashboardProfile.PRAYER_METHOD + "&school=0&timezonestring="
                        + URLEncoder.encode(DashboardProfile.TIMEZONE, "UTF-8"));
                List<DashboardData.PrayerDay> incoming = DashboardParser.prayers(body);
                if (incoming.stream().anyMatch(day -> !YearMonth.from(day.date).equals(month))) {
                    throw new IllegalArgumentException("Unexpected calendar month");
                }
                List<DashboardData.PrayerDay> merged = new ArrayList<>(state.prayers);
                merged.removeIf(day -> YearMonth.from(day.date).equals(month));
                merged.addAll(incoming);
                state.prayers = Collections.unmodifiableList(merged);
                state.prayerUpdated = System.currentTimeMillis();
                write(prayerKey(month), body, state.prayerUpdated);
            } catch (Exception ignored) { state.prayerError = true; }
        }
        publish(state);
        for (String feed : feeds) {
            Cache cached = read(fixtureKey(feed));
            if (cached != null && loaded.containsKey(feed) && System.currentTimeMillis() - cached.at < 3 * HOUR) continue;
            try {
                String url = "alhilal".equals(DashboardProfile.PROVIDER) ? FIXTURE_URL
                        : "https://site.api.espn.com/apis/site/v2/sports/soccer/" + feed + "/teams/" + DashboardProfile.TEAM_ID + "/schedule?fixture=true";
                String body = download(url);
                loaded.put(feed, parseFixtures(body));
                long now = System.currentTimeMillis();
                updated.put(feed, now);
                write(fixtureKey(feed), body, now);
            } catch (Exception ignored) { state.fixtureError = true; }
            mergeFixtures(state, loaded, updated);
            publish(state);
        }
        File[] old = directory.listFiles();
        if (old != null) for (File file : old) {
            if (file.getName().startsWith("prayer-") && System.currentTimeMillis() - file.lastModified() > 65 * 24 * HOUR) file.delete();
        }
    }

    private String prayerKey(YearMonth month) {
        return "prayer-" + Integer.toHexString(Objects.hash(DashboardProfile.LATITUDE, DashboardProfile.LONGITUDE,
                DashboardProfile.TIMEZONE, DashboardProfile.PRAYER_METHOD)) + "-" + month;
    }

    private String fixtureKey(String feed) {
        return "fixtures-" + DashboardProfile.PROVIDER + "-" + DashboardProfile.TEAM_ID + "-" + feed;
    }

    private List<DashboardData.Fixture> parseFixtures(String body) throws Exception {
        return "alhilal".equals(DashboardProfile.PROVIDER) ? DashboardParser.fixtures(body)
                : DashboardParser.espnFixtures(body, DashboardProfile.TEAM_ID, DashboardProfile.TEAM_NAME);
    }

    private void mergeFixtures(Snapshot state, Map<String, List<DashboardData.Fixture>> loaded, Map<String, Long> updated) {
        List<DashboardData.Fixture> result = new ArrayList<>();
        java.util.Set<String> seen = new HashSet<>();
        for (List<DashboardData.Fixture> rows : loaded.values()) for (DashboardData.Fixture row : rows)
            if (seen.add(row.id)) result.add(row);
        state.fixtures = Collections.unmodifiableList(result);
        state.fixtureUpdated = updated.isEmpty() ? 0 : Collections.min(updated.values());
    }

    private void publish(Snapshot state) {
        Snapshot copy = new Snapshot();
        copy.prayers = state.prayers;
        copy.fixtures = state.fixtures;
        copy.prayerUpdated = state.prayerUpdated;
        copy.fixtureUpdated = state.fixtureUpdated;
        copy.prayerError = state.prayerError;
        copy.fixtureError = state.fixtureError;
        snapshot = copy;
        main.post(() -> { Runnable callback = listener; if (callback != null) callback.run(); });
    }

    private Cache read(String name) {
        try {
            File file = new File(directory, name + ".json");
            if (file.length() > MAX_BYTES * 2) return null;
            byte[] bytes = new AtomicFile(file).readFully();
            if (bytes.length > MAX_BYTES * 2) return null;
            JSONObject object = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            long at = object.getLong("at");
            if (at <= 0 || at > System.currentTimeMillis() + 60_000) return null;
            return new Cache(object.getString("body"), at);
        } catch (Exception ignored) { return null; }
    }

    private void write(String name, String body, long at) {
        AtomicFile target = new AtomicFile(new File(directory, name + ".json"));
        FileOutputStream stream = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            stream = target.startWrite();
            stream.write(new JSONObject().put("at", at).put("body", body).toString().getBytes(StandardCharsets.UTF_8));
            target.finishWrite(stream);
        } catch (Exception ignored) { if (stream != null) target.failWrite(stream); }
    }

    private String download(String url) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "CustomTVAndroid/1.0 PersonalDashboard");
        connection.setRequestProperty("Accept", "text/html,application/json");
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("Source unavailable");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_BYTES) throw new IllegalStateException("Response too large");
                    output.write(buffer, 0, count);
                }
                return output.toString("UTF-8");
            }
        } finally { connection.disconnect(); }
    }

    public void close() { listener = null; worker.shutdownNow(); }

    private static final class Cache {
        final String body;
        final long at;
        Cache(String body, long at) { this.body = body; this.at = at; }
    }
}
