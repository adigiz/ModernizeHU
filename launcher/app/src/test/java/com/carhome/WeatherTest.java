package com.carhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class WeatherTest {

    @Test
    public void dropsAdministrativeTitles() {
        assertEquals("Jakarta", Weather.shortPlace("Daerah Khusus Ibukota Jakarta"));
        assertEquals("Jakarta", Weather.shortPlace("Daerah Khusus Jakarta"));
        assertEquals("Yogyakarta", Weather.shortPlace("Daerah Istimewa Yogyakarta"));
        assertEquals("Jakarta Timur", Weather.shortPlace("Kota Administrasi Jakarta Timur"));
        assertEquals("Bogor", Weather.shortPlace("Kabupaten Bogor"));
        assertEquals("Bekasi", Weather.shortPlace("Kota Bekasi"));
    }

    @Test
    public void keepsPlainNames() {
        assertEquals("Padang", Weather.shortPlace("Padang"));
        assertEquals("Kota", Weather.shortPlace("Kota"));
        assertEquals("Kotabaru", Weather.shortPlace("Kotabaru"));
        assertNull(Weather.shortPlace(null));
    }

    @Test
    public void describesWeatherCodes() {
        assertEquals("Clear", Weather.describe(0));
        assertEquals("Drizzle", Weather.describe(51));
        assertEquals("Heavy rain", Weather.describe(82));
        assertEquals("Thunderstorm", Weather.describe(95));
    }
}
