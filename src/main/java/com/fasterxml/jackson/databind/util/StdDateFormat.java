package com.fasterxml.jackson.databind.util;

import java.text.DateFormat;
import java.text.FieldPosition;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.io.NumberInput;

/**
 * Default {@link DateFormat} implementation used by standard Date
 * serializers and deserializers. For serialization defaults to using
 * an ISO-8601 compliant format (format String "yyyy-MM-dd'T'HH:mm:ss.SSSZ")
 * and for deserialization, both ISO-8601 and RFC-1123.
 */
@SuppressWarnings("serial")
public class StdDateFormat
    extends DateFormat
{
    /* 24-Jun-2017, tatu: Finally rewrote deserialization to use basic Regex
     *   instead of SimpleDateFormat; partly for better concurrency, partly
     *   for easier enforcing of specific rules. Heavy lifting done by Calendar,
     *   anyway.
     */

    protected final static String PATTERN_PLAIN_STR = "\\d\\d\\d\\d[-]\\d\\d[-]\\d\\d";

    protected final static Pattern PATTERN_PLAIN = Pattern.compile(PATTERN_PLAIN_STR);

    protected final static Pattern PATTERN_ISO8601;
    static {
        Pattern p = null;
        try {
            p = Pattern.compile(PATTERN_PLAIN_STR
                    +"[T]\\d\\d[:]\\d\\d(?:[:]\\d\\d)?" // hours, minutes, optional seconds
                    +"(\\.\\d+)?" // optional second fractions
                    +"(Z|[+-]\\d\\d(?:[:]?\\d\\d)?)?" // optional timeoffset/Z
            );
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        PATTERN_ISO8601 = p;
    }

    /**
     * Defines a commonly used date format that conforms
     * to ISO-8601 date formatting standard, when it includes basic undecorated
     * timezone definition.
     */
    public final static String DATE_FORMAT_STR_ISO8601 = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

    /**
     * ISO-8601 with just the Date part, no time: needed for error messages
     */
    protected final static String DATE_FORMAT_STR_PLAIN = "yyyy-MM-dd";

    /**
     * This constant defines the date format specified by
     * RFC 1123 / RFC 822. Used for parsing via `SimpleDateFormat` as well as
     * error messages.
     */
    protected final static String DATE_FORMAT_STR_RFC1123 = "EEE, dd MMM yyyy HH:mm:ss zzz";

    /**
     * For error messages we'll also need a list of all formats.
     */
    protected final static String[] ALL_FORMATS = new String[] {
        DATE_FORMAT_STR_ISO8601,
        "yyyy-MM-dd'T'HH:mm:ss.SSS", // ISO-8601 but no timezone
        DATE_FORMAT_STR_RFC1123,
        DATE_FORMAT_STR_PLAIN
    };

    /**
     * By default we use UTC for everything, with Jackson 2.7 and later
     * (2.6 and earlier relied on GMT)
     */
    protected final static TimeZone DEFAULT_TIMEZONE;
    static {
        DEFAULT_TIMEZONE = TimeZone.getTimeZone("UTC"); // since 2.7
    }

    protected final static Locale DEFAULT_LOCALE = Locale.US;

    protected final static DateFormat DATE_FORMAT_RFC1123;

    protected final static DateFormat DATE_FORMAT_ISO8601;

    /* Let's construct "blueprint" date format instances: can not be used
     * as is, due to thread-safety issues, but can be used for constructing
     * actual instances more cheaply (avoids re-parsing).
     */
    static {
        /* Another important thing: let's force use of default timezone for
         * baseline DataFormat objects
         */

        DATE_FORMAT_RFC1123 = new SimpleDateFormat(DATE_FORMAT_STR_RFC1123, DEFAULT_LOCALE);
        DATE_FORMAT_RFC1123.setTimeZone(DEFAULT_TIMEZONE);
        DATE_FORMAT_ISO8601 = new SimpleDateFormat(DATE_FORMAT_STR_ISO8601, DEFAULT_LOCALE);
        DATE_FORMAT_ISO8601.setTimeZone(DEFAULT_TIMEZONE);
    }
    
    /**
     * A singleton instance can be used for cloning purposes, as a blueprint of sorts.
     */
    public final static StdDateFormat instance = new StdDateFormat();
    
    /**
     * Caller may want to explicitly override timezone to use; if so,
     * we will have non-null value here.
     */
    protected transient TimeZone _timezone;

    protected final Locale _locale;

    /**
     * Explicit override for leniency, if specified.
     *<p>
     * Can not be `final` because {@link #setLenient(boolean)} returns
     * `void`.
     *
     * @since 2.7
     */
    protected Boolean _lenient;
    
    private transient DateFormat _formatRFC1123;
    private transient DateFormat _formatISO8601;

    /*
    /**********************************************************
    /* Life cycle, accessing singleton "standard" formats
    /**********************************************************
     */

    public StdDateFormat() {
        _locale = DEFAULT_LOCALE;
    }

    @Deprecated // since 2.7
    public StdDateFormat(TimeZone tz, Locale loc) {
        _timezone = tz;
        _locale = loc;
    }

    protected StdDateFormat(TimeZone tz, Locale loc, Boolean lenient) {
        _timezone = tz;
        _locale = loc;
        _lenient = lenient;
    }
    
    public static TimeZone getDefaultTimeZone() {
        return DEFAULT_TIMEZONE;
    }
    
    /**
     * Method used for creating a new instance with specified timezone;
     * if no timezone specified, defaults to the default timezone (UTC).
     */
    public StdDateFormat withTimeZone(TimeZone tz) {
        if (tz == null) {
            tz = DEFAULT_TIMEZONE;
        }
        if ((tz == _timezone) || tz.equals(_timezone)) {
            return this;
        }
        return new StdDateFormat(tz, _locale, _lenient);
    }

    public StdDateFormat withLocale(Locale loc) {
        if (loc.equals(_locale)) {
            return this;
        }
        return new StdDateFormat(_timezone, loc, _lenient);
    }

    /**
     * @since 2.9
     */
    public StdDateFormat withLenient(Boolean b) {
        if (_equals(b, _lenient)) {
            return this;
        }
        return new StdDateFormat(_timezone, _locale, b);
    }

    @Override
    public StdDateFormat clone() {
        // Although there is that much state to share, we do need to
        // orchestrate a bit, mostly since timezones may be changed
        return new StdDateFormat(_timezone, _locale, _lenient);
    }

    /**
     * @deprecated Since 2.4; use variant that takes Locale
     */
    @Deprecated
    public static DateFormat getISO8601Format(TimeZone tz) {
        return getISO8601Format(tz, DEFAULT_LOCALE);
    }

    /**
     * Method for getting a non-shared DateFormat instance
     * that uses specified timezone and can handle simple ISO-8601
     * compliant date format.
     * 
     * @since 2.4
     */
    public static DateFormat getISO8601Format(TimeZone tz, Locale loc) {
        return _cloneFormat(DATE_FORMAT_ISO8601, DATE_FORMAT_STR_ISO8601, tz, loc, null);
    }

    /**
     * Method for getting a non-shared DateFormat instance
     * that uses specific timezone and can handle RFC-1123
     * compliant date format.
     * 
     * @since 2.4
     */
    public static DateFormat getRFC1123Format(TimeZone tz, Locale loc) {
        return _cloneFormat(DATE_FORMAT_RFC1123, DATE_FORMAT_STR_RFC1123,
                tz, loc, null);
    }

    /*
    /**********************************************************
    /* Public API, configuration
    /**********************************************************
     */

    @Override // since 2.6
    public TimeZone getTimeZone() {
        return _timezone;
    }

    @Override
    public void setTimeZone(TimeZone tz)
    {
        /* DateFormats are timezone-specific (via Calendar contained),
         * so need to reset instances if timezone changes:
         */
        if (!tz.equals(_timezone)) {
            _clearFormats();
            _timezone = tz;
        }
    }

    /**
     * Need to override since we need to keep track of leniency locally,
     * and not via underlying {@link Calendar} instance like base class
     * does.
     */
    @Override // since 2.7
    public void setLenient(boolean enabled) {
        Boolean newValue = Boolean.valueOf(enabled);
        if (!_equals(newValue, _lenient)) {
            _lenient = newValue;
            // and since leniency settings may have been used:
            _clearFormats();
        }
    }

    @Override // since 2.7
    public boolean isLenient() {
        // default is, I believe, true
        return (_lenient == null) || _lenient.booleanValue();
    }

    /*
    /**********************************************************
    /* Public API, parsing
    /**********************************************************
     */

    @Override
    public Date parse(String dateStr) throws ParseException
    {
        dateStr = dateStr.trim();
        ParsePosition pos = new ParsePosition(0);
        Date dt = _parseDate(dateStr, pos);
        if (dt != null) {
            return dt;
        }
        StringBuilder sb = new StringBuilder();
        for (String f : ALL_FORMATS) {
            if (sb.length() > 0) {
                sb.append("\", \"");
            } else {
                sb.append('"');
            }
            sb.append(f);
        }
        sb.append('"');
        throw new ParseException
            (String.format("Can not parse date \"%s\": not compatible with any of standard forms (%s)",
                           dateStr, sb.toString()), pos.getErrorIndex());
    }

    // 24-Jun-2017, tatu: I don't think this ever gets called. So could... just not implement?
    @Override
    public Date parse(String dateStr, ParsePosition pos)
    {
        try {
            return _parseDate(dateStr, pos);
        } catch (ParseException e) {
            // may look weird but this is what `DateFormat` suggest to do...
        }
        return null;
    }

    protected Date _parseDate(String dateStr, ParsePosition pos) throws ParseException
    {
        if (looksLikeISO8601(dateStr)) { // also includes "plain"
            return parseAsISO8601(dateStr, pos);
        }
        // Also consider "stringified" simple time stamp
        int i = dateStr.length();
        while (--i >= 0) {
            char ch = dateStr.charAt(i);
            if (ch < '0' || ch > '9') {
                // 07-Aug-2013, tatu: And [databind#267] points out that negative numbers should also work
                if (i > 0 || ch != '-') {
                    break;
                }
            }
        }
        if ((i < 0)
            // let's just assume negative numbers are fine (can't be RFC-1123 anyway); check length for positive
                && (dateStr.charAt(0) == '-' || NumberInput.inLongRange(dateStr, false))) {
            return _parseDateFromLong(dateStr, pos);
        }
        // Otherwise, fall back to using RFC 1123. NOTE: call will NOT throw, just returns `null`
        return parseAsRFC1123(dateStr, pos);
    }

    /*
    /**********************************************************
    /* Public API, writing
    /**********************************************************
     */
    
    @Override
    public StringBuffer format(Date date, StringBuffer toAppendTo,
            FieldPosition fieldPosition)
    {
        if (_formatISO8601 == null) {
            _formatISO8601 = _cloneFormat(DATE_FORMAT_ISO8601, DATE_FORMAT_STR_ISO8601,
                    _timezone, _locale, _lenient);
        }
        // 24-Jun-2017, tatu: is this actually safe thing to do without clone() or sync?
        return _formatISO8601.format(date, toAppendTo, fieldPosition);
    }

    /*
    /**********************************************************
    /* Std overrides
    /**********************************************************
     */

    @Override
    public String toString() {
        return String.format("DateFormat %s: (timezone: %s, locale: %s, lenient: %s)",
                getClass().getName(), _timezone, _locale, _lenient);
    }

    public String toPattern() { // same as SimpleDateFormat
        StringBuilder sb = new StringBuilder(100);
        sb.append("[one of: '")
            .append(DATE_FORMAT_STR_ISO8601)
            .append("', '")
            .append(DATE_FORMAT_STR_RFC1123)
            .append("' (")
            ;
        sb.append(Boolean.FALSE.equals(_lenient) ?
                "strict" : "lenient")
            .append(")]");
        return sb.toString();
    }

    @Override // since 2.7[.2], as per [databind#1130]
    public boolean equals(Object o) {
        return (o == this);
    }

    @Override // since 2.7[.2], as per [databind#1130]
    public int hashCode() {
        return System.identityHashCode(this);
    }

    /*
    /**********************************************************
    /* Helper methods, parsing
    /**********************************************************
     */

    /**
     * Helper method used to figure out if input looks like valid
     * ISO-8601 string.
     */
    protected boolean looksLikeISO8601(String dateStr)
    {
        if (dateStr.length() >= 7 // really need 10, but...
            && Character.isDigit(dateStr.charAt(0))
            && Character.isDigit(dateStr.charAt(3))
            && dateStr.charAt(4) == '-'
            && Character.isDigit(dateStr.charAt(5))
            ) {
            return true;
        }
        return false;
    }

    private Date _parseDateFromLong(String longStr, ParsePosition pos) throws ParseException
    {
        long ts;
        try {
            ts = NumberInput.parseLong(longStr);
        } catch (NumberFormatException e) {
            throw new ParseException(String.format(
                    "Timestamp value %s out of 64-bit value range", longStr),
                    pos.getErrorIndex());
        }
        return new Date(ts);
    }

    protected Date parseAsISO8601(String dateStr, ParsePosition pos)
        throws ParseException
    {
        try {
            return _parseAsISO8601(dateStr, pos);
        } catch (IllegalArgumentException e) {
            throw new ParseException(String.format("Can not parse date \"%s\", problem: %s",
                    dateStr, e.getMessage()),
                    pos.getErrorIndex());
        }
    }

    protected Date _parseAsISO8601(String dateStr, ParsePosition pos)
        throws IllegalArgumentException, ParseException
    {
        final int totalLen = dateStr.length();
        // actually, one short-cut: if we end with "Z", must be UTC
        TimeZone tz = DEFAULT_TIMEZONE;
        if ((_timezone != null) && ('Z' != dateStr.charAt(totalLen-1))) {
            tz = _timezone;
        }
        Calendar cal = Calendar.getInstance(tz, _locale);
        if (_lenient != null) {
            cal.setLenient(_lenient.booleanValue());
        }
        String formatStr;
        if (totalLen <= 10) {
            Matcher m = PATTERN_PLAIN.matcher(dateStr);
            if (m.matches()) {
                int year = _parse4D(dateStr, 0);
                int month = _parse2D(dateStr, 5)-1;
                int day = _parse2D(dateStr, 8);

                cal.set(year, month, day, 0, 0, 0);
                cal.set(Calendar.MILLISECOND, 0);
                return cal.getTime();
            }
            formatStr = DATE_FORMAT_STR_PLAIN;
        } else {
            Matcher m = PATTERN_ISO8601.matcher(dateStr);
            if (m.matches()) {
                // Important! START with optional timezone; otherwise
                // Calendar will implode.
                
                int start = m.start(2);
                int end = m.end(2);
                int len = end-start;
                if (len > 1) { // 0 -> none, 1 -> 'Z'
                    // NOTE: first char is sign; then 2 digits, then optional colon, optional 2 digits
                    int offsetSecs = _parse2D(dateStr, start+1) * 3600;
                    if (len >= 5) {
                        offsetSecs += _parse2D(dateStr, end-2);
                    }
                    if (dateStr.charAt(start) == '-') {
                        offsetSecs *= -1000;
                    } else {
                        offsetSecs *= 1000;
                    }
                    cal.set(Calendar.ZONE_OFFSET, offsetSecs);
                    // 23-Jun-2017, tatu: Not sure why, but this appears to be needed too:
                    cal.set(Calendar.DST_OFFSET, 0);
                }
                
                int year = _parse4D(dateStr, 0);
                int month = _parse2D(dateStr, 5)-1;
                int day = _parse2D(dateStr, 8);

                // So: 10 chars for date, then `T`, so starts at 11
                int hour = _parse2D(dateStr, 11);
                int minute = _parse2D(dateStr, 14);

                // Seconds are actually optional... so
                int seconds;
                if ((totalLen > 16) && dateStr.charAt(16) == ':') {
                    seconds = _parse2D(dateStr, 17);
                } else {
                    seconds = 0;
                }
                cal.set(year, month, day, hour, minute, seconds);

                // Optional milliseconds
                start = m.start(1) + 1;
                end = m.end(1);
                int msecs = 0;
                if (start >= end) { // no fractional
                    cal.set(Calendar.MILLISECOND, 0);
                } else {
                    // first char is '.', but rest....
                    msecs = 0;
                    switch (end-start) {
                    case 3:
                        msecs += (dateStr.charAt(start+2) - '0');
                    case 2:
                        msecs += 10 * (dateStr.charAt(start+1) - '0');
                    case 1:
                        msecs += 100 * (dateStr.charAt(start) - '0');
                        break;
                    default:
                        throw new ParseException(String.format(
"Can not parse date \"%s\": invalid fractional seconds '%s'; can use at most 3 digits",
                                       dateStr, m.group(1).substring(1)
                                       ),
                                pos.getErrorIndex());
                    }
                    cal.set(Calendar.MILLISECOND, msecs);
                }
                return cal.getTime();
            }
            formatStr = DATE_FORMAT_STR_ISO8601;
        }

        throw new ParseException
        (String.format("Can not parse date \"%s\": while it seems to fit format '%s', parsing fails (leniency? %s)",
                       dateStr, formatStr, _lenient),
           pos.getErrorIndex());
    }

    private static int _parse4D(String str, int index) {
        return (1000 * (str.charAt(index) - '0'))
                + (100 * (str.charAt(index+1) - '0'))
                + (10 * (str.charAt(index+2) - '0'))
                + (str.charAt(index+3) - '0');
    }

    private static int _parse2D(String str, int index) {
        return (10 * (str.charAt(index) - '0'))
                + (str.charAt(index+1) - '0');
    }

    protected Date parseAsRFC1123(String dateStr, ParsePosition pos)
    {
        if (_formatRFC1123 == null) {
            _formatRFC1123 = _cloneFormat(DATE_FORMAT_RFC1123, DATE_FORMAT_STR_RFC1123,
                    _timezone, _locale, _lenient);
        }
        return _formatRFC1123.parse(dateStr, pos);
    }

    /*
    /**********************************************************
    /* Helper methods, other
    /**********************************************************
     */

    private final static DateFormat _cloneFormat(DateFormat df, String format,
            TimeZone tz, Locale loc, Boolean lenient)
    {
        if (!loc.equals(DEFAULT_LOCALE)) {
            df = new SimpleDateFormat(format, loc);
            df.setTimeZone((tz == null) ? DEFAULT_TIMEZONE : tz);
        } else {
            df = (DateFormat) df.clone();
            if (tz != null) {
                df.setTimeZone(tz);
            }
        }
        if (lenient != null) {
            df.setLenient(lenient.booleanValue());
        }
        return df;
    }

    protected void _clearFormats() {
        _formatRFC1123 = null;
        _formatISO8601 = null;
    }

    protected static <T> boolean _equals(T value1, T value2) {
        if (value1 == value2) {
            return true;
        }
        return (value1 != null) && value1.equals(value2);
    }
}
