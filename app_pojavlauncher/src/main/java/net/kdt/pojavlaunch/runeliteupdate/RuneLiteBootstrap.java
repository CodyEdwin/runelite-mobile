package net.kdt.pojavlaunch.runeliteupdate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the RuneLite client up to date the same way the official launcher does, without Android
 * dependencies (so it can be exercised on a desktop JVM too).
 *
 * <p>The official launcher downloads https://static.runelite.net/bootstrap.json, checks its
 * SHA256withRSA signature (bootstrap.json.sha256) against the RuneLite certificate shipped in the
 * launcher, then downloads every listed artifact (the client, the injected game client, the API and
 * the libraries) into ~/.runelite/repository2, verifying each SHA-256, and starts
 * net.runelite.client.RuneLite with those jars as the classpath. This class does the same into
 * {@code <runelite dir>/client-updates}:</p>
 * <pre>
 *   client-updates/state.json            mode, current / previous verified version, last error
 *   client-updates/versions/X.json       verified classpath for RuneLite X (+ shim check result)
 *   client-updates/repo/*.jar            downloaded artifacts (shared between versions)
 *   client-updates/ok/X                  written by the Android shim once RuneLite X showed its window
 * </pre>
 */
public final class RuneLiteBootstrap {
    public static final String BOOTSTRAP_URL = "https://static.runelite.net/bootstrap.json";
    public static final String SIGNATURE_URL = "https://static.runelite.net/bootstrap.json.sha256";

    /** Newest of the official release and the client bundled in the APK; updates on launch. */
    public static final String MODE_AUTO = "auto";
    /** Always the latest official release; updates on launch. */
    public static final String MODE_OFFICIAL = "official";
    /** The client bundled in the APK, never touches the network. */
    public static final String MODE_BUNDLED = "bundled";
    /** Stay on the previously installed official release (rollback), no update checks. */
    public static final String MODE_PREVIOUS = "previous";

    /** Launch attempts without the shim confirming a working window before falling back. */
    static final int MAX_UNCONFIRMED_ATTEMPTS = 2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public interface Progress {
        void onProgress(String message, int percent);
    }

    public static final class State {
        public String mode = MODE_AUTO;
        public String current;
        public String previous;
        public long lastCheck;
        public String lastError;
        public String latestSeen;
        public Map<String, Integer> attempts = new LinkedHashMap<>();
    }

    public static final class Artifact {
        public String name;
        public String hash;
        public long size;
    }

    public static final class Manifest {
        public String version;
        public List<Artifact> classpath = new ArrayList<>();
        public List<String> jvmArgs = new ArrayList<>();
        public List<String> skipped = new ArrayList<>();
        public boolean compatible = true;
        public String compatReport;
        public long installedAt;
    }

    public static final class UpdateResult {
        public String latestVersion;
        public boolean downloaded;
        public boolean installed;
        public long bytesDownloaded;
        public String message;
        public ShimCompat.Report compat;
    }

    public static final class LaunchPlan {
        /** null = use the bundled runelite.jar */
        public Manifest manifest;
        public List<File> classpath = new ArrayList<>();
        public String version;
        public String note;

        public boolean isBundled() {
            return manifest == null;
        }
    }

    private final File baseDir;
    private final File repoDir;
    private final File versionsDir;
    private final File okDir;
    private final PublicKey signingKey;
    private final File shimJar;
    private String userAgent = "RuneLite-Android-Launcher";

    public RuneLiteBootstrap(File runeLiteDir, InputStream certificate, File shimJar) throws IOException {
        this.baseDir = new File(runeLiteDir, "client-updates");
        this.repoDir = new File(baseDir, "repo");
        this.versionsDir = new File(baseDir, "versions");
        this.okDir = new File(baseDir, "ok");
        this.shimJar = shimJar;
        try {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(certificate);
            // Like the official launcher, only the key matters (the certificate's dates are not checked).
            this.signingKey = cert.getPublicKey();
        } catch (Exception e) {
            throw new IOException("Unable to read the RuneLite signing certificate", e);
        }
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public File getOkDir() {
        return okDir;
    }

    /* ------------------------------------------------------------------ state */

    public synchronized State loadState() {
        File f = new File(baseDir, "state.json");
        if (f.isFile()) {
            try {
                State s = GSON.fromJson(new String(readFile(f), StandardCharsets.UTF_8), State.class);
                if (s != null) {
                    if (s.attempts == null) s.attempts = new LinkedHashMap<>();
                    if (s.mode == null) s.mode = MODE_AUTO;
                    return s;
                }
            } catch (Exception ignored) {
            }
        }
        return new State();
    }

    public synchronized void saveState(State state) throws IOException {
        writeAtomically(new File(baseDir, "state.json"), GSON.toJson(state).getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void setMode(String mode) throws IOException {
        State s = loadState();
        s.mode = mode;
        saveState(s);
    }

    public Manifest loadManifest(String version) {
        if (version == null) return null;
        File f = new File(versionsDir, safeName(version) + ".json");
        if (!f.isFile()) return null;
        try {
            return GSON.fromJson(new String(readFile(f), StandardCharsets.UTF_8), Manifest.class);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isConfirmed(String version) {
        return version != null && new File(okDir, safeName(version)).isFile();
    }

    /* ----------------------------------------------------------------- update */

    /**
     * Fetch bootstrap.json, verify it, download what changed and, when the Android shim links
     * against it, make it the current version (the old one becomes the fallback).
     */
    public UpdateResult checkForUpdate(Progress progress) throws IOException {
        UpdateResult result = new UpdateResult();
        State state = loadState();
        try {
            report(progress, "Checking for RuneLite updates", 0);
            byte[] json = download(BOOTSTRAP_URL, 2 * 1024 * 1024);
            byte[] signature = download(SIGNATURE_URL, 64 * 1024);
            if (!verifySignature(json, signature))
                throw new IOException("bootstrap.json signature check failed (RuneLite may have changed its signing key; the app needs an update)");

            JsonObject bootstrap = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            String version = bootstrap.get("version").getAsString();
            result.latestVersion = version;
            state.latestSeen = version;

            Manifest existing = loadManifest(version);
            if (existing != null && existing.compatible && verifyFiles(existing, false) == null) {
                if (!version.equals(state.current)) promote(state, version);
                result.installed = true;
                result.message = "RuneLite " + version + " is up to date";
                return finish(state, result, null);
            }

            Manifest manifest = new Manifest();
            manifest.version = version;
            List<JsonObject> wanted = new ArrayList<>();
            long total = 0;
            for (JsonElement element : bootstrap.getAsJsonArray("artifacts")) {
                JsonObject a = element.getAsJsonObject();
                String name = a.get("name").getAsString();
                if (!isUsableOnAndroid(a)) {
                    manifest.skipped.add(name);
                    continue;
                }
                wanted.add(a);
                total += a.has("size") ? a.get("size").getAsLong() : 0;
            }
            if (wanted.isEmpty()) throw new IOException("bootstrap.json lists no artifacts");

            long done = 0;
            mkdirs(repoDir);
            for (JsonObject a : wanted) {
                Artifact art = new Artifact();
                art.name = a.get("name").getAsString();
                art.hash = a.get("hash").getAsString().toLowerCase(Locale.ROOT);
                art.size = a.has("size") ? a.get("size").getAsLong() : -1;
                String path = a.get("path").getAsString();
                checkName(art.name);
                if (!path.startsWith("https://")) throw new IOException("refusing non-https artifact URL " + path);

                File target = new File(repoDir, art.name);
                if (!(target.isFile() && (art.size < 0 || target.length() == art.size) && art.hash.equals(sha256(target)))) {
                    final long base = done;
                    final long totalSize = total;
                    report(progress, "Downloading " + art.name, percent(done, total));
                    downloadTo(path, target, art, (bytes) -> report(progress, "Downloading " + art.name, percent(base + bytes, totalSize)));
                    result.downloaded = true;
                    result.bytesDownloaded += target.length();
                }
                done += Math.max(art.size, 0);
                manifest.classpath.add(art);
            }

            JsonArray jvm = bootstrap.has("clientJvm17Arguments") ? bootstrap.getAsJsonArray("clientJvm17Arguments") : null;
            if (jvm != null) {
                for (JsonElement e : jvm) {
                    String arg = e.getAsString();
                    // Only module-access flags matter here; memory/stack/JIT flags are the launcher's job
                    // and -Drunelite.launcher.* would switch RuneLite out of developer mode.
                    if (arg.startsWith("--add-opens=") || arg.startsWith("--add-exports=")) manifest.jvmArgs.add(arg);
                }
            }

            report(progress, "Checking RuneLite " + version + " against the Android shim", 99);
            if (shimJar != null && shimJar.isFile()) {
                ShimCompat.Report compat = ShimCompat.check(shimJar, filesOf(manifest));
                result.compat = compat;
                manifest.compatible = compat.isCompatible();
                manifest.compatReport = compat.toString();
            }
            manifest.installedAt = System.currentTimeMillis();
            mkdirs(versionsDir);
            writeAtomically(new File(versionsDir, safeName(version) + ".json"), GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));

            if (!manifest.compatible) {
                result.message = "RuneLite " + version + " was downloaded but the Android shim does not support it yet; staying on "
                        + (state.current != null ? state.current : "the bundled client");
                return finish(state, result, result.message);
            }
            if (!version.equals(state.current)) promote(state, version);
            result.installed = true;
            result.message = result.downloaded ? "Installed RuneLite " + version : "RuneLite " + version + " is up to date";
            return finish(state, result, null);
        } catch (IOException | RuntimeException e) {
            state.lastCheck = System.currentTimeMillis();
            state.lastError = String.valueOf(e.getMessage());
            saveState(state);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Invalid bootstrap.json: " + e, e);
        }
    }

    private UpdateResult finish(State state, UpdateResult result, String error) throws IOException {
        state.lastCheck = System.currentTimeMillis();
        state.lastError = error;
        saveState(state);
        collectGarbage(state);
        return result;
    }

    private void promote(State state, String version) {
        if (state.current != null && !state.current.equals(version)) state.previous = state.current;
        state.current = version;
        if (MODE_PREVIOUS.equals(state.mode)) state.mode = MODE_AUTO;
    }

    /** Swap back to the previous official version and stay there (no update checks). */
    public synchronized String rollback() throws IOException {
        State s = loadState();
        if (s.previous == null || loadManifest(s.previous) == null) throw new IOException("No previous RuneLite version is installed");
        s.mode = MODE_PREVIOUS;
        saveState(s);
        return s.previous;
    }

    /**
     * Platform-specific jars (rlicn for Windows, lwjgl natives for desktop OSes) are skipped: their
     * native libraries are built for glibc/Windows/macOS and cannot load on Android. Platform-
     * neutral Java jars (lwjgl, jna, rlawt, discord) are kept so classes that reference them still
     * link; the GPU plugin, Discord and rlawt fail gracefully at runtime like with the bundled jar.
     */
    static boolean isUsableOnAndroid(JsonObject artifact) {
        return !artifact.has("platform") || artifact.getAsJsonArray("platform").size() == 0;
    }

    /* ----------------------------------------------------------------- launch */

    /**
     * Decide what to launch. Records a launch attempt for a downloaded version; after
     * {@link #MAX_UNCONFIRMED_ATTEMPTS} attempts without the shim confirming a window, the
     * previous confirmed version (or the bundled client) is used instead.
     */
    public synchronized LaunchPlan planLaunch(String bundledVersion) throws IOException {
        State state = loadState();
        LaunchPlan plan = new LaunchPlan();
        plan.version = bundledVersion;
        if (MODE_BUNDLED.equals(state.mode)) return plan;

        List<String> candidates = new ArrayList<>();
        if (MODE_PREVIOUS.equals(state.mode)) {
            candidates.add(state.previous);
        } else {
            candidates.add(state.current);
            candidates.add(state.previous);
        }

        StringBuilder note = new StringBuilder();
        for (String version : candidates) {
            if (version == null) continue;
            Manifest manifest = loadManifest(version);
            if (manifest == null || !manifest.compatible) continue;
            if (MODE_AUTO.equals(state.mode) && bundledVersion != null && compareVersions(version, bundledVersion) < 0) {
                // The bundled client is newer than this release (e.g. a snapshot built after it).
                if (note.length() == 0) plan.note = "Using the bundled RuneLite " + bundledVersion + " (newer than release " + version + ")";
                return plan;
            }
            String broken = verifyFiles(manifest, true);
            if (broken != null) {
                note.append("RuneLite ").append(version).append(" is damaged (").append(broken).append("). ");
                continue;
            }
            int attempts = state.attempts.containsKey(version) ? state.attempts.get(version) : 0;
            if (!isConfirmed(version) && attempts >= MAX_UNCONFIRMED_ATTEMPTS) {
                note.append("RuneLite ").append(version).append(" failed to start ").append(attempts).append(" times. ");
                continue;
            }
            if (!isConfirmed(version)) {
                state.attempts.put(version, attempts + 1);
                saveState(state);
            }
            plan.manifest = manifest;
            plan.version = version;
            plan.classpath = filesOf(manifest);
            if (note.length() > 0) plan.note = note + "Using RuneLite " + version + ".";
            return plan;
        }
        if (note.length() > 0) plan.note = note + "Using the bundled RuneLite " + bundledVersion + ".";
        return plan;
    }

    /** Clear the failure counter so a version that was given up on is tried again. */
    public synchronized void resetAttempts() throws IOException {
        State s = loadState();
        s.attempts.clear();
        saveState(s);
    }

    /* ---------------------------------------------------------------- helpers */

    List<File> filesOf(Manifest manifest) {
        List<File> files = new ArrayList<>();
        for (Artifact a : manifest.classpath) files.add(new File(repoDir, a.name));
        return files;
    }

    /** @return null when every file is present (and, with {@code hashes}, matches its SHA-256). */
    String verifyFiles(Manifest manifest, boolean hashes) throws IOException {
        for (Artifact a : manifest.classpath) {
            File f = new File(repoDir, a.name);
            if (!f.isFile()) return a.name + " missing";
            if (a.size >= 0 && f.length() != a.size) return a.name + " has the wrong size";
            if (hashes && !a.hash.equals(sha256(f))) return a.name + " fails its hash check";
        }
        return null;
    }

    private void collectGarbage(State state) {
        Set<String> keepVersions = new HashSet<>();
        if (state.current != null) keepVersions.add(safeName(state.current) + ".json");
        if (state.previous != null) keepVersions.add(safeName(state.previous) + ".json");
        if (state.latestSeen != null) keepVersions.add(safeName(state.latestSeen) + ".json");
        Set<String> keepFiles = new HashSet<>();
        File[] manifests = versionsDir.listFiles();
        if (manifests != null) {
            for (File f : manifests) {
                if (!keepVersions.contains(f.getName())) {
                    f.delete();
                    continue;
                }
                Manifest m = loadManifest(f.getName().substring(0, f.getName().length() - 5));
                if (m != null) for (Artifact a : m.classpath) keepFiles.add(a.name);
            }
        }
        File[] files = repoDir.listFiles();
        if (files != null) for (File f : files) if (!keepFiles.contains(f.getName())) f.delete();
    }

    boolean verifySignature(byte[] data, byte[] signature) {
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(signingKey);
            s.update(data);
            return s.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        return conn;
    }

    private byte[] download(String url, int limit) throws IOException {
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limit) throw new IOException(url + " is too large");
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private interface ByteProgress {
        void onBytes(long bytes);
    }

    private void downloadTo(String url, File target, Artifact art, ByteProgress progress) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            File tmp = new File(target.getPath() + ".part");
            try {
                HttpURLConnection conn = open(url);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long read = 0, lastReport = 0;
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        digest.update(buf, 0, n);
                        read += n;
                        if (art.size > 0 && read > art.size) throw new IOException(art.name + " is larger than bootstrap.json says");
                        if (read - lastReport > 256 * 1024) {
                            lastReport = read;
                            progress.onBytes(read);
                        }
                    }
                } finally {
                    conn.disconnect();
                }
                String hash = hex(digest.digest());
                if (art.size >= 0 && read != art.size) throw new IOException(art.name + ": expected " + art.size + " bytes, got " + read);
                if (!hash.equals(art.hash)) throw new IOException(art.name + ": SHA-256 mismatch (" + hash + ")");
                if (target.exists() && !target.delete()) throw new IOException("Unable to replace " + target);
                if (!tmp.renameTo(target)) throw new IOException("Unable to move " + tmp + " to " + target);
                return;
            } catch (IOException e) {
                last = e;
                tmp.delete();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
        }
        throw last;
    }

    public static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    /**
     * Compare RuneLite versions such as 1.13.6 and 1.13.7-SNAPSHOT (a snapshot sorts before the
     * release with the same number). Anything before the last '-'-separated number group, like the
     * commit prefix in "4e8b0bc5f-1.13.7-SNAPSHOT", is ignored.
     */
    public static int compareVersions(String a, String b) {
        int[] va = numbers(a), vb = numbers(b);
        for (int i = 0; i < Math.max(va.length, vb.length); i++) {
            int x = i < va.length ? va[i] : 0, y = i < vb.length ? vb[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        boolean sa = a.toUpperCase(Locale.ROOT).contains("SNAPSHOT"), sb = b.toUpperCase(Locale.ROOT).contains("SNAPSHOT");
        return sa == sb ? 0 : (sa ? -1 : 1);
    }

    private static int[] numbers(String version) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)+)").matcher(version);
        String best = null;
        while (m.find()) best = m.group(1);
        if (best == null) return new int[0];
        String[] parts = best.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static void checkName(String name) throws IOException {
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.startsWith(".") || !name.endsWith(".jar"))
            throw new IOException("Unexpected artifact name " + name);
    }

    static String safeName(String version) {
        return version.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static int percent(long done, long total) {
        return total <= 0 ? 0 : (int) Math.min(99, done * 100 / total);
    }

    private static void report(Progress progress, String message, int percent) {
        if (progress != null) progress.onProgress(message, percent);
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Unable to create " + dir);
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return ShimCompat.readAll(in);
        }
    }

    private static void writeAtomically(File target, byte[] data) throws IOException {
        mkdirs(target.getParentFile());
        File tmp = new File(target.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (target.exists() && !target.delete()) throw new IOException("Unable to replace " + target);
        if (!tmp.renameTo(target)) throw new IOException("Unable to write " + target);
    }

    /** Desktop test entry: RuneLiteBootstrap <runeliteDir> <runelite.crt> [shim.jar] */
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        RuneLiteBootstrap b;
        try (InputStream cert = new FileInputStream(args[1])) {
            b = new RuneLiteBootstrap(dir, cert, args.length > 2 ? new File(args[2]) : null);
        }
        final int[] last = {-1};
        UpdateResult r = b.checkForUpdate((msg, pct) -> {
            if (pct / 10 != last[0]) {
                last[0] = pct / 10;
                System.out.println(pct + "% " + msg);
            }
        });
        System.out.println(r.message + " (downloaded " + r.bytesDownloaded + " bytes)");
        if (r.compat != null) System.out.println("Shim: " + r.compat);
        LaunchPlan plan = b.planLaunch(args.length > 3 ? args[3] : "1.13.7-SNAPSHOT");
        System.out.println("Launch: " + (plan.isBundled() ? "bundled " : "official ") + plan.version + (plan.note != null ? " - " + plan.note : ""));
        for (File f : plan.classpath) System.out.println("  " + f.getName());
        if (plan.manifest != null) System.out.println("JVM args: " + plan.manifest.jvmArgs + "\nSkipped: " + plan.manifest.skipped);
    }
}
