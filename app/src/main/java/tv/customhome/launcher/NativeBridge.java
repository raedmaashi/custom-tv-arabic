package tv.customhome.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import tv.customhome.launcher.dashboard.DashboardData;
import tv.customhome.launcher.dashboard.DashboardProfile;
import tv.customhome.launcher.dashboard.DashboardRepository;

public final class NativeBridge {
    private final Activity activity;
    private final DashboardRepository repository;
    private final Set<String> apps = ConcurrentHashMap.newKeySet();
    private final Map<String, String> inputs = new ConcurrentHashMap<>();
    private final Map<String, byte[]> icons = new ConcurrentHashMap<>();
    private static final String ICON_BASE = "https://appassets.androidplatform.net/app-icons/";
    private static final String[] PRAYERS = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};

    public NativeBridge(Activity activity, DashboardRepository repository) {
        this.activity = activity;
        this.repository = repository;
    }

    @JavascriptInterface public String listApps() {
        JSONArray result = new JSONArray();
        try {
            PackageManager manager = activity.getPackageManager();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> infos = manager.queryIntentActivities(query, 0);
            Set<String> homePackages = new HashSet<>();
            for (ResolveInfo home : manager.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0))
                homePackages.add(home.activityInfo.packageName);
            Set<String> seen = new HashSet<>();
            for (ResolveInfo info : infos) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(activity.getPackageName()) || homePackages.contains(pkg)
                        || !seen.add(pkg)) continue;
                apps.add(pkg);
                result.put(new JSONObject().put("id", pkg).put("name", info.loadLabel(manager).toString())
                        .put("icon", ICON_BASE + pkg + ".png"));
            }
            TvInputManager managerInput = (TvInputManager) activity.getSystemService(Activity.TV_INPUT_SERVICE);
            if (managerInput != null) for (TvInputInfo input : managerInput.getTvInputList()) {
                if (!input.isPassthroughInput()) continue;
                String id = "input." + Base64.encodeToString(input.getId().getBytes(StandardCharsets.UTF_8),
                        Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                inputs.put(id, input.getId());
                String name = input.loadLabel(activity).toString();
                result.put(new JSONObject().put("id", id).put("name", name)
                        .put("icon", "css/app-icons/hdmi.svg"));
            }
            result.put(new JSONObject().put("id", "system.settings").put("name", "إعدادات التلفزيون"));
        } catch (Exception ignored) { }
        return result.toString();
    }

    @JavascriptInterface public boolean launch(String id) {
        Intent intent;
        if ("system.settings".equals(id)) intent = new Intent(Settings.ACTION_SETTINGS);
        else if (inputs.containsKey(id)) intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(inputs.get(id)), "vnd.android.cursor.item/channel");
        else if (apps.contains(id)) {
            intent = activity.getPackageManager().getLeanbackLaunchIntentForPackage(id);
            if (intent == null) intent = activity.getPackageManager().getLaunchIntentForPackage(id);
        }
        else return false;
        if (intent == null) return false;
        final Intent launchIntent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.runOnUiThread(() -> {
            try { activity.startActivity(launchIntent); }
            catch (Exception ignored) { Toast.makeText(activity, R.string.launch_failed, Toast.LENGTH_SHORT).show(); }
        });
        return true;
    }

    @JavascriptInterface public String dashboard() {
        JSONObject result = new JSONObject();
        Instant now = Instant.now();
        DashboardRepository.Snapshot state = repository.snapshot();
        try {
            DashboardData.PrayerDay today = DashboardData.today(state.prayers, now);
            DashboardData.NextPrayer next = DashboardData.nextPrayer(state.prayers, now);
            JSONArray prayers = new JSONArray();
            for (int i = 0; i < PRAYERS.length; i++) prayers.put(new JSONObject().put("name", PRAYERS[i])
                    .put("time", today == null ? "—" : today.times.get(i).toString()));
            result.put("prayers", prayers).put("prayerReady", today != null)
                    .put("prayerUpdated", state.prayerUpdated).put("prayerError", state.prayerError);
            if (next != null && today != null) {
                result.put("nextPrayer", PRAYERS[next.index]).put("nextAt", next.at.toEpochMilli())
                        .put("tomorrow", !next.at.atZone(DashboardData.ZONE).toLocalDate().equals(today.date));
            }
            JSONArray matches = new JSONArray();
            List<DashboardData.Fixture> fixtures = DashboardData.upcoming(state.fixtures, now);
            for (DashboardData.Fixture fixture : fixtures.subList(0, Math.min(3, fixtures.size()))) {
                matches.put(new JSONObject().put("home", fixture.home).put("away", fixture.away)
                        .put("competition", fixture.competition).put("kickoff", fixture.kickoff.toEpochMilli())
                        .put("pending", fixture.timeUnconfirmed).put("homeLogo", fixture.homeLogo).put("awayLogo", fixture.awayLogo));
            }
            result.put("matches", matches).put("fixtureUpdated", state.fixtureUpdated).put("fixtureError", state.fixtureError)
                    .put("fetchedAt", now.toEpochMilli());
        } catch (Exception ignored) { }
        return result.toString();
    }

    public WebResourceResponse icon(String path) {
        String pkg = path.endsWith(".png") ? path.substring(0, path.length() - 4) : "";
        if (!apps.contains(pkg)) return null;
        byte[] data = icons.get(pkg);
        if (data == null) {
            try {
                PackageManager manager = activity.getPackageManager();
                Drawable drawable = manager.getApplicationBanner(pkg);
                boolean banner = drawable != null;
                if (drawable == null) drawable = manager.getApplicationIcon(pkg);
                Bitmap bitmap = Bitmap.createBitmap(banner ? 320 : 160, 180, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                int width = Math.max(1, drawable.getIntrinsicWidth()), height = Math.max(1, drawable.getIntrinsicHeight());
                float scale = Math.min((float) bitmap.getWidth() / width, (float) bitmap.getHeight() / height);
                int w = Math.round(width * scale), h = Math.round(height * scale);
                drawable.setBounds((bitmap.getWidth() - w) / 2, (180 - h) / 2, (bitmap.getWidth() + w) / 2, (180 + h) / 2);
                drawable.draw(canvas);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                bitmap.recycle();
                data = output.toByteArray();
                icons.put(pkg, data);
            } catch (Exception ignored) { return null; }
        }
        return new WebResourceResponse("image/png", null, 200, "OK",
                Collections.singletonMap("Cache-Control", "max-age=86400"), new ByteArrayInputStream(data));
    }

    public boolean isCrestHost(String host) {
        return "images.ctfassets.net".equals(host) || "contentfulproxy.stadion.io".equals(host) || "a.espncdn.com".equals(host);
    }
}
