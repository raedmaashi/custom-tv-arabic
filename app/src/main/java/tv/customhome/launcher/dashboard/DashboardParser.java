package tv.customhome.launcher.dashboard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public final class DashboardParser {
    private static final String[] PRAYERS = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final String FLIGHT_PREFIX = "self.__next_f.push([1,";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT);

    public static List<DashboardData.PrayerDay> prayers(String body) throws JSONException {
        JSONObject response = new JSONObject(body);
        if (response.optInt("code") != 200) throw new JSONException("Prayer service error");
        JSONArray data = response.getJSONArray("data");
        if (data.length() < 28 || data.length() > 31) throw new JSONException("Incomplete calendar");
        List<DashboardData.PrayerDay> result = new ArrayList<>();
        Set<LocalDate> seen = new HashSet<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject day = data.getJSONObject(i);
            LocalDate date = LocalDate.parse(day.getJSONObject("date").getJSONObject("gregorian").getString("date"), DATE);
            List<LocalTime> times = new ArrayList<>();
            for (String prayer : PRAYERS) {
                String time = day.getJSONObject("timings").getString(prayer);
                if (!time.matches("\\d{2}:\\d{2}(?: .*)?")) throw new JSONException("Invalid prayer time");
                LocalTime parsed = LocalTime.parse(time.substring(0, 5));
                if (!times.isEmpty() && !parsed.isAfter(times.get(times.size() - 1))) throw new JSONException("Prayer order");
                times.add(parsed);
            }
            if (!seen.add(date)) throw new JSONException("Duplicate prayer date");
            result.add(new DashboardData.PrayerDay(date, times));
        }
        YearMonth month = YearMonth.from(result.get(0).date);
        if (result.size() != month.lengthOfMonth()) throw new JSONException("Incomplete calendar month");
        for (DashboardData.PrayerDay day : result) {
            if (!YearMonth.from(day.date).equals(month)) throw new JSONException("Mixed calendar months");
        }
        return result;
    }

    public static List<DashboardData.Fixture> fixtures(String html) throws JSONException {
        StringBuilder flight = new StringBuilder();
        int cursor = 0;
        while ((cursor = html.indexOf(FLIGHT_PREFIX, cursor)) >= 0) {
            int start = cursor + FLIGHT_PREFIX.length();
            cursor = start;
            if (start >= html.length() || html.charAt(start) != '"') continue;
            int end = start + 1;
            while (end < html.length()) {
                char character = html.charAt(end);
                if (character == '\\') { end += 2; continue; }
                if (character == '"') break;
                end++;
            }
            if (end >= html.length()) throw new JSONException("Incomplete fixture chunk");
            flight.append(new JSONArray("[" + html.substring(start, end + 1) + "]").getString(0));
            cursor = end + 1;
        }
        List<DashboardData.Fixture> fixtures = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int[] visited = {0};
        for (String line : flight.toString().split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0 || !line.contains("\"fixtureProps\"")) continue;
            String json = line.substring(colon + 1);
            if (!json.startsWith("[")) continue;
            walk(new JSONTokener(json).nextValue(), fixtures, seen, visited, 0);
        }
        if (fixtures.isEmpty()) throw new JSONException("No recognizable Al Hilal fixtures");
        return fixtures;
    }

    public static List<DashboardData.Fixture> espnFixtures(String body, String teamId, String teamName) throws JSONException {
        JSONArray events = new JSONObject(body).getJSONArray("events");
        List<DashboardData.Fixture> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            JSONArray competitions = event.optJSONArray("competitions");
            if (competitions == null) throw new JSONException("Missing competitions");
            for (int j = 0; j < competitions.length(); j++) {
                JSONObject competition = competitions.getJSONObject(j);
                JSONObject status = competition.optJSONObject("status");
                JSONObject type = status == null ? null : status.optJSONObject("type");
                if (type == null || !"pre".equals(type.optString("state"))) continue;
                JSONArray competitors = competition.getJSONArray("competitors");
                JSONObject home = null, away = null;
                for (int k = 0; k < competitors.length(); k++) {
                    JSONObject competitor = competitors.getJSONObject(k);
                    if ("home".equals(competitor.optString("homeAway"))) home = competitor.getJSONObject("team");
                    if ("away".equals(competitor.optString("homeAway"))) away = competitor.getJSONObject("team");
                }
                if (home == null || away == null) continue;
                if (!teamId.equals(home.optString("id")) && !teamId.equals(away.optString("id"))) continue;
                String id = event.getString("id");
                if (!seen.add(id)) continue;
                String name = type.optString("name");
                if (name.contains("CANCELLED") || name.contains("CANCELED") || name.contains("ABANDONED")) continue;
                boolean pending = !event.optBoolean("timeValid", true) || !competition.optBoolean("timeValid", true)
                        || name.contains("POSTPONED") || name.contains("TBD");
                JSONObject season = event.optJSONObject("seasonType");
                String league = season == null ? "" : season.optString("name");
                try {
                    Instant kickoff = java.time.OffsetDateTime.parse(competition.optString("date", event.optString("date"))).toInstant();
                    result.add(new DashboardData.Fixture(id,
                            teamId.equals(home.optString("id")) ? teamName : home.getString("displayName"),
                            teamId.equals(away.optString("id")) ? teamName : away.getString("displayName"), league, kickoff, pending,
                            crest(home.opt("logos"), 0), crest(away.opt("logos"), 0)));
                } catch (java.time.DateTimeException error) { throw new JSONException("Invalid kickoff date"); }
            }
        }
        return result;
    }

    private static void walk(Object value, List<DashboardData.Fixture> fixtures, Set<String> seen,
                             int[] visited, int depth) throws JSONException {
        if (++visited[0] > 80_000 || depth > 48) throw new JSONException("Fixture page exceeds limits");
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) walk(array.get(i), fixtures, seen, visited, depth + 1);
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONObject fixture = object.optJSONObject("fixtureProps");
            if (fixture != null) {
                String id = fixture.optString("id");
                String status = fixture.optString("matchStatus");
                String date = object.optString("kickOffUtc");
                JSONObject home = fixture.optJSONObject("home"), away = fixture.optJSONObject("away");
                if (home == null || away == null || !home.has("clubName") || !away.has("clubName")) return;
                String homeName = home.getString("clubName").trim(), awayName = away.getString("clubName").trim();
                boolean hilal = homeName.contains(DashboardProfile.TEAM_NAME) || awayName.contains(DashboardProfile.TEAM_NAME);
                if (hilal && !id.isEmpty() && !date.isEmpty() && "PreMatch".equals(status) && seen.add(id)) {
                    String tbc = object.optString("kickOffTbc");
                    boolean pending = !tbc.isEmpty() && !"$undefined".equals(tbc) && !"null".equals(tbc);
                    try {
                        fixtures.add(new DashboardData.Fixture(id, homeName, awayName,
                                fixture.optString("leagueTitle"), Instant.parse(date), pending,
                                crest(home.opt("clubCrest"), 0), crest(away.opt("clubCrest"), 0)));
                    } catch (java.time.DateTimeException ignored) { }
                }
                return;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) walk(object.get(keys.next()), fixtures, seen, visited, depth + 1);
        }
    }

    private DashboardParser() { }

    private static String crest(Object node, int depth) throws JSONException {
        if (depth > 8) return "";
        if (node instanceof JSONObject) {
            String url = ((JSONObject) node).optString("url", ((JSONObject) node).optString("href"));
            if (url.startsWith("https://images.ctfassets.net/") || url.startsWith("https://contentfulproxy.stadion.io/") || url.startsWith("https://a.espncdn.com/")) return url;
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = crest(array.get(i), depth + 1);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }
}
