package com.aoml.ladcp.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;


/**
 * Utility methods for LADCP Terminal operations.
 */
public class LADCPUtils {

    private static final DateTimeFormatter LOG_FORMATTER = 
            DateTimeFormatter.ofPattern("yyyy/MM/dd  HH:mm:ss");
    private static final DateTimeFormatter FILE_FORMATTER = 
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter ADCP_FORMATTER = 
            DateTimeFormatter.ofPattern("yyMMddHHmmss");

    /**
     * Gets the current UTC timestamp formatted for logging.
     *
     * @return Formatted timestamp string
     */
    public static String getLogTimestamp() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(LOG_FORMATTER);
    }

    /**
     * Gets the current UTC timestamp formatted for filenames.
     *
     * @return Formatted timestamp string
     */
    public static String getFileTimestamp() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(FILE_FORMATTER);
    }

    /**
     * Gets the current UTC timestamp formatted for ADCP clock setting.
     *
     * @return Formatted timestamp string
     */
    public static String getADCPTimestamp() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(ADCP_FORMATTER);
    }

    /**
     * Converts bytes to a hex string for debugging.
     *
     * @param data Byte array
     * @return Hex string representation
     */
    public static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    /**
     * Converts bytes to a printable ASCII string, replacing non-printable characters.
     *
     * @param data Byte array
     * @return Printable string
     */
    public static String bytesToPrintable(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            if (b >= 32 && b < 127) {
                sb.append((char) b);
            } else if (b == '\r') {
                sb.append("\\r");
            } else if (b == '\n') {
                sb.append("\\n");
            } else if (b == '\t') {
                sb.append("\\t");
            } else {
                sb.append(String.format("\\x%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }

    /**
     * Ensures a directory exists, creating it if necessary.
     *
     * @param dir Directory path
     * @throws IOException if directory cannot be created
     */
    public static void ensureDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    /**
     * Creates a backup of a file.
     *
     * @param source Source file
     * @param backupDir Backup directory
     * @return Path to backup file
     * @throws IOException if backup fails
     */
    public static Path backupFile(Path source, Path backupDir) throws IOException {
        ensureDirectory(backupDir);
        Path backup = backupDir.resolve(source.getFileName());
        Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    /**
     * Appends text to a file.
     *
     * @param file File to append to
     * @param text Text to append
     * @throws IOException if append fails
     */
    public static void appendToFile(Path file, String text) throws IOException {
        ensureDirectory(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Makes a file read-only.
     *
     * @param file File to protect
     * @return true if successful
     */
    public static boolean makeReadOnly(Path file) {
        return file.toFile().setReadOnly();
    }

    /**
     * Gets the user's home directory.
     *
     * @return Home directory path
     */
    public static Path getHomeDirectory() {
        return Paths.get(System.getProperty("user.home"));
    }

    /**
     * Gets the default data directory for LADCP files.
     *
     * @return Default data directory
     */
    public static Path getDefaultDataDirectory() {
        return getHomeDirectory().resolve("data").resolve("ladcp_proc").resolve("raw_ladcp");
    }

    /**
     * Gets the default log directory.
     *
     * @return Default log directory
     */
    public static Path getDefaultLogDirectory() {
        return getHomeDirectory().resolve("data").resolve("ladcp_terminal_logs");
    }

    /**
     * Calculates the difference between two times.
     *
     * @param time1 First time
     * @param time2 Second time
     * @return Difference in seconds
     */
    public static long getTimeDifferenceSeconds(LocalDateTime time1, LocalDateTime time2) {
        return Duration.between(time1, time2).getSeconds();
    }

    /**
     * Parses an ADCP time response string.
     *
     * @param response Response string from TS? command
     * @return Parsed LocalDateTime or null if parsing fails
     */
    public static LocalDateTime parseADCPTimeResponse(String response) {
        // Response format varies by firmware:
        // TS = yy/MM/dd,HH:mm:ss  (firmware 51.36)
        // TS yy/MM/dd,HH:mm:ss    (firmware 51.40)
        try {
            int idx = response.indexOf("TS");
            if (idx < 0) return null;

            String after = response.substring(idx + 2).trim();
            if (after.startsWith("=")) {
                after = after.substring(1).trim();
            }

            // Extract the time portion
            String timeStr = after.split("\\s+")[0];
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yy/MM/dd,HH:mm:ss");
            return LocalDateTime.parse(timeStr, formatter);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validates a station_cast string format.
     *
     * @param stationCast Station_Cast string
     * @return true if valid format (NNN_NN)
     */
    public static boolean isValidStationCast(String stationCast) {
        return stationCast != null && stationCast.matches("\\d{3}_\\d{2}");
    }

    /**
     * Generates an LADCP-compatible filename.
     *
     * @param prefix Filename prefix
     * @param cruiseName Cruise name
     * @param stationCast Station_Cast identifier
     * @param suffix Filename suffix
     * @param extension File extension
     * @return Generated filename
     */
    public static String makeFilename(String prefix, String cruiseName, 
            String stationCast, String suffix, String extension) {
        if (!extension.startsWith(".")) {
            extension = "." + extension;
        }
        return String.format("%s%s_%s%s%s", prefix, cruiseName, stationCast, suffix, extension);
    }

    /**
     * Safely closes a resource without throwing exceptions.
     *
     * @param closeable Resource to close
     */
    public static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    /**
     * Sleep for the specified duration without throwing InterruptedException.
     *
     * @param millis Milliseconds to sleep
     * @return true if sleep completed, false if interrupted
     */
    public static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ==================== File Logging Control ====================

    /**
     * Enables the logback FILE appender on the root logger.
     * Creates the log directory and appender programmatically.
     *
     * @return true if the appender was successfully attached
     */
    public static boolean enableFileLogging() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger root = context.getLogger(
                ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);

        // Already attached?
        if (root.getAppender("FILE") != null) {
            return true;
        }

        try {
            Path logDir = getHomeDirectory().resolve("ladcp_logs");
            Files.createDirectories(logDir);

            ch.qos.logback.core.rolling.RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> fileAppender =
                    new ch.qos.logback.core.rolling.RollingFileAppender<>();
            fileAppender.setContext(context);
            fileAppender.setName("FILE");
            fileAppender.setFile(logDir.resolve("ladcp-terminal.log").toString());

            ch.qos.logback.core.rolling.TimeBasedRollingPolicy rollingPolicy =
                    new ch.qos.logback.core.rolling.TimeBasedRollingPolicy();
            rollingPolicy.setContext(context);
            rollingPolicy.setParent(fileAppender);
            rollingPolicy.setFileNamePattern(logDir.resolve("ladcp-terminal.%d{yyyy-MM-dd}.log").toString());
            rollingPolicy.setMaxHistory(30);
            rollingPolicy.start();

            fileAppender.setRollingPolicy(rollingPolicy);

            ch.qos.logback.classic.encoder.PatternLayoutEncoder encoder =
                    new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
            encoder.setContext(context);
            encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
            encoder.start();

            fileAppender.setEncoder(encoder);
            fileAppender.start();

            root.addAppender(fileAppender);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Disables the logback FILE appender on the root logger.
     *
     * @return true if the appender was found and detached
     */
    public static boolean disableFileLogging() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger root = context.getLogger(
                ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);

        ch.qos.logback.core.Appender<?> fileAppender = root.getAppender("FILE");
        if (fileAppender != null) {
            root.detachAppender("FILE");
            fileAppender.stop();
            return true;
        }
        return false;
    }

    /**
     * Checks if the FILE appender is currently attached to the root logger.
     *
     * @return true if file logging is active
     */
    public static boolean isFileLoggingEnabled() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger root = context.getLogger(
                ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        return root.getAppender("FILE") != null;
    }
}
