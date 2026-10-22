package tv.customhome.launcher.dashboard;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.Assert.*;

public class EspnTest {
    private JSONObject event(String id, String state, String status) throws Exception {
        JSONObject home = new JSONObject().put("id", "817").put("displayName", "Al Nassr")
                .put("logos", new JSONArray().put(new JSONObject().put("href", "https://a.espncdn.com/i/teamlogos/soccer/500/817.png")));
        JSONObject away = new JSONObject().put("id", "793").put("displayName", "Al Shabab");
        JSONObject competition = new JSONObject().put("date", "2026-11-01T14:00Z")
                .put("status", new JSONObject().put("type", new JSONObject().put("state", state).put("name", status)))
                .put("competitors", new JSONArray()
                        .put(new JSONObject().put("homeAway", "away").put("team", away))
                        .put(new JSONObject().put("homeAway", "home").put("team", home)));
        return new JSONObject().put("id", id).put("date", "2026-11-01T14:00Z")
                .put("seasonType", new JSONObject().put("name", "Saudi Pro League"))
                .put("competitions", new JSONArray().put(competition));
    }
    private List<DashboardData.Fixture> parse(JSONObject... events) throws Exception {
        JSONArray rows = new JSONArray();
        for (JSONObject event : events) rows.put(event);
        return DashboardParser.espnFixtures(new JSONObject().put("events", rows).toString(), "817", "النصر");
    }
    @Test public void upcomingUsesIdsHomeAwayAndHttpsCrests() throws Exception {
        DashboardData.Fixture match = parse(event("1", "pre", "STATUS_SCHEDULED")).get(0);
        assertEquals("النصر", match.home);
        assertEquals("Al Shabab", match.away);
        assertEquals(Instant.parse("2026-11-01T14:00:00Z"), match.kickoff);
        assertTrue(match.homeLogo.startsWith("https://a.espncdn.com/"));
        assertFalse(match.timeUnconfirmed);
    }
    @Test public void completedLiveCancelledAndOtherTeamsAreExcluded() throws Exception {
        JSONObject other = event("other", "pre", "STATUS_SCHEDULED");
        other.getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors").getJSONObject(1).getJSONObject("team").put("id", "999");
        assertTrue(parse(event("1", "post", "STATUS_FULL_TIME"), event("2", "in", "STATUS_IN_PROGRESS"),
                event("3", "pre", "STATUS_CANCELED"), other).isEmpty());
    }
    @Test public void duplicateIdsDoNotProduceDuplicateCards() throws Exception {
        JSONObject match = event("1", "pre", "STATUS_SCHEDULED");
        assertEquals(1, parse(match, match).size());
    }
    @Test public void unconfirmedAndPostponedDoNotClaimConfirmedTimes() throws Exception {
        JSONObject match = event("1", "pre", "STATUS_SCHEDULED").put("timeValid", false);
        assertTrue(parse(match).get(0).timeUnconfirmed);
        assertTrue(parse(event("2", "pre", "STATUS_POSTPONED")).get(0).timeUnconfirmed);
    }
    @Test public void emptyScheduleIsValidButErrorDocumentIsNot() throws Exception {
        assertTrue(parse().isEmpty());
        try { DashboardParser.espnFixtures("{\"error\":\"unavailable\"}", "817", "النصر"); fail(); }
        catch (JSONException expected) { }
    }
    @Test public void unsafeCrestIsDroppedWithoutLosingMatch() throws Exception {
        JSONObject match = event("1", "pre", "STATUS_SCHEDULED");
        match.getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors").getJSONObject(1)
                .getJSONObject("team").getJSONArray("logos").getJSONObject(0).put("href", "https://a.espncdn.com.evil.test/logo.png");
        assertEquals("", parse(match).get(0).homeLogo);
    }
}
