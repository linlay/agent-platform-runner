package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DateTimeTool extends AbstractDeterministicTool {

    private static final Pattern OFFSET_TOKEN_PATTERN = Pattern.compile("([+-])(\\d+)([ywDHMmS])");

    @Override
    public String name() {
        return "datetime";
    }

    @Override
    public JsonNode invoke(Map<String, Object> args) {
        ZoneId zoneId = parseZoneId(args.get("timezone"));
        String normalizedOffset = normalizeOffset(args.get("offset"));
        ZonedDateTime dateTime = applyOffset(ZonedDateTime.ofInstant(Instant.now(CLOCK), zoneId), normalizedOffset);
        LocalDate date = dateTime.toLocalDate();

        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("timezone", zoneId.getId());
        root.put("timezoneOffset", utcOffsetOf(dateTime.getOffset()));
        root.put("offset", normalizedOffset);
        root.put("date", date.toString());
        root.put("weekday", weekdayOf(date.getDayOfWeek()));
        root.put("lunarDate", lunarDateOf(date));
        root.put("time", dateTime.toLocalTime().truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        root.put("iso", dateTime.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        root.put("source", "system-clock");
        return root;
    }

    private ZoneId parseZoneId(Object rawTimezone) {
        String timezone = rawTimezone == null ? "" : String.valueOf(rawTimezone).trim();
        if (timezone.isEmpty()) {
            return ZoneId.systemDefault();
        }

        String normalized = timezone.toUpperCase(Locale.ROOT);
        if ("Z".equals(normalized) || "UTC".equals(normalized) || "GMT".equals(normalized)) {
            return ZoneOffset.UTC;
        }
        if (normalized.startsWith("UTC") || normalized.startsWith("GMT") || normalized.matches("[+-].*")) {
            if (normalized.startsWith("UTC") || normalized.startsWith("GMT")) {
                normalized = normalized.substring(3);
            }
            normalized = normalizeOffsetTimezone(normalized);
            try {
                return ZoneOffset.of(normalized);
            } catch (Exception ex) {
                throw new IllegalArgumentException(
                        "Invalid timezone: " + timezone + ". Use an IANA zone like Asia/Shanghai or an offset like UTC+8/+08:00/Z."
                );
            }
        }

        try {
            return ZoneId.of(timezone);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Invalid timezone: " + timezone + ". Use an IANA zone like Asia/Shanghai or an offset like UTC+8/+08:00/Z."
            );
        }
    }

    private String normalizeOffsetTimezone(String timezoneOffset) {
        if (timezoneOffset.matches("[+-]\\d{1,2}")) {
            String sign = timezoneOffset.substring(0, 1);
            int hours = Integer.parseInt(timezoneOffset.substring(1));
            return String.format(Locale.ROOT, "%s%02d:00", sign, hours);
        }
        if (timezoneOffset.matches("[+-]\\d{1,2}:\\d{2}")) {
            String sign = timezoneOffset.substring(0, 1);
            String[] parts = timezoneOffset.substring(1).split(":");
            int hours = Integer.parseInt(parts[0]);
            return String.format(Locale.ROOT, "%s%02d:%s", sign, hours, parts[1]);
        }
        return timezoneOffset;
    }

    private String normalizeOffset(Object rawOffset) {
        String offset = rawOffset == null ? "" : String.valueOf(rawOffset).trim();
        if (offset.isEmpty() || "0".equals(offset)) {
            return "0";
        }

        String compact = offset.replaceAll("\\s+", "");
        Matcher matcher = OFFSET_TOKEN_PATTERN.matcher(compact);
        StringBuilder normalized = new StringBuilder();
        int index = 0;
        while (matcher.find()) {
            if (matcher.start() != index) {
                throw invalidOffset(offset);
            }
            normalized.append(matcher.group(1))
                    .append(matcher.group(2))
                    .append(matcher.group(3));
            index = matcher.end();
        }
        if (index != compact.length() || normalized.length() == 0) {
            throw invalidOffset(offset);
        }
        return normalized.toString();
    }

    private ZonedDateTime applyOffset(ZonedDateTime dateTime, String normalizedOffset) {
        if ("0".equals(normalizedOffset)) {
            return dateTime;
        }

        Matcher matcher = OFFSET_TOKEN_PATTERN.matcher(normalizedOffset);
        ZonedDateTime result = dateTime;
        while (matcher.find()) {
            long amount = Long.parseLong(matcher.group(2));
            if ("-".equals(matcher.group(1))) {
                amount = -amount;
            }
            result = switch (matcher.group(3).charAt(0)) {
                case 'y' -> result.plusYears(amount);
                case 'w' -> result.plusWeeks(amount);
                case 'D' -> result.plusDays(amount);
                case 'H' -> result.plusHours(amount);
                case 'M' -> result.plusMonths(amount);
                case 'm' -> result.plusMinutes(amount);
                case 'S' -> result.plusSeconds(amount);
                default -> throw invalidOffset(normalizedOffset);
            };
        }
        return result;
    }

    private IllegalArgumentException invalidOffset(String rawOffset) {
        return new IllegalArgumentException(
                "Invalid offset: " + rawOffset + ". Use tokens like +1D, -2y, +3w or chained forms like +1D-3H+20m or +10M+25D."
        );
    }

    private String utcOffsetOf(ZoneOffset offset) {
        int totalSeconds = offset.getTotalSeconds();
        if (totalSeconds == 0) {
            return "UTC+0";
        }
        int absTotalSeconds = Math.abs(totalSeconds);
        int hours = absTotalSeconds / 3600;
        int minutes = (absTotalSeconds % 3600) / 60;
        String sign = totalSeconds >= 0 ? "+" : "-";
        if (minutes == 0) {
            return "UTC" + sign + hours;
        }
        return "UTC" + sign + String.format(Locale.ROOT, "%d:%02d", hours, minutes);
    }

    private String weekdayOf(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "星期一";
            case TUESDAY -> "星期二";
            case WEDNESDAY -> "星期三";
            case THURSDAY -> "星期四";
            case FRIDAY -> "星期五";
            case SATURDAY -> "星期六";
            case SUNDAY -> "星期日";
        };
    }

    private String lunarDateOf(LocalDate solarDate) {
        LunarDate lunarDate = LunarDate.fromSolarDate(solarDate);
        if (lunarDate == null) {
            return "超出支持范围";
        }
        return lunarDate.displayText();
    }

    private static final class LunarDate {

        private static final int MIN_YEAR = 1900;
        private static final int MAX_YEAR = 2100;
        private static final LocalDate BASE_SOLAR_DATE = LocalDate.of(1900, 1, 31);
        private static final int[] LUNAR_INFO = {
                0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
                0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
                0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
                0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
                0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
                0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5d0, 0x14573, 0x052d0, 0x0a9a8, 0x0e950, 0x06aa0,
                0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
                0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b5a0, 0x195a6,
                0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
                0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x05ac0, 0x0ab60, 0x096d5, 0x092e0,
                0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
                0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
                0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
                0x05aa0, 0x076a3, 0x096d0, 0x04bd7, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
                0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
                0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0,
                0x0a2e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4,
                0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0,
                0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160,
                0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252,
                0x0d520
        };
        private static final String[] MONTH_NAMES = {"正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊"};
        private static final String[] DAY_PREFIX = {"初", "十", "廿", "卅"};
        private static final String[] DAY_NUMBER = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        private static final String[] HEAVENLY_STEMS = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
        private static final String[] EARTHLY_BRANCHES = {"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"};

        private final int year;
        private final int month;
        private final int day;
        private final boolean leapMonth;

        private LunarDate(int year, int month, int day, boolean leapMonth) {
            this.year = year;
            this.month = month;
            this.day = day;
            this.leapMonth = leapMonth;
        }

        private static LunarDate fromSolarDate(LocalDate solarDate) {
            if (solarDate.isBefore(BASE_SOLAR_DATE)) {
                return null;
            }

            long offset = ChronoUnit.DAYS.between(BASE_SOLAR_DATE, solarDate);
            int lunarYear = MIN_YEAR;
            while (lunarYear <= MAX_YEAR) {
                int days = yearDays(lunarYear);
                if (offset < days) {
                    break;
                }
                offset -= days;
                lunarYear++;
            }
            if (lunarYear > MAX_YEAR) {
                return null;
            }

            int leapMonth = leapMonth(lunarYear);
            int lunarMonth = 1;
            boolean isLeapMonth = false;
            while (lunarMonth <= 12) {
                int daysInMonth = isLeapMonth ? leapDays(lunarYear) : monthDays(lunarYear, lunarMonth);
                if (offset < daysInMonth) {
                    break;
                }
                offset -= daysInMonth;

                if (leapMonth > 0 && lunarMonth == leapMonth && !isLeapMonth) {
                    isLeapMonth = true;
                } else {
                    if (isLeapMonth) {
                        isLeapMonth = false;
                    }
                    lunarMonth++;
                }
            }
            if (lunarMonth > 12) {
                return null;
            }

            int lunarDay = (int) offset + 1;
            return new LunarDate(lunarYear, lunarMonth, lunarDay, isLeapMonth);
        }

        private String displayText() {
            return ganzhiYear(year) + "年" + (leapMonth ? "闰" : "") + monthName(month) + "月" + dayName(day);
        }

        private static int yearDays(int year) {
            int sum = 348;
            int info = lunarInfo(year);
            for (int bit = 0x8000; bit > 0x8; bit >>= 1) {
                if ((info & bit) != 0) {
                    sum += 1;
                }
            }
            return sum + leapDays(year);
        }

        private static int leapDays(int year) {
            int leap = leapMonth(year);
            if (leap == 0) {
                return 0;
            }
            return (lunarInfo(year) & 0x10000) != 0 ? 30 : 29;
        }

        private static int leapMonth(int year) {
            return lunarInfo(year) & 0xF;
        }

        private static int monthDays(int year, int month) {
            return (lunarInfo(year) & (0x10000 >> month)) != 0 ? 30 : 29;
        }

        private static int lunarInfo(int year) {
            int index = year - MIN_YEAR;
            if (index < 0 || index >= LUNAR_INFO.length) {
                return 0;
            }
            return LUNAR_INFO[index];
        }

        private static String monthName(int month) {
            if (month < 1 || month > 12) {
                return "未知";
            }
            return MONTH_NAMES[month - 1];
        }

        private static String dayName(int day) {
            if (day <= 0 || day > 30) {
                return "未知";
            }
            if (day == 10) {
                return "初十";
            }
            if (day == 20) {
                return "二十";
            }
            if (day == 30) {
                return "三十";
            }
            int prefix = day / 10;
            int number = day % 10;
            return DAY_PREFIX[prefix] + DAY_NUMBER[number];
        }

        private static String ganzhiYear(int year) {
            int stemIndex = Math.floorMod(year - 4, 10);
            int branchIndex = Math.floorMod(year - 4, 12);
            return HEAVENLY_STEMS[stemIndex] + EARTHLY_BRANCHES[branchIndex];
        }
    }
}
