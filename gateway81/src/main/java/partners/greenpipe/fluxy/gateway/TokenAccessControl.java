/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package partners.greenpipe.fluxy.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteAccessControl;

final class TokenAccessControl implements RouteAccessControl {
    static final String ACTOR_ATTRIBUTE = "fluxy.actor";

    private static final String WRITE_HASH_PROPERTY = "fluxy.apiTokenSha256";
    private static final String READ_HASH_PROPERTY = "fluxy.readApiTokenSha256";

    private final boolean writeRequired;

    TokenAccessControl(boolean writeRequired) {
        this.writeRequired = writeRequired;
    }

    @Override
    public boolean canAccess(RequestContext context) {
        String token = requestToken(context.getRequest());
        if (token == null) {
            return false;
        }
        byte[] actual = sha256(token);
        String writeHash = normalizedHash(System.getProperty(WRITE_HASH_PROPERTY));
        if (matches(actual, writeHash)) {
            context.getRequest().setAttribute(ACTOR_ATTRIBUTE, "fluxy-write-" + writeHash.substring(0, 12));
            return true;
        }
        String readHash = normalizedHash(System.getProperty(READ_HASH_PROPERTY));
        if (!writeRequired && matches(actual, readHash)) {
            context.getRequest().setAttribute(ACTOR_ATTRIBUTE, "fluxy-read-" + readHash.substring(0, 12));
            return true;
        }
        return false;
    }

    @Override
    public void handleAccessDenied(RequestContext context, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(RouteGroupTypes.JSON);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("WWW-Authenticate", "Bearer realm=\"Fluxy\"");
        response.getWriter().write(
            "{\"ok\":false,\"code\":\"UNAUTHORIZED\","
                + "\"error\":\"Invalid or missing Fluxy API token\"}"
        );
    }

    private static String requestToken(HttpServletRequest request) {
        String apiToken = request.getHeader("X-Ignition-API-Token");
        if (apiToken != null && !apiToken.isBlank()) {
            return apiToken;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authorization.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalizedHash(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[0-9a-f]{64}") ? normalized : "";
    }

    private static boolean matches(byte[] actual, String expectedHex) {
        return !expectedHex.isEmpty()
            && MessageDigest.isEqual(actual, hexToBytes(expectedHex));
    }

    private static byte[] hexToBytes(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < value.length(); index += 2) {
            bytes[index / 2] = (byte) Integer.parseInt(value.substring(index, index + 2), 16);
        }
        return bytes;
    }

    private static final class RouteGroupTypes {
        private static final String JSON = "application/json";

        private RouteGroupTypes() {
        }
    }
}
