package com.carhome;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Expected values are the Aladhan API's Kemenag times (method 20) plus Kemenag's 2-minute ihtiyat,
 * which is also what a head unit in Padang showed on 24 October 2026.
 */
public class PrayerTimesTest {

    private static void assertTimes(int[] actual, String... expected) {
        for (int i = 0; i < expected.length; i++) {
            String[] hm = expected[i].split(":");
            int want = Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
            assertTrue(PrayerTimes.NAMES[i] + ": got " + actual[i] / 60 + ":" + actual[i] % 60 + ", want " + expected[i],
                    Math.abs(actual[i] - want) <= 1);
        }
    }

    @Test
    public void padangInOctober() {
        assertTimes(PrayerTimes.minutes(2026, 10, 26, -0.95, 100.35, 7),
                "04:42", "12:05", "15:23", "18:09", "19:19");
    }

    @Test
    public void padangInJune() {
        assertTimes(PrayerTimes.minutes(2026, 6, 21, -0.95, 100.35, 7),
                "04:57", "12:22", "15:48", "18:24", "19:39");
    }

    @Test
    public void jakartaInOctober() {
        assertTimes(PrayerTimes.minutes(2026, 10, 26, -6.2, 106.85, 7),
                "04:10", "11:39", "14:53", "17:48", "18:59");
    }
}
