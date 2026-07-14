/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package partners.greenpipe.fluxy.gateway;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;

final class ProjectScanOperations {
    static final int DEFAULT_TIMEOUT_SECONDS = 10;
    static final int MAX_TIMEOUT_SECONDS = 300;

    private ProjectScanOperations() {
    }

    static ScanResult requestScan(GatewayContext context, int timeoutSeconds)
        throws InterruptedException, ExecutionException, TimeoutException {
        if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(
                "timeoutSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS
            );
        }
        long started = System.nanoTime();
        context.getProjectManager().requestScan().get(timeoutSeconds, TimeUnit.SECONDS);
        return new ScanResult((System.nanoTime() - started) / 1_000_000);
    }

    record ScanResult(long durationMillis) {
    }
}
