package tv.customhome.launcher.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class DashboardData {
    public static final ZoneId ZONE = ZoneId.of(DashboardProfile.TIMEZONE);

    public static final class PrayerDay {
        public final LocalDate date;
        public final List<LocalTime> times;

        public PrayerDay(LocalDate date, List<LocalTime> times) {
            if (times.size() != 5) throw new IllegalArgumentException("Five prayers required");
            this.date = date;
            this.times = Collections.unmodifiableList(new ArrayList<>(times));
        }
    }

    public static final class NextPrayer {
        public final int index;
        public final Instant at;

        NextPrayer(int index, Instant at) {
            this.index = index;
            this.at = at;
        }
    }

    public static final class Fixture {
        public final String id, home, away, competition;
        public final String homeLogo, awayLogo;
        public final Instant kickoff;
        public final boolean timeUnconfirmed;

        public Fixture(String id, String home, String away, String competition,
                       Instant kickoff, boolean timeUnconfirmed) {
            this(id, home, away, competition, kickoff, timeUnconfirmed, "", "");
        }

        public Fixture(String id, String home, String away, String competition,
                       Instant kickoff, boolean timeUnconfirmed, String homeLogo, String awayLogo) {
            this.id = id;
            this.home = home;
            this.away = away;
            this.competition = competition;
            this.kickoff = kickoff;
            this.timeUnconfirmed = timeUnconfirmed;
            this.homeLogo = homeLogo;
            this.awayLogo = awayLogo;
        }

        public String opponent() {
            return home.contains(DashboardProfile.TEAM_NAME) ? away : home;
        }
    }

    public static PrayerDay today(List<PrayerDay> days, Instant now) {
        LocalDate date = now.atZone(ZONE).toLocalDate();
        for (PrayerDay day : days) if (day.date.equals(date)) return day;
        return null;
    }

    public static NextPrayer nextPrayer(List<PrayerDay> days, Instant now) {
        NextPrayer best = null;
        for (PrayerDay day : days) {
            for (int i = 0; i < day.times.size(); i++) {
                Instant at = day.date.atTime(day.times.get(i)).atZone(ZONE).toInstant();
                if (at.isAfter(now) && (best == null || at.isBefore(best.at))) best = new NextPrayer(i, at);
            }
        }
        return best;
    }

    public static List<Fixture> upcoming(List<Fixture> fixtures, Instant now) {
        List<Fixture> result = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            boolean future = fixture.timeUnconfirmed
                    ? !fixture.kickoff.atZone(ZONE).toLocalDate().isBefore(now.atZone(ZONE).toLocalDate())
                    : fixture.kickoff.isAfter(now);
            if (future) result.add(fixture);
        }
        result.sort(Comparator.comparing(fixture -> fixture.kickoff));
        return result;
    }

    private DashboardData() { }
}
