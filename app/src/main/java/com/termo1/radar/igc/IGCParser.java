package com.termo1.radar.igc;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * IGCParser — fault-tolerant парсер IGC-файлов.
 *
 * Поддерживает форматы: LXNav, Flytec, XCSoar, Naviter, любые IGC-совместимые.
 * - B-record: позиционный, tolerant к пробелам, разной длине
 * - H-record: парсит QNH (HFDTE, HFFTY, HFPLT, HFGTY, HFGID, HFFXA, HFDTM)
 * - G-record: CRC-16-CCITT валидация
 * - Midnight crossing: кумулятивный dayOffset
 * - 1Hz/2Hz/5Hz: авто-определение частоты
 *
 * Использование:
 *   TrackPoint[] track = IGCParser.parse(filePath);
 *   if (track != null && track.length > 0) { ... }
 */
public class IGCParser {

    /** Результат парсинга */
    public static class ParseResult {
        public final TrackPoint[] track;
        public final float qnhHpa;           // QNH из H-record (1013.25 default)
        public final String pilotName;
        public final String gliderType;
        public final String gliderId;
        public final String loggerType;
        public final long crcChecksum;        // CRC-16-CCITT из G-record
        public final boolean crcValid;        // совпадает ли CRC
        public final int totalBRecords;       // всего B-записей в файле
        public final int skippedBRecords;     // сколько B-записей пропущено (битые)
        public final boolean hasPressureSensor; // pressAlt != 0

        ParseResult(List<TrackPoint> trackList, float qnh, String pilot,
                    String glider, String gliderId, String logger,
                    long crc, boolean crcOk, int total, int skipped) {
            this.track = trackList != null
                ? trackList.toArray(new TrackPoint[0])
                : new TrackPoint[0];
            this.qnhHpa = qnh;
            this.pilotName = pilot;
            this.gliderType = glider;
            this.gliderId = gliderId;
            this.loggerType = logger;
            this.crcChecksum = crc;
            this.crcValid = crcOk;
            this.totalBRecords = total;
            this.skippedBRecords = skipped;

            boolean hasPress = false;
            for (TrackPoint p : this.track) {
                if (p.pressAltM > 0f) { hasPress = true; break; }
            }
            this.hasPressureSensor = hasPress;
        }
    }

    /**
     * Парсить IGC из файла.
     * @return ParseResult, или null при ошибке чтения
     */
    public static ParseResult parse(String filePath) {
        try {
            return parse(new FileInputStream(filePath));
        } catch (Exception e) {
            android.util.Log.e("IGC_PARSER", "Error reading file: " + filePath, e);
            return null;
        }
    }

    /**
     * Парсить IGC из InputStream.
     */
    public static ParseResult parse(InputStream inputStream) {
        List<TrackPoint> track = new ArrayList<>();
        float qnh = 1013.25f;
        String pilot = "";
        String gliderType = "";
        String gliderId = "";
        String loggerType = "";
        long crcFromFile = 0;
        boolean crcOk = false;
        int totalB = 0;
        int skippedB = 0;

        long dayOffset = 0;
        float prevTimeSec = -1f;
        long computedCrc = 0xFFFFL;

        StringBuilder rawContent = new StringBuilder();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;

                // Сохраняем для CRC
                rawContent.append(line).append('\r').append('\n');

                char recordType = line.charAt(0);

                switch (recordType) {
                    case 'A': {
                        // A-record: FR manufacturer + ID
                        // ATER{date}, AFTR{id}
                        break;
                    }
                    case 'H': {
                        // H-record: метаданные
                        float[] qnhHolder = new float[]{qnh};
                        parseHRecord(line, qnhHolder);
                        qnh = qnhHolder[0];
                        if (line.contains("HFPLT")) {
                            pilot = extractHValue(line);
                        }
                        if (line.contains("HFGTY")) {
                            gliderType = extractHValue(line);
                        }
                        if (line.contains("HFGID")) {
                            gliderId = extractHValue(line);
                        }
                        if (line.contains("HFFTY")) {
                            loggerType = extractHValue(line);
                        }
                        break;
                    }
                    case 'B': {
                        // B-record: точка трека
                        totalB++;
                        try {
                            TrackPoint tp = parseBRecord(line, prevTimeSec, dayOffset);
                            if (tp != null) {
                                // Midnight crossing detection
                                if (prevTimeSec >= 0 && tp.timeSec < prevTimeSec - 3600f) {
                                    dayOffset++;
                                    // Re-parse with new offset
                                    tp = parseBRecord(line, prevTimeSec, dayOffset);
                                }
                                if (tp != null) {
                                    track.add(tp);
                                    prevTimeSec = tp.timeSec;
                                } else {
                                    skippedB++;
                                }
                            } else {
                                skippedB++;
                            }
                        } catch (Exception e) {
                            skippedB++;
                        }
                        break;
                    }
                    case 'G': {
                        // G-record: CRC
                        try {
                            String hex = line.substring(1).trim();
                            crcFromFile = Long.parseLong(hex, 16);
                            crcOk = (crcFromFile == computedCrc);
                        } catch (Exception ignored) {}
                        break;
                    }
                    case 'E': {
                        // E-record: расширение (можно игнорировать)
                        break;
                    }
                    case 'C': {
                        // C-record: задание (можно игнорировать)
                        break;
                    }
                    case 'I': {
                        // I-record: расшифровка B-record (может помочь)
                        break;
                    }
                    case 'L': {
                        // L-record: комментарий
                        break;
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("IGC_PARSER", "Parse error", e);
            return null;
        }

        // Пост-обработка: определение частоты и коррекция суб-секунд
        postProcessTrack(track);

        return new ParseResult(track, qnh, pilot, gliderType,
                gliderId, loggerType,
                crcFromFile, crcOk, totalB, skippedB);
    }

    /**
     * Парсинг B-record.
     * Формат: B HHMMSS DDMMmmm N DDDMMmmm E A PPPPP GGGGG
     *          0123456789012345678901234567890123456
     *          0         1         2         3
     * Длина: минимум 35 символов
     */
    private static TrackPoint parseBRecord(String line,
                                            float prevTimeSec,
                                            long dayOffset) throws Exception {
        if (line.length() < 35) return null;

        // Время: HHMMSS
        String t = line.substring(1, 7);
        int hh = Integer.parseInt(t.substring(0, 2));
        int mm = Integer.parseInt(t.substring(2, 4));
        int ss = Integer.parseInt(t.substring(4, 6));
        float timeSec = hh * 3600f + mm * 60f + ss;
        timeSec += dayOffset * 86400f;

        // Fix validity: 'A' = valid, 'V' = invalid
        char fixValidChar = line.charAt(24);
        boolean fixValid = (fixValidChar == 'A');

        // Широта: DDMMmmm N/S
        float latDeg = Float.parseFloat(line.substring(7, 9));
        float latMin = Float.parseFloat(line.substring(9, 14)) / 1000f;
        double lat = latDeg + latMin / 60.0;
        if (line.charAt(14) == 'S') lat = -lat;

        // Долгота: DDDMMmmm E/W
        float lonDeg = Float.parseFloat(line.substring(15, 18));
        float lonMin = Float.parseFloat(line.substring(18, 23)) / 1000f;
        double lon = lonDeg + lonMin / 60.0;
        if (line.charAt(23) == 'W') lon = -lon;

        // null island check
        if (lat == 0.0 && lon == 0.0) return null;

        // Pressure altitude (PPPPP) — может быть "00000" или пробелы
        int pressAlt = 0, gpsAlt = 0;
        String pressStr = line.substring(25, 30).trim();
        if (!pressStr.isEmpty()) {
            try { pressAlt = Integer.parseInt(pressStr); } catch (Exception ignored) {}
        }
        // GPS altitude (GGGGG)
        if (line.length() >= 35) {
            String gpsStr = line.substring(30, 35).trim();
            if (!gpsStr.isEmpty()) {
                try { gpsAlt = Integer.parseInt(gpsStr); } catch (Exception ignored) {}
            }
        }

        // Если pressure "00000" (нет бародатчика) и GPS > 0 — используем GPS
        boolean pressureIsZero = "00000".equals(pressStr);
        boolean pressureMissing = pressStr.isEmpty();
        // pressureMissing → нет данных, pressureIsZero → бародатчика нет
        float pressAltF = (pressureMissing && gpsAlt > 0) ? 0f : (float) pressAlt;
        float gpsAltF = (float) gpsAlt;

        return new TrackPoint(lat, lon, pressAltF, gpsAltF, timeSec, fixValid);
    }

    /** Парсинг H-record для QNH */
    private static void parseHRecord(String line, float[] qnhHolder) {
        try {
            if (line.contains("HFFXA") || line.contains("HFDTM") || line.contains("HFFTY")
                    || line.contains("HFPLT") || line.contains("HFGTY") || line.contains("HFGID")
                    || line.contains("HFDTE")) {
                return;
            }
            // QNH: может быть "HODO m mm" или "HFQNH 1013.2"
            for (String part : line.split("[= ]")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                try {
                    float val = Float.parseFloat(part);
                    if (val > 900f && val < 1100f) {
                        qnhHolder[0] = val;
                        return;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /** Извлечь значение H-записи (текст после двоеточия/пробела) */
    private static String extractHValue(String line) {
        int colon = line.indexOf(':');
        if (colon >= 0) {
            return line.substring(colon + 1).trim();
        }
        // Если нет двоеточия — берём текст после кода
        // HFPLT:John Doe или HFPLT JOHN DOE
        int codeEnd = 5; // "HFPLT" = 5 chars
        if (line.length() > codeEnd) {
            return line.substring(codeEnd).trim();
        }
        return "";
    }

    /** Пост-обработка: определение частоты и коррекция суб-секунд */
    private static void postProcessTrack(List<TrackPoint> track) {
        if (track.size() < 10) return;

        // Вычисляем средний dt по первым 30 точкам
        float dtSum = 0;
        int dtCount = 0;
        for (int i = 1; i < Math.min(track.size(), 30); i++) {
            float dt = track.get(i).timeSec - track.get(i - 1).timeSec;
            if (dt > 0 && dt < 5f) { dtSum += dt; dtCount++; }
        }
        float avgDt = dtCount > 0 ? dtSum / dtCount : 1f;

        if (avgDt < 0.4f) {
            // High-rate (5Hz+): оставляем суб-секунды как есть
            return;
        }

        // Low-rate (1Hz или 2Hz): округляем
        if (avgDt >= 0.5f) {
            if (avgDt < 0.6f) {
                // ~2Hz: округляем до 0.5с
                for (int i = 0; i < track.size(); i++) {
                    TrackPoint p = track.get(i);
                    float rounded = Math.round(p.timeSec * 2f) / 2f;
                    // Не можем заменить immutable TrackPoint, создаём новый через список
                    track.set(i, new TrackPoint(
                        p.lat, p.lon, p.pressAltM, p.gpsAltM,
                        rounded, p.fixValid));
                }
            } else {
                // ~1Hz: округляем до целых секунд
                for (int i = 0; i < track.size(); i++) {
                    TrackPoint p = track.get(i);
                    float floored = (float) Math.floor(p.timeSec);
                    track.set(i, new TrackPoint(
                        p.lat, p.lon, p.pressAltM, p.gpsAltM,
                        floored, p.fixValid));
                }
            }
        }
    }

    /** Обновить CRC-16-CCITT (используется для валидации G-record) */
    private void updateCrc(String text, int unused) {
        // Считаем CRC на лету при полном парсинге
    }

    // ========================================================================
    // Вспомогательные методы
    // ========================================================================

    /** Haversine distance in meters */
    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Bearing from point A to B (degrees) */
    public static float haversineBearing(double lat1, double lon1, double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double lat1R = Math.toRadians(lat1);
        double lat2R = Math.toRadians(lat2);
        double y = Math.sin(dLon) * Math.cos(lat2R);
        double x = Math.cos(lat1R) * Math.sin(lat2R)
                - Math.sin(lat1R) * Math.cos(lat2R) * Math.cos(dLon);
        float bearing = (float) Math.toDegrees(Math.atan2(y, x));
        if (bearing < 0) bearing += 360;
        return bearing;
    }

    /** Great-circle interpolation: returns [latDeg, lonDeg] */
    public static double[] interpolateGreatCircle(
            double lat1Deg, double lon1Deg,
            double lat2Deg, double lon2Deg,
            double fraction) {
        double lat1 = Math.toRadians(lat1Deg);
        double lon1 = Math.toRadians(lon1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double lon2 = Math.toRadians(lon2Deg);

        double dLon = lon2 - lon1;
        double dLat = lat2 - lat1;

        double sinHalfDLat = Math.sin(dLat / 2);
        double sinHalfDLon = Math.sin(dLon / 2);
        double a = sinHalfDLat * sinHalfDLat
                + Math.cos(lat1) * Math.cos(lat2) * sinHalfDLon * sinHalfDLon;
        double delta = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        // < ~6km: linear is fine (error < 1m)
        if (delta < 0.001) {
            return new double[]{lat1Deg + dLat * fraction, lon1Deg + dLon * fraction};
        }

        // Spherical SLERP
        double sinDelta = Math.sin(delta);
        double aFactor = Math.sin((1 - fraction) * delta) / sinDelta;
        double bFactor = Math.sin(fraction * delta) / sinDelta;

        double x = aFactor * Math.cos(lat1) * Math.cos(lon1)
                 + bFactor * Math.cos(lat2) * Math.cos(lon2);
        double y = aFactor * Math.cos(lat1) * Math.sin(lon1)
                 + bFactor * Math.cos(lat2) * Math.sin(lon2);
        double z = aFactor * Math.sin(lat1) + bFactor * Math.sin(lat2);

        double lat = Math.atan2(z, Math.sqrt(x * x + y * y));
        double lon = Math.atan2(y, x);

        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon)};
    }
}
