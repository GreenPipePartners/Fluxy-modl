/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package partners.greenpipe.fluxy.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.common.sqltags.history.ReturnFormat;
import com.inductiveautomation.ignition.common.sqltags.history.TagHistoryQueryFlags;
import org.junit.jupiter.api.Test;

class NativeHistoryStreamerTest {
    @Test
    void buildsRawTallQueryWithExplicitSafetyFlags() {
        JsonObject payload = new JsonParser().parse("""
            {
              "paths": [
                "histprov:Core Historian:/sys:gateway:/prov:default:/tag:A",
                "histprov:Core Historian:/sys:gateway:/prov:default:/tag:B"
              ],
              "startTime": 1000,
              "endTime": 2000,
              "blockRows": 2500
            }
            """).getAsJsonObject();

        NativeHistoryStreamer.QueryRequest request = NativeHistoryStreamer.parseRequest(payload);

        assertEquals(-1, request.params().getReturnSize());
        assertEquals(ReturnFormat.Tall, request.params().getReturnFormat());
        assertEquals(2, request.params().getPaths().size());
        assertEquals(2, request.params().getAliases().size());
        assertEquals(2, request.params().getColumnAggregationModes().size());
        assertEquals(2500, request.blockRows());
        assertEquals("Core Historian", request.historyProvider());
        assertTrue(request.params().getQueryFlags().hasFlag(TagHistoryQueryFlags.NO_SEED_VALUES));
        assertTrue(request.params().getQueryFlags().hasFlag(TagHistoryQueryFlags.NO_INTERPOLATION));
        assertTrue(request.params().getQueryFlags().hasFlag(TagHistoryQueryFlags.BOUNDING_VALUES_NO));
        assertTrue(request.params().getQueryFlags().hasFlag(TagHistoryQueryFlags.NO_PREPROCESSED_DATA));
    }

    @Test
    void rejectsCrossProviderQueries() {
        JsonObject payload = new JsonParser().parse("""
            {
              "paths": [
                "histprov:Core Historian:/sys:gateway:/prov:default:/tag:A",
                "histprov:Other:/sys:gateway:/prov:default:/tag:B"
              ],
              "startTime": 1000,
              "endTime": 2000
            }
            """).getAsJsonObject();

        assertThrows(IllegalArgumentException.class, () -> NativeHistoryStreamer.parseRequest(payload));
    }

    @Test
    void emitsBoundedSequencedNdjsonBlocks() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NativeHistoryStreamer.BlockWriter writer = new NativeHistoryStreamer.BlockWriter(
            output,
            "query-1",
            2
        );
        writer.initialize(
            new String[] {"path", "value", "timestamp"},
            new Class<?>[] {String.class, Double.class, Date.class},
            false,
            -1
        );
        writer.write(new Object[] {"A", 1.5, new Date(1000)}, null);
        writer.write(new Object[] {"A", 2.5, new Date(2000)}, null);
        writer.write(new Object[] {"A", 3.5, new Date(3000)}, null);
        writer.finish();

        String[] lines = output.toString(StandardCharsets.UTF_8).trim().split("\\n");
        assertEquals(4, lines.length);
        JsonObject schema = new JsonParser().parse(lines[0]).getAsJsonObject();
        JsonObject firstBlock = new JsonParser().parse(lines[1]).getAsJsonObject();
        JsonObject secondBlock = new JsonParser().parse(lines[2]).getAsJsonObject();
        JsonObject end = new JsonParser().parse(lines[3]).getAsJsonObject();

        assertEquals("schema", schema.get("type").getAsString());
        assertEquals(1, schema.get("protocolVersion").getAsInt());
        assertEquals(0, firstBlock.get("sequence").getAsInt());
        assertEquals(2, firstBlock.get("rowCount").getAsInt());
        assertEquals(1000, firstBlock.get("firstTimestamp").getAsLong());
        assertEquals(2000, firstBlock.get("lastTimestamp").getAsLong());
        assertEquals(1, secondBlock.get("sequence").getAsInt());
        assertEquals(1, secondBlock.get("rowCount").getAsInt());
        assertEquals("end", end.get("type").getAsString());
        assertEquals(3, end.get("rows").getAsLong());
        assertEquals(2, end.get("blocks").getAsLong());
        assertTrue(writer.result().succeeded());
    }

    @Test
    void emitsPendingRowsBeforeTerminalQueryError() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NativeHistoryStreamer.BlockWriter writer = new NativeHistoryStreamer.BlockWriter(
            output,
            "query-error",
            100
        );
        writer.initialize(
            new String[] {"value", "timestamp"},
            new Class<?>[] {Double.class, Date.class},
            false,
            -1
        );
        writer.write(new Object[] {1.5, new Date(1000)}, null);
        writer.finishWithError(new IllegalStateException("sensitive provider detail"));

        String[] lines = output.toString(StandardCharsets.UTF_8).trim().split("\\n");
        assertEquals(3, lines.length);
        JsonObject block = new JsonParser().parse(lines[1]).getAsJsonObject();
        JsonObject error = new JsonParser().parse(lines[2]).getAsJsonObject();

        assertEquals("block", block.get("type").getAsString());
        assertEquals(1, block.get("rowCount").getAsInt());
        assertEquals("error", error.get("type").getAsString());
        assertEquals("HISTORY_QUERY_FAILED", error.get("code").getAsString());
        assertEquals("History query failed", error.get("message").getAsString());
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("sensitive provider detail"));
    }
}
