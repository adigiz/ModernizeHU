/*
 * Modified Java port of PrayTimes.js: Prayer Times Calculator (ver 2.5)
 * Copyright (C) 2007-2017 PrayTimes.org
 * Developer: Hamid Zarrabi-Zadeh
 * License: GNU LGPL v3.0, http://praytimes.org
 *
 * This file is distributed under the GNU LGPL v3.0, not the MIT License of the rest of the project.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY.
 */
package com.carhome;

/**
 * Kemenag RI prayer times: Subuh at 20°, Isya at 18°, Shafi'i Ashar, plus the ministry's 2-minute
 * ihtiyat.
 */
final class PrayerTimes {

    static final String[] NAMES = {"Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"};

    private static final double FAJR_ANGLE = 20;
    private static final double ISHA_ANGLE = 18;
    private static final double SUNSET_ANGLE = 0.833;
    private static final int IHTIYAT_MINUTES = 2;

    private PrayerTimes() {
    }

    /** Minutes after local midnight for Subuh, Dzuhur, Ashar, Maghrib and Isya. */
    static int[] minutes(int year, int month, int day, double lat, double lon, double utcOffsetHours) {
        double jd = julianDay(year, month, day) - lon / (15 * 24.0);
        double[] hours = {
                sunAngleTime(jd, lat, FAJR_ANGLE, 5 / 24.0, true),
                midDay(jd, 12 / 24.0),
                asrTime(jd, lat, 13 / 24.0),
                sunAngleTime(jd, lat, SUNSET_ANGLE, 18 / 24.0, false),
                sunAngleTime(jd, lat, ISHA_ANGLE, 18 / 24.0, false),
        };
        int[] result = new int[hours.length];
        for (int i = 0; i < hours.length; i++) {
            double local = hours[i] + utcOffsetHours - lon / 15;
            result[i] = (int) Math.round(fixHour(local) * 60) + IHTIYAT_MINUTES;
        }
        return result;
    }

    private static double julianDay(int year, int month, int day) {
        if (month <= 2) {
            year -= 1;
            month += 12;
        }
        double a = Math.floor(year / 100.0);
        double b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + day + b - 1524.5;
    }

    /** Declination [0] and equation of time [1] for a Julian day. */
    private static double[] sunPosition(double jd) {
        double d = jd - 2451545.0;
        double g = fixAngle(357.529 + 0.98560028 * d);
        double q = fixAngle(280.459 + 0.98564736 * d);
        double l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g));
        double e = 23.439 - 0.00000036 * d;
        double ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15;
        double eqt = q / 15 - fixHour(ra);
        double decl = darcsin(dsin(e) * dsin(l));
        return new double[] {decl, eqt};
    }

    private static double midDay(double jd, double dayPortion) {
        return fixHour(12 - sunPosition(jd + dayPortion)[1]);
    }

    private static double sunAngleTime(double jd, double lat, double angle, double dayPortion, boolean beforeNoon) {
        double decl = sunPosition(jd + dayPortion)[0];
        double noon = midDay(jd, dayPortion);
        double t = darccos((-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))) / 15;
        return noon + (beforeNoon ? -t : t);
    }

    private static double asrTime(double jd, double lat, double dayPortion) {
        double decl = sunPosition(jd + dayPortion)[0];
        double angle = -darccot(1 + dtan(Math.abs(lat - decl)));
        return sunAngleTime(jd, lat, angle, dayPortion, false);
    }

    private static double dsin(double d) {
        return Math.sin(Math.toRadians(d));
    }

    private static double dcos(double d) {
        return Math.cos(Math.toRadians(d));
    }

    private static double dtan(double d) {
        return Math.tan(Math.toRadians(d));
    }

    private static double darcsin(double x) {
        return Math.toDegrees(Math.asin(x));
    }

    private static double darccos(double x) {
        return Math.toDegrees(Math.acos(x));
    }

    private static double darctan2(double y, double x) {
        return Math.toDegrees(Math.atan2(y, x));
    }

    private static double darccot(double x) {
        return Math.toDegrees(Math.atan(1 / x));
    }

    private static double fixAngle(double a) {
        a = a - 360 * Math.floor(a / 360);
        return a < 0 ? a + 360 : a;
    }

    private static double fixHour(double h) {
        h = h - 24 * Math.floor(h / 24);
        return h < 0 ? h + 24 : h;
    }
}
