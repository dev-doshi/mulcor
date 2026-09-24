package dev.mulcor.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minestom.server.network.player.GameProfile;

/**
 * Online-mode player authentication (cold path). The server asks the session server whether {@code username}
 * joined with {@code serverHash}; the answer is the player's authoritative profile (UUID, name, skin properties).
 * The call is asynchronous, so a slow session server never holds up a Netty event loop.
 */
@FunctionalInterface
public interface Authenticator {
    /** Completes with the profile, or with {@code null} if the session server does not vouch for the player. */
    CompletableFuture<GameProfile> hasJoined(String username, String serverHash);

    URI MOJANG = URI.create("https://sessionserver.mojang.com/session/minecraft/hasJoined");

    /** Mojang's session server. */
    static Authenticator mojang() {
        return http(MOJANG);
    }

    /** A vanilla-compatible session server at {@code endpoint} (the full {@code .../hasJoined} URL). */
    static Authenticator http(URI endpoint) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (username, serverHash) -> {
            URI uri = URI.create(endpoint + "?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&serverId=" + URLEncoder.encode(serverHash, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(r -> r.statusCode() == 200 && !r.body().isBlank() ? parseProfile(r.body()) : null);
        };
    }

    /** Parse a session-server profile: {@code {"id": "<32 hex>", "name": ..., "properties": [...]}}. */
    static GameProfile parseProfile(String json) {
        // Minestom's profile and codec classes initialize each other in a cycle that only resolves when its
        // registries load first (as the server always does before a login); make that order explicit.
        java.util.Objects.requireNonNull(Vanilla.REGISTRIES);
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String id = o.get("id").getAsString();
        UUID uuid = id.length() == 32
                ? new UUID(Long.parseUnsignedLong(id.substring(0, 16), 16), Long.parseUnsignedLong(id.substring(16), 16))
                : UUID.fromString(id);
        List<GameProfile.Property> props = new ArrayList<>();
        JsonElement arr = o.get("properties");
        if (arr != null && arr.isJsonArray()) {
            for (JsonElement e : arr.getAsJsonArray()) {
                JsonObject p = e.getAsJsonObject();
                JsonElement sig = p.get("signature");
                props.add(new GameProfile.Property(p.get("name").getAsString(), p.get("value").getAsString(),
                        sig == null || sig.isJsonNull() ? null : sig.getAsString()));
            }
        }
        return new GameProfile(uuid, o.get("name").getAsString(), props);
    }
}
