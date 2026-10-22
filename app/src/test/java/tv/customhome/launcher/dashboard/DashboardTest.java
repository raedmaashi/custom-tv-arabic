package tv.customhome.launcher.dashboard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class DashboardTest {
    private DashboardData.PrayerDay day(String date) {
        return new DashboardData.PrayerDay(LocalDate.parse(date), Arrays.asList(
                LocalTime.of(5, 17), LocalTime.of(12, 18), LocalTime.of(15, 33),
                LocalTime.of(17, 58), LocalTime.of(19, 28)));
    }

    private Instant local(String value) {
        return java.time.LocalDateTime.parse(value).atZone(DashboardData.ZONE).toInstant();
    }

    @Test public void prayerAfterIshaUsesTomorrow() {
        DashboardData.NextPrayer next = DashboardData.nextPrayer(Arrays.asList(day("2026-10-21"), day("2026-10-22")),
                local("2026-10-21T21:00:00"));
        assertEquals(0, next.index);
        assertEquals(local("2026-10-22T05:17:00"), next.at);
    }

    @Test public void prayerCrossesMonthBoundary() {
        DashboardData.NextPrayer next = DashboardData.nextPrayer(Arrays.asList(day("2026-10-31"), day("2026-11-01")),
                local("2026-10-31T23:30:00"));
        assertEquals(local("2026-11-01T05:17:00"), next.at);
    }

    @Test public void dateUsesConfiguredTimezone() {
        DashboardData.PrayerDay today = DashboardData.today(Arrays.asList(day("2026-10-21"), day("2026-10-22")),
                local("2026-10-22T01:00:00"));
        assertEquals(LocalDate.parse("2026-10-22"), today.date);
    }

    @Test public void missingTomorrowDoesNotInventFajr() {
        assertNull(DashboardData.nextPrayer(Collections.singletonList(day("2026-10-21")),
                local("2026-10-21T21:00:00")));
    }

    @Test public void atExactPrayerTimeSelectsFollowingPrayer() {
        DashboardData.NextPrayer next = DashboardData.nextPrayer(Collections.singletonList(day("2026-10-21")),
                local("2026-10-21T12:18:00"));
        assertEquals(2, next.index);
    }

    @Test public void parsesMonthlyPrayerTimesWithOffsetSuffix() throws Exception {
        List<DashboardData.PrayerDay> days = DashboardParser.prayers(calendar().toString());
        assertEquals(28, days.size());
        assertEquals(LocalTime.of(5, 17), days.get(0).times.get(0));
    }

    @Test(expected = JSONException.class) public void rejectsIncompletePrayerCalendar() throws Exception {
        JSONObject data = calendar();
        data.getJSONArray("data").remove(0);
        DashboardParser.prayers(data.toString());
    }

    @Test(expected = JSONException.class) public void rejectsDuplicatePrayerDays() throws Exception {
        JSONObject data = calendar();
        JSONArray days = data.getJSONArray("data");
        days.put(1, days.get(0));
        DashboardParser.prayers(data.toString());
    }

    @Test(expected = JSONException.class) public void rejectsMisorderedPrayerTimes() throws Exception {
        JSONObject data = calendar();
        data.getJSONArray("data").getJSONObject(0).getJSONObject("timings").put("Asr", "11:00 (+03)");
        DashboardParser.prayers(data.toString());
    }

    @Test public void parsesSplitFlightChunksAndDeduplicates() throws Exception {
        JSONObject match = fixture("a", "2026-10-23T18:00:00.000Z", "$undefined", "PreMatch");
        String flight = "12:" + new JSONArray().put(match).put(match) + "\n";
        int split = flight.indexOf("fixtureProps") + 5;
        String html = chunk(flight.substring(0, split)) + chunk(flight.substring(split));
        List<DashboardData.Fixture> matches = DashboardParser.fixtures(html);
        assertEquals(1, matches.size());
        assertEquals("القادسية", matches.get(0).opponent());
        assertEquals(Instant.parse("2026-10-23T18:00:00Z"), matches.get(0).kickoff);
    }

    @Test public void unconfirmedMatchNeverAppearsAsMidnightKickoff() throws Exception {
        String html = chunk("12:" + new JSONArray().put(fixture("a", "2026-11-05T00:00:00Z", "DateAndTime", "PreMatch")) + "\n");
        assertTrue(DashboardParser.fixtures(html).get(0).timeUnconfirmed);
    }

    @Test public void cancelledFixturesAreNotUpcoming() throws Exception {
        JSONArray rows = new JSONArray().put(fixture("a", "2026-10-23T18:00:00Z", "$undefined", "Cancelled"))
                .put(fixture("b", "2026-10-27T16:15:00Z", "$undefined", "PreMatch"));
        List<DashboardData.Fixture> result = DashboardParser.fixtures(chunk("12:" + rows + "\n"));
        assertEquals(1, result.size());
        assertEquals("b", result.get(0).id);
    }

    @Test public void removesPastFixturesAndSortsUpcoming() {
        DashboardData.Fixture old = new DashboardData.Fixture("a", "القادسية", "الهلال", "الدوري",
                Instant.parse("2026-10-20T18:00:00Z"), false);
        DashboardData.Fixture future = new DashboardData.Fixture("b", "الهلال", "الفتح", "الدوري",
                Instant.parse("2026-10-31T18:00:00Z"), false);
        assertEquals("b", DashboardData.upcoming(Arrays.asList(future, old), local("2026-10-21T21:00:00")).get(0).id);
    }

    @Test(expected = JSONException.class) public void changedOrErrorPageDoesNotReplaceValidCache() throws Exception {
        DashboardParser.fixtures("<html>Temporarily unavailable</html>");
    }

    private JSONObject calendar() throws Exception {
        JSONArray days = new JSONArray();
        for (int i = 1; i <= 28; i++) {
            JSONObject timings = new JSONObject().put("Fajr", "05:17 (+03)").put("Dhuhr", "12:18 (+03)")
                    .put("Asr", "15:33 (+03)").put("Maghrib", "17:58 (+03)").put("Isha", "19:28 (+03)");
            days.put(new JSONObject().put("date", new JSONObject().put("gregorian",
                    new JSONObject().put("date", String.format(java.util.Locale.US, "%02d-02-2026", i)))).put("timings", timings));
        }
        return new JSONObject().put("code", 200).put("data", days);
    }

    private JSONObject fixture(String id, String date, String tbc, String status) throws Exception {
        JSONObject props = new JSONObject().put("id", id).put("matchStatus", status).put("leagueTitle", "دوري روشن السعودي")
                .put("home", new JSONObject().put("clubName", "القادسية"))
                .put("away", new JSONObject().put("clubName", DashboardProfile.TEAM_NAME));
        return new JSONObject().put("fixtureProps", props).put("kickOffUtc", date).put("kickOffTbc", tbc);
    }

    private String chunk(String text) { return "<script>self.__next_f.push([1," + JSONObject.quote(text) + "])</script>"; }

    @Test public void largePageChunkDoesNotOverflowTheStack() throws Exception {
        JSONObject match = fixture("a", "2026-10-23T18:00:00Z", "$undefined", "PreMatch");
        match.put("unrelatedPageContent", String.join("", Collections.nCopies(150_000, "x")));
        assertEquals(1, DashboardParser.fixtures(chunk("12:" + new JSONArray().put(match) + "\n")).size());
    }

    @Test(expected = JSONException.class) public void rejectsTwentyEightDaysForOctober() throws Exception {
        JSONObject data = calendar();
        JSONArray days = data.getJSONArray("data");
        for (int i = 0; i < days.length(); i++) {
            JSONObject date = days.getJSONObject(i).getJSONObject("date").getJSONObject("gregorian");
            date.put("date", date.getString("date").replace("-02-", "-10-"));
        }
        DashboardParser.prayers(data.toString());
    }
    @Test public void extractsClubCrestsFromOfficialHost() throws Exception {
        JSONObject match = fixture("a", "2026-10-23T18:00:00Z", "$undefined", "PreMatch");
        match.getJSONObject("fixtureProps").getJSONObject("home").put("clubCrest",
                new JSONArray().put(new JSONObject().put("url", "https://images.ctfassets.net/club/qadsiah.png")));
        DashboardData.Fixture result = DashboardParser.fixtures(chunk("12:" + new JSONArray().put(match) + "\n")).get(0);
        assertEquals("https://images.ctfassets.net/club/qadsiah.png", result.homeLogo);
        assertEquals("", result.awayLogo);
    }

    @Test public void rejectsUntrustedOrInsecureCrestUrls() throws Exception {
        for (String url : Arrays.asList("http://images.ctfassets.net/logo.png", "https://images.ctfassets.net.evil.test/logo.png", "javascript:alert(1)")) {
            JSONObject match = fixture("a", "2026-10-23T18:00:00Z", "$undefined", "PreMatch");
            match.getJSONObject("fixtureProps").getJSONObject("home").put("clubCrest", new JSONObject().put("url", url));
            assertEquals("", DashboardParser.fixtures(chunk("12:" + new JSONArray().put(match) + "\n")).get(0).homeLogo);
        }
    }
}
