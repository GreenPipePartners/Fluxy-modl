/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package partners.greenpipe.fluxy.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.inductiveautomation.ignition.common.Path;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.StreamingDatasetWriter;
import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonNull;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonPrimitive;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.sqltags.history.Aggregate;
import com.inductiveautomation.ignition.common.sqltags.history.AggregationMode;
import com.inductiveautomation.ignition.common.sqltags.history.BasicTagHistoryQueryParams;
import com.inductiveautomation.ignition.common.sqltags.history.ReturnFormat;
import com.inductiveautomation.ignition.common.sqltags.history.TagHistoryQueryFlags;
import com.inductiveautomation.ignition.common.util.Flags;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

final class NativeHistoryStreamer {
    static final String CONTENT_TYPE = "application/x-ndjson";
    static final int PROTOCOL_VERSION = 1;
    static final int DEFAULT_BLOCK_ROWS = 1_000;
    static final int MAX_BLOCK_ROWS = 5_000;
    static final int MAX_PATHS = 128;
    static final int MAX_BLOCK_BYTES = 4 * 1024 * 1024;
    static final long MAX_TOTAL_DATA_BYTES = 1024L * 1024L * 1024L;
    static final long MAX_DURATION_NANOS = TimeUnit.MINUTES.toNanos(30);

    private static final int MAX_PATH_LENGTH = 4_096;
    private static final Pattern HISTORY_PROVIDER = Pattern.compile("^histprov:([^:]+):/");

    private NativeHistoryStreamer() {
    }

    static QueryRequest parseRequest(JsonObject payload) {
        if (!payload.has("paths") || !payload.get("paths").isJsonArray()) {
            throw new IllegalArgumentException("Request must include paths list");
        }
        JsonArray rawPaths = payload.getAsJsonArray("paths");
        if (rawPaths.size() == 0 || rawPaths.size() > MAX_PATHS) {
            throw new IllegalArgumentException("paths must contain between 1 and " + MAX_PATHS + " items");
        }

        List<Path> paths = new ArrayList<>(rawPaths.size());
        List<String> aliases = new ArrayList<>(rawPaths.size());
        List<Aggregate> columnModes = new ArrayList<>(rawPaths.size());
        Set<String> providers = new HashSet<>();
        for (int index = 0; index < rawPaths.size(); index++) {
            JsonElement element = rawPaths.get(index);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("paths must contain strings");
            }
            String rawPath = element.getAsString();
            if (rawPath.length() > MAX_PATH_LENGTH) {
                throw new IllegalArgumentException("historical path exceeds " + MAX_PATH_LENGTH + " characters");
            }
            Matcher matcher = HISTORY_PROVIDER.matcher(rawPath);
            if (!matcher.find()) {
                throw new IllegalArgumentException("historical paths must start with histprov:");
            }
            QualifiedPath path = QualifiedPath.parseSafe(rawPath);
            if (path == null) {
                throw new IllegalArgumentException("invalid historical path");
            }
            providers.add(matcher.group(1));
            paths.add(path);
            aliases.add("value_" + index);
            columnModes.add(AggregationMode.LastValue);
        }
        if (providers.size() != 1) {
            throw new IllegalArgumentException("all paths must use the same history provider");
        }

        long startTime = requiredLong(payload, "startTime");
        long endTime = requiredLong(payload, "endTime");
        if (endTime <= startTime) {
            throw new IllegalArgumentException("endTime must be greater than startTime");
        }
        int blockRows = payload.has("blockRows") ? payload.get("blockRows").getAsInt() : DEFAULT_BLOCK_ROWS;
        if (blockRows < 1 || blockRows > MAX_BLOCK_ROWS) {
            throw new IllegalArgumentException("blockRows must be between 1 and " + MAX_BLOCK_ROWS);
        }

        BasicTagHistoryQueryParams params = new BasicTagHistoryQueryParams(
            paths,
            new Date(startTime),
            new Date(endTime),
            -1,
            AggregationMode.LastValue,
            ReturnFormat.Tall,
            aliases,
            columnModes
        );
        params.setQueryFlags(Flags.of(
            TagHistoryQueryFlags.NO_SEED_VALUES,
            TagHistoryQueryFlags.NO_INTERPOLATION,
            TagHistoryQueryFlags.BOUNDING_VALUES_NO,
            TagHistoryQueryFlags.NO_PREPROCESSED_DATA
        ));
        return new QueryRequest(params, blockRows, startTime, endTime, providers.iterator().next());
    }

    static StreamResult stream(
        GatewayContext context,
        QueryRequest request,
        OutputStream output,
        String queryId
    ) {
        BlockWriter writer = new BlockWriter(output, queryId, request.blockRows());
        try {
            context.getTagHistoryManager().queryHistory(request.params(), writer);
        } catch (RuntimeException exception) {
            writer.failOutsideQuery(exception);
        }
        return writer.result();
    }

    private static long requiredLong(JsonObject payload, String key) {
        if (!payload.has(key) || !payload.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("Request must include " + key);
        }
        try {
            return payload.get(key).getAsLong();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(key + " must be an integer timestamp", exception);
        }
    }

    record QueryRequest(
        BasicTagHistoryQueryParams params,
        int blockRows,
        long startTime,
        long endTime,
        String historyProvider
    ) {
    }

    record StreamResult(long rows, long blocks, long bytes, Exception error, boolean disconnected) {
        boolean succeeded() {
            return error == null && !disconnected;
        }
    }

    static final class BlockWriter implements StreamingDatasetWriter {
        private final OutputStream output;
        private final String queryId;
        private final int blockRows;
        private final long started = System.nanoTime();
        private JsonArray rows = new JsonArray();
        private JsonArray qualities = new JsonArray();

        private String[] columnNames = new String[0];
        private Class<?>[] columnTypes = new Class<?>[0];
        private boolean supportsQuality;
        private boolean initialized;
        private boolean terminal;
        private boolean disconnected;
        private long rowCount;
        private long blockCount;
        private long bytesWritten;
        private int pendingBytes;
        private Long firstTimestamp;
        private Long lastTimestamp;
        private Exception error;

        BlockWriter(OutputStream output, String queryId, int blockRows) {
            this.output = output;
            this.queryId = queryId;
            this.blockRows = blockRows;
        }

        @Override
        public void initialize(
            String[] columnNames,
            Class<?>[] columnTypes,
            boolean supportsQuality,
            int expectedRows
        ) {
            this.columnNames = columnNames.clone();
            this.columnTypes = columnTypes.clone();
            this.supportsQuality = supportsQuality;

            JsonObject event = event("schema");
            event.addProperty("protocolVersion", PROTOCOL_VERSION);
            event.add("columns", strings(this.columnNames));
            JsonArray types = new JsonArray();
            for (Class<?> type : this.columnTypes) {
                types.add(type == null ? "java.lang.Object" : type.getName());
            }
            event.add("columnTypes", types);
            event.addProperty("supportsQuality", supportsQuality);
            event.addProperty("expectedRows", expectedRows);
            try {
                emit(event, false);
                output.flush();
                initialized = true;
            } catch (IOException exception) {
                disconnected = true;
                throw new UncheckedIOException(exception);
            }
        }

        @Override
        public void write(Object[] data, QualityCode[] quality) throws IOException {
            if (terminal) {
                throw new IOException("history stream is already complete");
            }
            if (!initialized) {
                throw new IOException("history stream was not initialized");
            }
            if (System.nanoTime() - started > MAX_DURATION_NANOS) {
                throw new ExportLimitException("HISTORY_DURATION_LIMIT", "History export exceeded duration limit");
            }

            JsonArray row = new JsonArray();
            Long timestamp = null;
            for (Object value : data) {
                row.add(toJson(value));
                if (timestamp == null && value instanceof Date date) {
                    timestamp = date.getTime();
                }
            }
            JsonArray rowQuality = new JsonArray();
            if (quality != null) {
                for (QualityCode code : quality) {
                    rowQuality.add(code == null ? JsonNull.INSTANCE : new JsonPrimitive(code.getCode()));
                }
            }

            int rowBytes = utf8Size(row) + utf8Size(rowQuality) + 2;
            if (rowBytes > MAX_BLOCK_BYTES - 16_384) {
                throw new ExportLimitException("HISTORY_ROW_TOO_LARGE", "History row exceeds block byte limit");
            }
            if (rows.size() > 0 && pendingBytes + rowBytes > MAX_BLOCK_BYTES - 16_384) {
                flushBlock();
            }
            rows.add(row);
            qualities.add(rowQuality);
            pendingBytes += rowBytes;
            rowCount++;
            if (firstTimestamp == null && timestamp != null) {
                firstTimestamp = timestamp;
            }
            if (timestamp != null) {
                lastTimestamp = timestamp;
            }
            if (rows.size() >= blockRows) {
                flushBlock();
            }
        }

        @Override
        public void finish() {
            if (terminal || disconnected) {
                return;
            }
            try {
                flushBlock();
                JsonObject event = event("end");
                event.addProperty("rows", rowCount);
                event.addProperty("blocks", blockCount);
                event.addProperty("bytes", bytesWritten);
                emit(event, false);
                output.flush();
                terminal = true;
            } catch (ExportLimitException exception) {
                finishWithError(exception);
            } catch (IOException exception) {
                disconnected = true;
                error = exception;
            }
        }

        @Override
        public void finishWithError(Exception exception) {
            if (terminal || disconnected) {
                return;
            }
            error = exception;
            if (isDisconnect(exception)) {
                disconnected = true;
                return;
            }
            try {
                if (exception instanceof ExportLimitException) {
                    rows = new JsonArray();
                    qualities = new JsonArray();
                    pendingBytes = 0;
                    firstTimestamp = null;
                    lastTimestamp = null;
                } else {
                    flushBlock();
                }
                JsonObject event = event("error");
                if (exception instanceof ExportLimitException limit) {
                    event.addProperty("code", limit.code());
                    event.addProperty("message", limit.getMessage());
                } else {
                    event.addProperty("code", "HISTORY_QUERY_FAILED");
                    event.addProperty("message", "History query failed");
                }
                event.addProperty("rows", rowCount);
                event.addProperty("blocks", blockCount);
                emit(event, false);
                output.flush();
                terminal = true;
            } catch (IOException ioException) {
                disconnected = true;
            }
        }

        void failOutsideQuery(RuntimeException exception) {
            if (!terminal) {
                finishWithError(exception);
            }
        }

        StreamResult result() {
            return new StreamResult(rowCount, blockCount, bytesWritten, error, disconnected);
        }

        private void flushBlock() throws IOException {
            if (rows.size() == 0) {
                return;
            }
            JsonObject event = event("block");
            event.addProperty("sequence", blockCount);
            event.addProperty("rowCount", rows.size());
            if (firstTimestamp != null) {
                event.addProperty("firstTimestamp", firstTimestamp);
            }
            if (lastTimestamp != null) {
                event.addProperty("lastTimestamp", lastTimestamp);
            }
            event.add("rows", rows.deepCopy());
            if (supportsQuality) {
                event.add("qualities", qualities.deepCopy());
            }
            emit(event, true);
            output.flush();
            rows = new JsonArray();
            qualities = new JsonArray();
            pendingBytes = 0;
            firstTimestamp = null;
            lastTimestamp = null;
            blockCount++;
        }

        private JsonObject event(String type) {
            JsonObject event = new JsonObject();
            event.addProperty("type", type);
            event.addProperty("queryId", queryId);
            return event;
        }

        private void emit(JsonObject event, boolean dataBlock) throws IOException {
            byte[] line = (event.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            if (line.length > MAX_BLOCK_BYTES && dataBlock) {
                throw new ExportLimitException("HISTORY_BLOCK_TOO_LARGE", "History block exceeds byte limit");
            }
            if (dataBlock && bytesWritten + line.length > MAX_TOTAL_DATA_BYTES) {
                throw new ExportLimitException("HISTORY_EXPORT_TOO_LARGE", "History export exceeds total byte limit");
            }
            try {
                output.write(line);
                bytesWritten += line.length;
            } catch (IOException exception) {
                disconnected = true;
                throw exception;
            }
        }

        private static JsonArray strings(String[] values) {
            JsonArray result = new JsonArray();
            for (String value : values) {
                result.add(value);
            }
            return result;
        }

        private static JsonElement toJson(Object value) {
            if (value == null) {
                return JsonNull.INSTANCE;
            }
            if (value instanceof Date date) {
                return new JsonPrimitive(date.getTime());
            }
            if (value instanceof Boolean bool) {
                return new JsonPrimitive(bool);
            }
            if (value instanceof Number number) {
                double doubleValue = number.doubleValue();
                return Double.isFinite(doubleValue) ? new JsonPrimitive(number) : JsonNull.INSTANCE;
            }
            if (value instanceof Character character) {
                return new JsonPrimitive(character);
            }
            return new JsonPrimitive(String.valueOf(value));
        }

        private static int utf8Size(JsonElement value) {
            return value.toString().getBytes(StandardCharsets.UTF_8).length;
        }

        private static boolean isDisconnect(Throwable throwable) {
            for (Throwable current = throwable; current != null; current = current.getCause()) {
                if (current instanceof IOException && !(current instanceof ExportLimitException)) {
                    return true;
                }
                if (current instanceof UncheckedIOException) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class ExportLimitException extends IOException {
        private final String code;

        private ExportLimitException(String code, String message) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
