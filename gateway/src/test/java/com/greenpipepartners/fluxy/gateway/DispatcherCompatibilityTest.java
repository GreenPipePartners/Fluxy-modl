/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package com.greenpipepartners.fluxy.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DispatcherCompatibilityTest {
    @Test
    void usesFutureFacingIgnition83Names() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/fluxy_dispatch.py")) {
            assertNotNull(stream);
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(source.contains("system.date.format"));
            assertTrue(source.contains("system.historian.browse"));
            assertTrue(source.contains("system.historian.storeDataPoints"));
            assertTrue(source.contains("system.historian.queryRawPoints"));
            assertFalse(source.contains("system.db.dateFormat"));
            assertFalse(source.contains("system.tag.browseHistoricalTags"));
        }
    }
}
