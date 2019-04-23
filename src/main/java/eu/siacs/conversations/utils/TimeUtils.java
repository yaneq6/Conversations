package eu.siacs.conversations.utils;

import java.util.Calendar;

public class TimeUtils {

    public final static long DEFAULT_VALUE = 0;


    public static Calendar minutesToCalender(long time) {
        final Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, (int) ((time % (24 * 60)) / 60));
        c.set(Calendar.MINUTE, (int) ((time % (24 * 60)) % 60));
        return c;
    }

    public static long minutesToTimestamp(long time) {
        return minutesToCalender(time).getTimeInMillis();
    }
}
