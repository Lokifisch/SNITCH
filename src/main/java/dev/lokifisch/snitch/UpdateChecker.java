package dev.lokifisch.snitch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks the GitHub "latest release" endpoint for this plugin's repo and,
 * if enabled, stages the new jar in Bukkit's update folder so the server
 * swaps it in automatically the next time it restarts.
 */
public final class UpdateChecker {

    private static final String API_URL = "https://api.github.com/repos/%s/releases/latest";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final JavaPlugin plugin;
    private final String repo;
    private final File pluginJarFile;
    private final String currentVersion;

    private volatile String latestVersion;
    private volatile String releaseUrl;
    private volatile String downloadUrl;
    private final AtomicBoolean updateAvailable = new AtomicBoolean(false);
    private final AtomicBoolean staged = new AtomicBoolean(false);

    /**
     * @param repo          "Owner/Repo", e.g. "Lokifisch/SNITCH"
     * @param pluginJarFile this plugin's own jar file (JavaPlugin#getFile() is protected,
     *                      so the plugin must pass it in rather than us calling it directly)
     */
    public UpdateChecker(JavaPlugin plugin, String repo, File pluginJarFile) {
        this.plugin = plugin;
        this.repo = repo;
        this.pluginJarFile = pluginJarFile;
        this.currentVersion = plugin.getDescription().getVersion();
    }

    public boolean isUpdateAvailable() {
        return updateAvailable.get();
    }

    public boolean isStaged() {
        return staged.get();
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getReleaseUrl() {
        return releaseUrl;
    }

    /** Runs the GitHub check off the main thread; {@code onResult} (if given) runs back on the main thread. */
    public void checkAsync(boolean autoUpdate, Runnable onResult) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                check(autoUpdate);
            } catch (Exception e) {
                plugin.getLogger().warning("Update check failed: " + e.getMessage());
            } finally {
                if (onResult != null) {
                    plugin.getServer().getScheduler().runTask(plugin, onResult);
                }
            }
        });
    }

    private void check(boolean autoUpdate) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(API_URL, repo)))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", plugin.getName() + "-UpdateChecker")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            plugin.getLogger().info("No releases published yet for " + repo + ".");
            return;
        }
        if (response.statusCode() != 200) {
            plugin.getLogger().warning("Update check got HTTP " + response.statusCode() + " from GitHub.");
            return;
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("tag_name")) {
            return;
        }
        String tag = json.get("tag_name").getAsString();
        this.latestVersion = (tag.startsWith("v") || tag.startsWith("V")) ? tag.substring(1) : tag;
        this.releaseUrl = json.has("html_url")
                ? json.get("html_url").getAsString()
                : "https://github.com/" + repo + "/releases";

        if (!isNewer(latestVersion, currentVersion)) {
            plugin.getLogger().info(plugin.getName() + " is up to date (v" + currentVersion + ").");
            return;
        }

        updateAvailable.set(true);
        plugin.getLogger().warning(String.format(
                "A new version of %s is available: v%s (running v%s) - %s",
                plugin.getName(), latestVersion, currentVersion, releaseUrl));

        this.downloadUrl = findJarAsset(json);
        if (autoUpdate) {
            if (downloadUrl == null) {
                plugin.getLogger().warning("Auto-update is enabled but the release has no .jar asset attached.");
            } else {
                stage(downloadUrl);
            }
        }
    }

    private String findJarAsset(JsonObject release) {
        if (!release.has("assets")) {
            return null;
        }
        JsonArray assets = release.getAsJsonArray("assets");
        for (JsonElement el : assets) {
            JsonObject asset = el.getAsJsonObject();
            String name = asset.has("name") ? asset.get("name").getAsString() : "";
            if (name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return asset.get("browser_download_url").getAsString();
            }
        }
        return null;
    }

    /** Downloads the given jar URL into Bukkit's update folder under this plugin's own jar filename. */
    public void stage(String url) {
        try {
            Path updateFolder = plugin.getServer().getUpdateFolderFile().toPath();
            Files.createDirectories(updateFolder);

            Path target = updateFolder.resolve(pluginJarFile.getName());
            Path tmp = updateFolder.resolve(pluginJarFile.getName() + ".part");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", plugin.getName() + "-UpdateChecker")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                plugin.getLogger().warning("Failed to download update: HTTP " + response.statusCode());
                return;
            }
            try (InputStream in = response.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);

            staged.set(true);
            plugin.getLogger().warning(String.format(
                    "%s v%s downloaded and staged - it will be applied automatically the next time the server restarts.",
                    plugin.getName(), latestVersion));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to stage update: " + e.getMessage());
        }
    }

    /** Numeric dotted-version compare (e.g. 1.10.0 > 1.9.0); non-numeric suffixes like "-SNAPSHOT" are ignored. */
    static boolean isNewer(String latest, String current) {
        int[] l = parts(latest);
        int[] c = parts(current);
        int len = Math.max(l.length, c.length);
        for (int i = 0; i < len; i++) {
            int a = i < l.length ? l[i] : 0;
            int b = i < c.length ? c[i] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    private static int[] parts(String v) {
        String cleaned = v == null ? "" : v.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
            cleaned = cleaned.substring(1);
        }
        cleaned = cleaned.split("[-+]")[0];
        String[] pieces = cleaned.split("\\.");
        int[] out = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            try {
                out[i] = Integer.parseInt(pieces[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
