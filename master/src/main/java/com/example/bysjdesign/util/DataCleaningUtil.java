package com.example.bysjdesign.util;

import com.example.bysjdesign.campus.entity.AccessLog;
import com.example.bysjdesign.campus.entity.BorrowLog;
import com.example.bysjdesign.campus.entity.CampusUser;
import com.example.bysjdesign.campus.entity.NetworkLog;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DataCleaningUtil {

    private static final Logger logger = LoggerFactory.getLogger(DataCleaningUtil.class);
    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build();
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId ZONE_ID = ZoneId.systemDefault();

    public record ImportedCampusUser(Integer sourceUserId, CampusUser user) {}

    public List<ImportedCampusUser> loadCampusUsers(String resourcePath) {
        List<ImportedCampusUser> users = new ArrayList<>();
        Date importedAt = new Date();

        parseCsv(resourcePath, parser -> {
            for (CSVRecord record : parser) {
                try {
                    Integer sourceUserId = readInteger(record, "user_id");
                    if (sourceUserId == null) {
                        continue;
                    }

                    CampusUser user = new CampusUser();
                    user.setStudentId(readText(record, "student_id"));
                    user.setName(readText(record, "name"));
                    user.setGender(normalizeGender(readText(record, "gender")));
                    user.setEnrollmentYear(extractEnrollmentYear(user.getStudentId()));
                    user.setCollege(readText(record, "college"));
                    user.setMajor(readText(record, "major"));
                    user.setClazz(readText(record, "clazz"));
                    user.setStatus(1);
                    user.setCreateTime(importedAt);
                    users.add(new ImportedCampusUser(sourceUserId, user));
                } catch (Exception ex) {
                    logSkippedRecord(resourcePath, record, ex);
                }
            }
            return null;
        });

        return users;
    }

    public Set<Integer> loadCampusUserSourceIds(String resourcePath) {
        Set<Integer> sourceIds = new LinkedHashSet<>();

        parseCsv(resourcePath, parser -> {
            for (CSVRecord record : parser) {
                try {
                    Integer sourceUserId = readInteger(record, "user_id");
                    if (sourceUserId != null) {
                        sourceIds.add(sourceUserId);
                    }
                } catch (Exception ex) {
                    logSkippedRecord(resourcePath, record, ex);
                }
            }
            return null;
        });

        return sourceIds;
    }

    public List<AccessLog> loadAccessLogs(String resourcePath, Map<Integer, Integer> userIdMapping) {
        List<AccessLog> logs = new ArrayList<>();
        final int[] skippedByUserMapping = {0};

        parseCsv(resourcePath, parser -> {
            for (CSVRecord record : parser) {
                try {
                    Integer actualUserId = resolveUserId(record, userIdMapping);
                    if (actualUserId == null) {
                        skippedByUserMapping[0]++;
                        continue;
                    }

                    AccessLog log = new AccessLog();
                    log.setUserId(actualUserId);
                    log.setLocationType(readText(record, "location_type"));
                    log.setLocationName(readText(record, "location_name"));
                    log.setEntryTime(readDateTime(record, "entry_time"));
                    log.setExitTime(readDateTime(record, "exit_time"));
                    log.setDurationSec(readInteger(record, "duration_sec"));
                    log.setCardId(readText(record, "card_id"));
                    log.setDeviceId(null);
                    logs.add(log);
                } catch (Exception ex) {
                    logSkippedRecord(resourcePath, record, ex);
                }
            }
            return null;
        });

        if (skippedByUserMapping[0] > 0) {
            logger.warn("{} 中有 {} 条门禁记录因无法匹配用户 ID 被跳过", resourcePath, skippedByUserMapping[0]);
        }
        return logs;
    }

    public List<BorrowLog> loadBorrowLogs(String resourcePath, Map<Integer, Integer> userIdMapping) {
        List<BorrowLog> logs = new ArrayList<>();
        final int[] skippedByUserMapping = {0};

        parseCsv(resourcePath, parser -> {
            for (CSVRecord record : parser) {
                try {
                    Integer actualUserId = resolveUserId(record, userIdMapping);
                    if (actualUserId == null) {
                        skippedByUserMapping[0]++;
                        continue;
                    }

                    BorrowLog log = new BorrowLog();
                    log.setUserId(actualUserId);
                    log.setBookIsbn(readText(record, "book_isbn"));
                    log.setBookTitle(readText(record, "book_title"));
                    log.setCategory(readText(record, "category"));
                    log.setBorrowDate(readDate(record, "borrow_date"));
                    log.setDueDate(readDate(record, "due_date"));
                    log.setReturnDate(readDate(record, "return_date"));
                    log.setRenewCount(readInteger(record, "renew_count"));
                    log.setLibraryBranch(readText(record, "library_branch"));
                    logs.add(log);
                } catch (Exception ex) {
                    logSkippedRecord(resourcePath, record, ex);
                }
            }
            return null;
        });

        if (skippedByUserMapping[0] > 0) {
            logger.warn("{} 中有 {} 条借阅记录因无法匹配用户 ID 被跳过", resourcePath, skippedByUserMapping[0]);
        }
        return logs;
    }

    public List<NetworkLog> loadNetworkLogs(String resourcePath, Map<Integer, Integer> userIdMapping) {
        List<NetworkLog> logs = new ArrayList<>();
        final int[] skippedByUserMapping = {0};

        parseCsv(resourcePath, parser -> {
            for (CSVRecord record : parser) {
                try {
                    Integer actualUserId = resolveUserId(record, userIdMapping);
                    if (actualUserId == null) {
                        skippedByUserMapping[0]++;
                        continue;
                    }

                    NetworkLog log = new NetworkLog();
                    log.setUserId(actualUserId);
                    log.setSessionStart(readDateTime(record, "session_start"));
                    log.setSessionEnd(readDateTime(record, "session_end"));
                    log.setDurationSec(readInteger(record, "duration_sec"));
                    log.setUploadBytes(readLong(record, "upload_bytes"));
                    log.setDownloadBytes(readLong(record, "download_bytes"));
                    log.setSrcIp(readText(record, "src_ip"));
                    log.setDestDomain(readText(record, "dest_domain"));
                    log.setDestIp(readText(record, "dest_ip"));
                    log.setProtocol(readText(record, "protocol"));
                    log.setCategory(readText(record, "category"));
                    log.setIsAbnormal(readInteger(record, "is_abnormal"));
                    logs.add(log);
                } catch (Exception ex) {
                    logSkippedRecord(resourcePath, record, ex);
                }
            }
            return null;
        });

        if (skippedByUserMapping[0] > 0) {
            logger.warn("{} 中有 {} 条网络记录因无法匹配用户 ID 被跳过", resourcePath, skippedByUserMapping[0]);
        }
        return logs;
    }

    private <T> T parseCsv(String resourcePath, CsvParserCallback<T> callback) {
        try (Reader reader = openReader(resourcePath);
             CSVParser parser = CSV_FORMAT.parse(reader)) {
            return callback.handle(parser);
        } catch (IOException ex) {
            throw new IllegalStateException("读取 CSV 资源失败: " + resourcePath, ex);
        }
    }

    private Reader openReader(String resourcePath) throws IOException {
        String normalizedPath = StringUtils.removeStart(resourcePath, "/");
        ClassPathResource resource = new ClassPathResource(normalizedPath);
        return new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
    }

    private Integer resolveUserId(CSVRecord record, Map<Integer, Integer> userIdMapping) {
        Integer sourceUserId = readInteger(record, "user_id");
        if (sourceUserId == null) {
            return null;
        }
        return userIdMapping.get(sourceUserId);
    }

    private String readText(CSVRecord record, String columnName) {
        if (!record.isMapped(columnName)) {
            return null;
        }

        String value = record.get(columnName);
        if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
            value = value.substring(1);
        }
        return StringUtils.trimToNull(value);
    }

    private Integer readInteger(CSVRecord record, String columnName) {
        String value = readText(record, columnName);
        return value == null ? null : Integer.valueOf(value);
    }

    private Long readLong(CSVRecord record, String columnName) {
        String value = readText(record, columnName);
        return value == null ? null : Long.valueOf(value);
    }

    private Date readDate(CSVRecord record, String columnName) {
        String value = readText(record, columnName);
        if (value == null) {
            return null;
        }
        LocalDate date = LocalDate.parse(value, DATE_FORMATTER);
        return Date.from(date.atStartOfDay(ZONE_ID).toInstant());
    }

    private Date readDateTime(CSVRecord record, String columnName) {
        String value = readText(record, columnName);
        if (value == null) {
            return null;
        }
        LocalDateTime dateTime = LocalDateTime.parse(value, DATE_TIME_FORMATTER);
        return Date.from(dateTime.atZone(ZONE_ID).toInstant());
    }

    private Integer extractEnrollmentYear(String studentId) {
        if (StringUtils.length(studentId) < 4 || !StringUtils.isNumeric(studentId.substring(0, 4))) {
            return null;
        }

        int year = Integer.parseInt(studentId.substring(0, 4));
        int currentYear = LocalDate.now().getYear() + 1;
        return year >= 2000 && year <= currentYear ? year : null;
    }

    private String normalizeGender(String gender) {
        if (gender == null) {
            return null;
        }
        if ("男".equals(gender) || "女".equals(gender)) {
            return gender;
        }
        if ("M".equalsIgnoreCase(gender) || "male".equalsIgnoreCase(gender)) {
            return "男";
        }
        if ("F".equalsIgnoreCase(gender) || "female".equalsIgnoreCase(gender)) {
            return "女";
        }
        return gender;
    }

    private void logSkippedRecord(String resourcePath, CSVRecord record, Exception ex) {
        logger.warn("跳过 {} 第 {} 行，原因: {}", resourcePath, record.getRecordNumber(), ex.getMessage());
    }

    @FunctionalInterface
    private interface CsvParserCallback<T> {
        T handle(CSVParser parser) throws IOException;
    }
}
