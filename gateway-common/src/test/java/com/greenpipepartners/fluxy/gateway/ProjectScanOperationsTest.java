/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package com.greenpipepartners.fluxy.gateway;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;

class ProjectScanOperationsTest {
    @Test
    void requestsProjectScanAndWaitsForCompletion() throws Exception {
        GatewayContext context = contextWithScan(CompletableFuture.completedFuture(null));

        ProjectScanOperations.ScanResult result = ProjectScanOperations.requestScan(context, 10);

        assertTrue(result.durationMillis() >= 0);
    }

    @Test
    void rejectsInvalidTimeoutBeforeRequestingScan() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectScanOperations.requestScan(null, 0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectScanOperations.requestScan(null, 301)
        );
    }

    @Test
    void reportsExceptionalScanCompletion() {
        CompletableFuture<Void> scan = new CompletableFuture<>();
        scan.completeExceptionally(new IllegalStateException("scan failed"));

        assertThrows(
            ExecutionException.class,
            () -> ProjectScanOperations.requestScan(contextWithScan(scan), 10)
        );
    }

    private static GatewayContext contextWithScan(CompletableFuture<Void> scan) {
        ProjectManager manager = (ProjectManager) Proxy.newProxyInstance(
            ProjectManager.class.getClassLoader(),
            new Class<?>[] {ProjectManager.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("requestScan")) {
                    return scan;
                }
                return defaultValue(method.getReturnType());
            }
        );
        return (GatewayContext) Proxy.newProxyInstance(
            GatewayContext.class.getClassLoader(),
            new Class<?>[] {GatewayContext.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("getProjectManager")) {
                    return manager;
                }
                return defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
