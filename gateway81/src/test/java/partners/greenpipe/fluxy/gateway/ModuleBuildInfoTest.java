/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package partners.greenpipe.fluxy.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModuleBuildInfoTest {
    @Test
    void readsTheGeneratedLicenseMode() {
        boolean expected = "free".equals(System.getProperty("fluxy.test.expectedLicenseMode"));
        assertEquals(expected, ModuleBuildInfo.isFreeModule());
        assertEquals(
            System.getProperty("fluxy.test.expectedModuleVersion"),
            ModuleBuildInfo.moduleVersion()
        );
    }
}
