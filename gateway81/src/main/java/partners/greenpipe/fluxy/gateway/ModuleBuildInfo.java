/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package partners.greenpipe.fluxy.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class ModuleBuildInfo {
    private static final String RESOURCE = "/fluxy-build.properties";

    private ModuleBuildInfo() {
    }

    static boolean isFreeModule() {
        Properties properties = new Properties();
        try (InputStream stream = ModuleBuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            properties.load(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + RESOURCE, exception);
        }
        return "free".equals(properties.getProperty("licenseMode"));
    }
}
