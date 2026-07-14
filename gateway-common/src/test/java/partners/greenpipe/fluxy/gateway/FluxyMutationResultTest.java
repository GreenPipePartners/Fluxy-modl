/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package partners.greenpipe.fluxy.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FluxyMutationResultTest {
    @Test
    void requiresSuccessfulHttpAndItemQualities() {
        assertTrue(FluxyRoutes.mutationSucceeded(
            200,
            "{\"ok\":true,\"qualities\":[{\"quality\":\"Good\"},\"Good_Unspecified\"]}"
        ));
        assertTrue(FluxyRoutes.mutationSucceeded(
            200,
            "{\"ok\":true,\"quality\":{\"quality\":\"Good\"}}"
        ));
        assertFalse(FluxyRoutes.mutationSucceeded(
            200,
            "{\"ok\":true,\"qualities\":[{\"quality\":\"Bad_Failure\"}]}"
        ));
        assertFalse(FluxyRoutes.mutationSucceeded(500, "{\"ok\":false}"));
        assertFalse(FluxyRoutes.mutationSucceeded(200, "not-json"));
    }
}
