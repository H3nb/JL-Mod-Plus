/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package javax.microedition.lcdui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

public class DateFieldValueContractTest {
    @Test
    public void constructorStartsUninitializedAndRejectsInvalidModesWithoutMutation() {
        DateField field = new DateField("Date", DateField.DATE);
        assertNull(field.getDate());
        assertThrows(IllegalArgumentException.class, () -> new DateField("Invalid", 0));
        assertThrows(IllegalArgumentException.class, () -> field.setInputMode(4));
        assertEquals(DateField.DATE, field.getInputMode());
        assertNull(field.getDate());
    }

    @Test
    public void dateModeTruncatesClockInTheConfiguredZoneAndSupportsClearing() {
        TimeZone zone = TimeZone.getTimeZone("GMT+08:00");
        DateField field = new DateField(null, DateField.DATE, zone);
        Calendar clock = Calendar.getInstance(zone);
        clock.clear();
        clock.set(2024, Calendar.NOVEMBER, 9, 19, 42, 31);
        field.setDate(clock.getTime());

        clock.set(Calendar.HOUR_OF_DAY, 0);
        clock.set(Calendar.MINUTE, 0);
        clock.set(Calendar.SECOND, 0);
        assertEquals(clock.getTime(), field.getDate());

        field.setDate(null);
        assertNull(field.getDate());
        field.setDate(clock.getTime());
        assertEquals(clock.getTime(), field.getDate());
    }

    @Test
    public void timeModeUsesOneEpochDayAndMinutePrecision() {
        TimeZone zone = TimeZone.getTimeZone("GMT+08:00");
        DateField field = new DateField(null, DateField.TIME, zone);
        assertNull(field.getDate());
        field.setDate(new Date(3_723_500L));
        assertEquals(new Date(3_720_000L), field.getDate());
        field.setDate(new Date(86_400_000L));
        assertNull(field.getDate());
        field.setDate(new Date(-1L));
        assertNull(field.getDate());
        field.setDate(new Date(0));
        assertEquals(new Date(0), field.getDate());
    }

    @Test
    public void changingModeNormalizesTheLogicalValueWithoutInitializingAnEmptyField() {
        DateField field = new DateField(null, DateField.DATE_TIME, TimeZone.getTimeZone("GMT"));
        field.setInputMode(DateField.TIME);
        assertNull(field.getDate());
        field.setInputMode(DateField.DATE_TIME);
        Calendar clock = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        clock.clear();
        clock.set(2024, Calendar.NOVEMBER, 9, 14, 22, 30);
        field.setDate(clock.getTime());
        clock.set(Calendar.SECOND, 0);
        assertEquals(clock.getTime(), field.getDate());
        field.setInputMode(DateField.DATE);
        clock.set(Calendar.HOUR_OF_DAY, 0);
        clock.set(Calendar.MINUTE, 0);
        assertEquals(clock.getTime(), field.getDate());
        field.setInputMode(DateField.TIME);
        assertEquals(new Date(0), field.getDate());
    }
}
