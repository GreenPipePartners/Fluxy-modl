/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package partners.greenpipe.fluxy.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;

final class FluxyRouteManifest {
    static final String IGNITION_81 = "8.1";
    static final String IGNITION_83 = "8.3";

    private static final String RESOURCE = "/fluxy-routes.json";
    private static final Pattern OPERATION_PATTERN = Pattern.compile(
        "[a-z][A-Za-z0-9]*/[A-Za-z][A-Za-z0-9]*"
    );
    private static final Set<String> SUPPORTED_VERSIONS = Set.of(IGNITION_81, IGNITION_83);
    private static final FluxyRouteManifest INSTANCE = load();

    private final int contractVersion;
    private final List<Route> routes;

    private FluxyRouteManifest(int contractVersion, List<Route> routes) {
        this.contractVersion = contractVersion;
        this.routes = List.copyOf(routes);
    }

    static FluxyRouteManifest instance() {
        return INSTANCE;
    }

    int contractVersion() {
        return contractVersion;
    }

    List<Route> routes() {
        return routes;
    }

    List<Route> routesFor(String ignitionVersion) {
        requireVersion(ignitionVersion);
        return routes.stream().filter(route -> route.availableOn(ignitionVersion)).toList();
    }

    Set<String> dispatchOperations(String ignitionVersion) {
        LinkedHashSet<String> operations = new LinkedHashSet<>();
        for (Route route : routesFor(ignitionVersion)) {
            if (route.handler() == Handler.DISPATCH) {
                operations.add(route.operation());
            }
        }
        return Set.copyOf(operations);
    }

    boolean isWrite(String operation) {
        return routes.stream().anyMatch(route ->
            route.operation().equals(operation) && route.access() == Access.WRITE
        );
    }

    String capabilitiesJson(String ignitionVersion, String moduleVersion) {
        requireVersion(ignitionVersion);
        JsonArray operations = new JsonArray();
        for (Route route : routes) {
            JsonObject item = new JsonObject();
            item.addProperty("operation", route.operation());
            item.addProperty("access", route.access().wireValue());
            item.addProperty("available", route.availableOn(ignitionVersion));
            operations.add(item);
        }

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("transport", "ignition-module");
        response.addProperty("contractVersion", contractVersion);
        response.addProperty("moduleVersion", moduleVersion);
        response.addProperty("ignitionVersion", ignitionVersion);
        response.add("operations", operations);
        return response.toString();
    }

    private static FluxyRouteManifest load() {
        String source;
        try (InputStream stream = FluxyRouteManifest.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + RESOURCE, exception);
        }

        try {
            JsonObject root = new JsonParser().parse(source).getAsJsonObject();
            int schemaVersion = requiredInt(root, "schemaVersion");
            if (schemaVersion != 1) {
                throw new IllegalStateException("Unsupported Fluxy route schema version " + schemaVersion);
            }
            int contractVersion = requiredInt(root, "contractVersion");
            if (contractVersion < 1) {
                throw new IllegalStateException("contractVersion must be positive");
            }
            if (!root.has("routes") || !root.get("routes").isJsonArray()) {
                throw new IllegalStateException("Fluxy route manifest must include routes array");
            }

            List<Route> routes = new ArrayList<>();
            Set<String> operations = new LinkedHashSet<>();
            for (JsonElement element : root.getAsJsonArray("routes")) {
                if (!element.isJsonObject()) {
                    throw new IllegalStateException("Fluxy route entries must be objects");
                }
                Route route = parseRoute(element.getAsJsonObject());
                if (!operations.add(route.operation())) {
                    throw new IllegalStateException("Duplicate Fluxy operation " + route.operation());
                }
                routes.add(route);
            }
            if (routes.isEmpty()) {
                throw new IllegalStateException("Fluxy route manifest must not be empty");
            }
            return new FluxyRouteManifest(contractVersion, routes);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid Fluxy route manifest", exception);
        }
    }

    private static Route parseRoute(JsonObject object) {
        String operation = requiredString(object, "operation");
        if (!OPERATION_PATTERN.matcher(operation).matches()) {
            throw new IllegalStateException("Invalid Fluxy operation " + operation);
        }
        Access access = Access.fromWireValue(requiredString(object, "access"));
        Handler handler = Handler.fromWireValue(requiredString(object, "handler"));
        if (!object.has("versions") || !object.get("versions").isJsonArray()) {
            throw new IllegalStateException(operation + " must include versions array");
        }
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        for (JsonElement element : object.getAsJsonArray("versions")) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalStateException(operation + " versions must be strings");
            }
            String version = element.getAsString();
            requireVersion(version);
            versions.add(version);
        }
        if (versions.isEmpty()) {
            throw new IllegalStateException(operation + " must support at least one version");
        }
        return new Route(operation, access, handler, Set.copyOf(versions));
    }

    private static int requiredInt(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IllegalStateException("Fluxy route manifest must include " + key);
        }
        return object.get(key).getAsInt();
    }

    private static String requiredString(JsonObject object, String key) {
        if (!object.has(key)
            || !object.get(key).isJsonPrimitive()
            || !object.getAsJsonPrimitive(key).isString()) {
            throw new IllegalStateException("Fluxy route entry must include " + key + " string");
        }
        return object.get(key).getAsString();
    }

    private static void requireVersion(String ignitionVersion) {
        if (!SUPPORTED_VERSIONS.contains(ignitionVersion)) {
            throw new IllegalArgumentException("Unsupported Ignition family " + ignitionVersion);
        }
    }

    enum Access {
        READ,
        WRITE;

        String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Access fromWireValue(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Unsupported Fluxy access " + value, exception);
            }
        }
    }

    enum Handler {
        DISPATCH("dispatch"),
        NATIVE_HISTORY_STREAM("native-history-stream"),
        PROJECT_SCAN("project-scan"),
        CAPABILITIES("capabilities");

        private final String wireValue;

        Handler(String wireValue) {
            this.wireValue = wireValue;
        }

        static Handler fromWireValue(String value) {
            for (Handler handler : values()) {
                if (handler.wireValue.equals(value)) {
                    return handler;
                }
            }
            throw new IllegalStateException("Unsupported Fluxy handler " + value);
        }
    }

    record Route(String operation, Access access, Handler handler, Set<String> versions) {
        boolean availableOn(String ignitionVersion) {
            return versions.contains(ignitionVersion);
        }
    }
}
