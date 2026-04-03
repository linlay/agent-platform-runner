package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.util.LunarDateUtils;
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
        return "_datetime_";
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
        return LunarDateUtils.lunarDateOf(solarDate);
    }
}
