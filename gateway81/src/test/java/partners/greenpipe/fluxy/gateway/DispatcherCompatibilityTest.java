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
    void usesOnlyIgnition81NamesForVersionSensitiveOperations() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/fluxy_dispatch.py")) {
            assertNotNull(stream);
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(source.contains("system.db.dateFormat"));
            assertTrue(source.contains("system.tag.browseHistoricalTags"));
            assertTrue(source.contains("Historian browse failed: %s"));
            assertTrue(source.contains("system.tag.storeTagHistory"));
            assertTrue(source.contains("system.tag.queryTagHistory"));
            assertTrue(source.contains("system.tag.queryTagCalculations"));
            assertTrue(source.contains("system.tag.storeAnnotations"));
            assertTrue(source.contains("system.tag.queryAnnotations"));
            assertTrue(source.contains("system.tag.importTags"));
            assertTrue(source.contains("system.tag.query"));
            assertTrue(source.contains("system.util.getModules"));
            assertTrue(source.contains("system.project.getProjectNames"));
            assertFalse(source.contains("system.date.format"));
            assertFalse(source.contains("system.historian."));
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
