/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package partners.greenpipe.fluxy.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import org.junit.jupiter.api.Test;

class FluxyRouteManifestTest {
    private static final Pattern DISPATCH_ENTRY = Pattern.compile(
        "\\\"([^\\\"]+)\\\"\\s*:\\s*_[A-Za-z0-9_]+"
    );
    private static final Set<String> WRITE_OPERATIONS = Set.of(
        "tag/configure",
        "tag/writeBlocking",
        "tag/deleteTags",
        "tag/copy",
        "tag/move",
        "tag/rename",
        "tag/importTags",
        "historian/storeDataPoints",
        "historian/storeAnnotations",
        "historian/deleteAnnotations",
        "historian/storeMetadata"
    );

    @Test
    void definesTheExpandedVersionedRouteContract() {
        FluxyRouteManifest manifest = FluxyRouteManifest.instance();

        assertEquals(2, manifest.contractVersion());
        assertEquals(28, manifest.routes().size());
        assertEquals(28, manifest.routesFor(FluxyRouteManifest.IGNITION_83).size());
        assertEquals(26, manifest.routesFor(FluxyRouteManifest.IGNITION_81).size());
        assertTrue(manifest.isWrite("tag/importTags"));
        assertTrue(manifest.isWrite("historian/storeAnnotations"));
        assertFalse(manifest.isWrite("tag/exportTags"));
        assertFalse(manifest.isWrite("util/getCapabilities"));
        for (FluxyRouteManifest.Route route : manifest.routes()) {
            FluxyRouteManifest.Access expectedAccess = WRITE_OPERATIONS.contains(route.operation())
                ? FluxyRouteManifest.Access.WRITE
                : FluxyRouteManifest.Access.READ;
            assertEquals(expectedAccess, route.access(), route.operation());
            FluxyRouteManifest.Handler expectedHandler = switch (route.operation()) {
                case "util/getCapabilities" -> FluxyRouteManifest.Handler.CAPABILITIES;
                case "historian/queryRawPointsStream" ->
                    FluxyRouteManifest.Handler.NATIVE_HISTORY_STREAM;
                case "project/requestScan" -> FluxyRouteManifest.Handler.PROJECT_SCAN;
                default -> FluxyRouteManifest.Handler.DISPATCH;
            };
            assertEquals(expectedHandler, route.handler(), route.operation());
            Set<String> expectedVersions = route.operation().equals("historian/queryMetadata")
                || route.operation().equals("historian/storeMetadata")
                ? Set.of("8.3")
                : Set.of("8.1", "8.3");
            assertEquals(expectedVersions, route.versions(), route.operation());
        }
    }

    @Test
    void dispatcherMatchesEveryAvailableDispatchRoute() throws Exception {
        String targetFamily = System.getProperty("fluxy.test.targetFamily");
        assertTrue(Set.of("8.1", "8.3").contains(targetFamily));

        String source;
        try (InputStream stream = getClass().getResourceAsStream("/fluxy_dispatch.py")) {
            assertNotNull(stream);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        Set<String> actual = new LinkedHashSet<>();
        Matcher matcher = DISPATCH_ENTRY.matcher(source.substring(source.indexOf("_OPERATIONS = {")));
        while (matcher.find()) {
            actual.add(matcher.group(1));
        }

        assertEquals(FluxyRouteManifest.instance().dispatchOperations(targetFamily), actual);
    }

    @Test
    void capabilitiesReportPlatformAvailability() {
        JsonObject ignition81 = new JsonParser().parse(
            FluxyRouteManifest.instance().capabilitiesJson("8.1", "test-version")
        ).getAsJsonObject();
        JsonObject ignition83 = new JsonParser().parse(
            FluxyRouteManifest.instance().capabilitiesJson("8.3", "test-version")
        ).getAsJsonObject();

        assertEquals(2, ignition81.get("contractVersion").getAsInt());
        assertEquals("test-version", ignition83.get("moduleVersion").getAsString());
        assertEquals(28, ignition81.getAsJsonArray("operations").size());
        assertEquals(28, ignition83.getAsJsonArray("operations").size());
        assertEquals(26, availableCount(ignition81));
        assertEquals(28, availableCount(ignition83));
    }

    private static int availableCount(JsonObject capabilities) {
        int count = 0;
        for (var operation : capabilities.getAsJsonArray("operations")) {
            if (operation.getAsJsonObject().get("available").getAsBoolean()) {
                count++;
            }
        }
        return count;
    }
}
