/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package partners.greenpipe.fluxy.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.python.util.PythonInterpreter;

class DispatcherCompatibilityTest {
    @Test
    void usesFutureFacingIgnition83Names() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/fluxy_dispatch.py")) {
            assertNotNull(stream);
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(source.contains("system.date.format"));
            assertTrue(source.contains("system.historian.browse"));
            assertTrue(source.contains("continuationPoint=continuation"));
            assertTrue(source.contains("Historian browse failed: %s"));
            assertTrue(source.contains("system.historian.storeDataPoints"));
            assertTrue(source.contains("system.historian.queryRawPoints"));
            assertTrue(source.contains("system.historian.queryAggregatedPoints"));
            assertTrue(source.contains("system.historian.storeAnnotations"));
            assertTrue(source.contains("system.historian.queryMetadata"));
            assertTrue(source.contains("system.tag.importTags"));
            assertTrue(source.contains("system.tag.query"));
            assertTrue(source.contains("system.util.getModules"));
            assertTrue(source.contains("system.project.getProjectNames"));
            assertFalse(source.contains("system.db.dateFormat"));
            assertFalse(source.contains("system.tag.browseHistoricalTags"));
        }
    }

    @Test
    void compilesAsJython27() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/fluxy_dispatch.py")) {
            assertNotNull(stream);
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            Properties properties = new Properties(System.getProperties());
            properties.setProperty("python.import.site", "false");
            PythonInterpreter.initialize(properties, properties, new String[0]);
            PythonInterpreter interpreter = new PythonInterpreter();
            try {
                interpreter.exec(source);
            } finally {
                interpreter.close();
            }
        }
    }
}
