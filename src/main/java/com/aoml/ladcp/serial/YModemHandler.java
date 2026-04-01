package com.aoml.ladcp.serial;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;

/**
 * Pure Java YModem file transfer handler for ADCP data downloads.
 * Implements YModem-1K protocol with CRC-16 error checking.
 * 
 * This is a self-contained implementation with no external dependencies,
 * designed for cross-platform compatibility (Windows, macOS, Linux).
 */
public class YModemHandler {
    private static final Logger log = LoggerFactory.getLogger(YModemHandler.class);

    // YModem protocol constants
    private static final byte SOH = 0x01;   // Start of 128-byte header
    private static final byte STX = 0x02;   // Start of 1024-byte header
    private static final byte EOT = 0x04;   // End of transmission
    private static final byte ACK = 0x06;   // Acknowledge
    private static final byte NAK = 0x15;   // Negative acknowledge
    private static final byte CAN = 0x18;   // Cancel
    private static final byte CRC_CHAR = 'C';  // CRC mode request

    private static final int BLOCK_SIZE_128 = 128;
    private static final int BLOCK_SIZE_1K = 1024;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int INITIAL_TIMEOUT_MS = 60000;
    private static final int RETRY_LIMIT = 10;
    private static final int CRC_RETRY_LIMIT = 3;

    private final SerialPortManager portManager;
    private volatile boolean cancelled = false;
    private int retryCount = 0;
    private int totalBytesReceived = 0;

    private ProgressListener progressListener;

    public YModemHandler(SerialPortManager portManager) {
        this.portManager = portManager;
    }

    /**
     * Interface for progress updates during download.
     */
    public interface ProgressListener {
        void onProgress(int bytesReceived, int totalBytes, String message);
        void onComplete(Path downloadedFile);
        void onError(String error);
    }

    /**
     * Sets the progress listener.
     *
     * @param listener Progress listener
     */
    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    /**
     * Downloads a file using YModem protocol.
     *
     * @param downloadDir Directory to save downloaded file
     * @return Path to downloaded file
     * @throws IOException if download fails
     */
    public Path download(Path downloadDir) throws IOException {
        cancelled = false;
        retryCount = 0;
        totalBytesReceived = 0;

        Files.createDirectories(downloadDir);
        log.info("Starting YModem download to {}", downloadDir);

        InputStream in = portManager.getInputStream();
        OutputStream out = portManager.getOutputStream();

        // Clear any pending data
        clearInputStream(in);

        // Initiate transfer by sending 'C' for CRC mode
        // This returns the first header byte (SOH/STX) or -1 if failed
        int firstHeader = initiateTransfer(in, out);
        if (firstHeader < 0) {
            throw new IOException("Failed to initiate YModem transfer");
        }

        // Receive file header (block 0) - pass the already-received header byte
        byte[] header = receiveBlockWithHeader(in, out, 0, firstHeader);
        if (header == null) {
            throw new IOException("Failed to receive file header");
        }

        // Parse filename and size from header
        String filename = parseFilename(header);
        long fileSize = parseFileSize(header);
        
        if (filename.isEmpty()) {
            // Empty filename means end of batch
            log.info("Received empty filename - end of transfer");
            out.write(ACK);
            out.flush();
            throw new IOException("No file to transfer");
        }

        Path outputFile = downloadDir.resolve(sanitizeFilename(filename));
        log.info("Downloading: {} ({} bytes)", filename, fileSize > 0 ? fileSize : "unknown");

        reportProgress(0, (int) fileSize, "Starting download: " + filename);

        // ACK the header and send C to start data blocks
        out.write(ACK);
        out.flush();
        Thread.yield();
        out.write(CRC_CHAR);
        out.flush();

        // Create output file and receive data blocks
        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(outputFile))) {
            int blockNum = 1;
            long bytesWritten = 0;

            while (!cancelled) {
                byte[] data = receiveBlock(in, out, blockNum);
                
                if (data == null) {
                    // EOT received - end of file
                    log.debug("EOT received after {} blocks", blockNum - 1);
                    break;
                }

                // For the last block, trim padding if we know the file size
                int writeLength = data.length;
                if (fileSize > 0 && bytesWritten + data.length > fileSize) {
                    writeLength = (int) (fileSize - bytesWritten);
                }

                fileOut.write(data, 0, writeLength);
                bytesWritten += writeLength;
                totalBytesReceived += data.length;
                blockNum++;

                reportProgress((int) bytesWritten, (int) fileSize,
                        String.format("Block %d received (%d bytes)", blockNum - 1, bytesWritten));
            }

            if (cancelled) {
                sendCancel(out);
                throw new IOException("Download cancelled by user");
            }
        }

        // Handle end of batch - receive empty header
        try {
            out.write(CRC_CHAR);
            out.flush();
            byte[] endHeader = receiveBlock(in, out, 0);
            if (endHeader != null) {
                out.write(ACK);
                out.flush();
            }
        } catch (IOException e) {
            // End of batch handling may fail, that's OK
            log.debug("End of batch handling: {}", e.getMessage());
        }

        log.info("Download complete: {} ({} bytes, {} retries)", 
                outputFile.getFileName(), totalBytesReceived, retryCount);

        if (progressListener != null) {
            progressListener.onComplete(outputFile);
        }

        return outputFile;
    }

    /**
     * Cancels an ongoing download.
     */
    public void cancel() {
        cancelled = true;
    }

    /**
     * Gets the retry count from the last download.
     *
     * @return Retry count
     */
    public int getRetryCount() {
        return retryCount;
    }

    /**
     * Initiates the YModem transfer by requesting CRC mode.
     * Returns the first header byte (SOH or STX) received, or -1 if failed.
     */
    private int initiateTransfer(InputStream in, OutputStream out) throws IOException {
        log.debug("Initiating YModem CRC transfer");

        for (int i = 0; i < CRC_RETRY_LIMIT; i++) {
            if (cancelled) return -1;

            out.write(CRC_CHAR);
            out.flush();
            log.debug("Sent 'C' for CRC mode, attempt {}", i + 1);

            // Wait for response - look for SOH or STX
            try {
                int response = readByteWithTimeout(in, INITIAL_TIMEOUT_MS / CRC_RETRY_LIMIT);
                if (response == SOH || response == STX) {
                    log.debug("Received block start: 0x{}", String.format("%02X", response));
                    // Return the header byte so receiveBlock can use it
                    // DO NOT send ACK here - we haven't received the full block yet!
                    return response;
                } else {
                    log.debug("Unexpected response during initiation: 0x{}", String.format("%02X", response));
                }
            } catch (IOException e) {
                log.debug("No response to CRC request, retrying...");
            }
        }

        return -1;
    }

    /**
     * Receives a YModem block when the header byte has already been read.
     *
     * @param in Input stream
     * @param out Output stream  
     * @param expectedBlock Expected block number (0-255)
     * @param headerByte The already-received header byte (SOH or STX)
     * @return Block data or null if EOT received
     */
    private byte[] receiveBlockWithHeader(InputStream in, OutputStream out, int expectedBlock, int headerByte) 
            throws IOException {
        
        int expected = expectedBlock & 0xFF;
        int blockSize;
        
        if (headerByte == SOH) {
            blockSize = BLOCK_SIZE_128;
        } else if (headerByte == STX) {
            blockSize = BLOCK_SIZE_1K;
        } else {
            throw new IOException("Invalid header byte passed to receiveBlockWithHeader: 0x" + 
                    String.format("%02X", headerByte));
        }

        // Read block number and complement
        int blockNum = readByteWithTimeout(in, READ_TIMEOUT_MS);
        int blockNumComp = readByteWithTimeout(in, READ_TIMEOUT_MS);

        log.debug("Block header: size={}, num={}, comp={}", blockSize, blockNum, blockNumComp);

        // Verify block number complement
        if (((blockNum + blockNumComp) & 0xFF) != 0xFF) {
            log.debug("Block number complement mismatch: {} + {} != 255", blockNum, blockNumComp);
            throw new IOException("Block number complement mismatch");
        }

        // Check if this is the expected block
        if (blockNum != expected) {
            log.debug("Unexpected block number: got {}, expected {}", blockNum, expected);
            throw new IOException("Unexpected block number: got " + blockNum + ", expected " + expected);
        }

        // Read data
        byte[] data = readBytes(in, blockSize);

        // Read CRC (2 bytes, big-endian)
        int crcHigh = readByteWithTimeout(in, READ_TIMEOUT_MS);
        int crcLow = readByteWithTimeout(in, READ_TIMEOUT_MS);
        int receivedCrc = ((crcHigh & 0xFF) << 8) | (crcLow & 0xFF);

        // Verify CRC
        int calculatedCrc = calculateCRC16(data);
        if (calculatedCrc != receivedCrc) {
            log.debug("CRC mismatch: calculated=0x{}, received=0x{}", 
                    String.format("%04X", calculatedCrc),
                    String.format("%04X", receivedCrc));
            throw new IOException("CRC mismatch");
        }

        // Block received successfully
        log.debug("Block {} received successfully ({} bytes)", blockNum, blockSize);
        return data;
    }

    /**
     * Receives a YModem block.
     *
     * @param in Input stream
     * @param out Output stream  
     * @param expectedBlock Expected block number (0-255)
     * @return Block data or null if EOT received
     */
    private byte[] receiveBlock(InputStream in, OutputStream out, int expectedBlock) 
            throws IOException {
        
        int expected = expectedBlock & 0xFF;

        for (int retry = 0; retry < RETRY_LIMIT; retry++) {
            if (cancelled) {
                return null;
            }

            try {
                int header = readByteWithTimeout(in, READ_TIMEOUT_MS);
                
                if (header == EOT) {
                    log.debug("Received EOT");
                    out.write(ACK);
                    out.flush();
                    return null;
                }

                if (header == CAN) {
                    // Check for double CAN
                    try {
                        int second = readByteWithTimeout(in, 1000);
                        if (second == CAN) {
                            throw new IOException("Transfer cancelled by sender");
                        }
                    } catch (IOException e) {
                        // Single CAN, treat as error
                    }
                    retryCount++;
                    sendNak(out);
                    continue;
                }

                int blockSize;
                if (header == SOH) {
                    blockSize = BLOCK_SIZE_128;
                } else if (header == STX) {
                    blockSize = BLOCK_SIZE_1K;
                } else {
                    log.debug("Invalid header byte: 0x{}", String.format("%02X", header));
                    retryCount++;
                    clearInputStream(in);
                    sendNak(out);
                    continue;
                }

                // Read block number and complement
                int blockNum = readByteWithTimeout(in, READ_TIMEOUT_MS);
                int blockNumComp = readByteWithTimeout(in, READ_TIMEOUT_MS);

                log.debug("Block header: size={}, num={}, comp={}", blockSize, blockNum, blockNumComp);

                // Verify block number complement
                if (((blockNum + blockNumComp) & 0xFF) != 0xFF) {
                    log.debug("Block number complement mismatch: {} + {} != 255", blockNum, blockNumComp);
                    retryCount++;
                    clearInputStream(in);
                    sendNak(out);
                    continue;
                }

                // Check if this is the expected block or a duplicate
                if (blockNum != expected) {
                    if (blockNum == ((expected - 1) & 0xFF)) {
                        // Duplicate of previous block - ACK and continue
                        log.debug("Duplicate block {} received, re-ACKing", blockNum);
                        byte[] data = readBytes(in, blockSize);
                        readBytes(in, 2); // Read and discard CRC
                        out.write(ACK);
                        out.flush();
                        continue;
                    } else {
                        log.debug("Unexpected block number: got {}, expected {}", blockNum, expected);
                        retryCount++;
                        clearInputStream(in);
                        sendNak(out);
                        continue;
                    }
                }

                // Read data
                byte[] data = readBytes(in, blockSize);

                // Read CRC (2 bytes, big-endian)
                int crcHigh = readByteWithTimeout(in, READ_TIMEOUT_MS);
                int crcLow = readByteWithTimeout(in, READ_TIMEOUT_MS);
                int receivedCrc = ((crcHigh & 0xFF) << 8) | (crcLow & 0xFF);

                // Verify CRC
                int calculatedCrc = calculateCRC16(data);
                if (calculatedCrc != receivedCrc) {
                    log.debug("CRC mismatch: calculated=0x{}, received=0x{}", 
                            String.format("%04X", calculatedCrc),
                            String.format("%04X", receivedCrc));
                    retryCount++;
                    sendNak(out);
                    continue;
                }

                // Block received successfully
                log.debug("Block {} received successfully ({} bytes)", blockNum, blockSize);
                out.write(ACK);
                out.flush();
                return data;

            } catch (IOException e) {
                log.debug("Error receiving block: {}", e.getMessage());
                retryCount++;
                if (retry < RETRY_LIMIT - 1) {
                    sendNak(out);
                }
            }
        }

        throw new IOException("Too many retries receiving block " + expectedBlock);
    }

    /**
     * Reads a single byte with timeout.
     */
    private int readByteWithTimeout(InputStream in, int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        
        while (System.currentTimeMillis() < deadline) {
            if (cancelled) {
                throw new IOException("Download cancelled");
            }
            if (in.available() > 0) {
                int b = in.read();
                if (b < 0) {
                    throw new IOException("End of stream");
                }
                return b & 0xFF;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading");
            }
        }
        throw new IOException("Read timeout after " + timeoutMs + "ms");
    }

    /**
     * Reads exactly the specified number of bytes.
     */
    private byte[] readBytes(InputStream in, int count) throws IOException {
        byte[] data = new byte[count];
        int totalRead = 0;
        long deadline = System.currentTimeMillis() + READ_TIMEOUT_MS;

        while (totalRead < count && System.currentTimeMillis() < deadline) {
            if (cancelled) {
                throw new IOException("Download cancelled");
            }
            if (in.available() > 0) {
                int read = in.read(data, totalRead, count - totalRead);
                if (read < 0) {
                    throw new IOException("Unexpected end of stream");
                }
                totalRead += read;
            } else {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while reading");
                }
            }
        }

        if (totalRead < count) {
            throw new IOException("Timeout reading " + count + " bytes, got " + totalRead);
        }

        return data;
    }

    /**
     * Clears any pending data from the input stream.
     */
    private void clearInputStream(InputStream in) {
        try {
            while (in.available() > 0) {
                in.read();
            }
        } catch (IOException e) {
            // Ignore
        }
    }

    /**
     * Sends NAK to request retransmission.
     */
    private void sendNak(OutputStream out) throws IOException {
        out.write(NAK);
        out.flush();
    }

    /**
     * Sends cancel sequence.
     */
    private void sendCancel(OutputStream out) throws IOException {
        byte[] cancel = {CAN, CAN, CAN, CAN, CAN};
        out.write(cancel);
        out.flush();
    }

    /**
     * Reports progress to listener.
     */
    private void reportProgress(int bytesReceived, int totalBytes, String message) {
        if (progressListener != null) {
            progressListener.onProgress(bytesReceived, totalBytes, message);
        }
    }

    /**
     * Parses filename from YModem header block (block 0).
     * Format: filename\0size\0[additional info]
     */
    private String parseFilename(byte[] header) {
        int end = 0;
        while (end < header.length && header[end] != 0) {
            end++;
        }
        if (end == 0) {
            return "";
        }
        return new String(header, 0, end).trim();
    }

    /**
     * Parses file size from YModem header block.
     */
    private long parseFileSize(byte[] header) {
        // Find start of size (after null terminator of filename)
        int start = 0;
        while (start < header.length && header[start] != 0) {
            start++;
        }
        start++; // Skip null

        if (start >= header.length) {
            return -1;
        }

        // Find end of size (space or null)
        int end = start;
        while (end < header.length && header[end] != 0 && header[end] != ' ') {
            end++;
        }

        if (start >= end) {
            return -1;
        }

        try {
            return Long.parseLong(new String(header, start, end - start).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Sanitizes filename for safe filesystem use.
     */
    private String sanitizeFilename(String filename) {
        // Remove path separators and invalid characters
        String safe = filename.replaceAll("[/\\\\:*?\"<>|]", "_");
        // Ensure it's not empty
        if (safe.isEmpty()) {
            safe = "download.dat";
        }
        return safe;
    }

    /**
     * Calculates CRC-16-CCITT for YModem.
     * Polynomial: 0x1021, Initial value: 0
     */
    private int calculateCRC16(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc = crc ^ ((b & 0xFF) << 8);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc = crc << 1;
                }
            }
            crc &= 0xFFFF;
        }
        return crc;
    }
}
