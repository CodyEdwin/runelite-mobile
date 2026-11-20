package com.codyedwin.runelitemobile.launcher;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.codyedwin.runelitemobile.Tools;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads RuneLite's own configuration profiles (~/.runelite/profiles2/profiles.json). */
final class RuneLiteProfiles {
    static final class Profile {
        final String name;
        final boolean active;
        final long id;

        Profile(String name, boolean active) {
            this(name, active, -1);
        }

        Profile(String name, boolean active, long id) {
            this.name = name;
            this.active = active;
            this.id = id;
        }

        /** RuneLite's settings file for this profile, see ProfileManager.profileConfigFile. */
        File configFile() {
            return new File(profilesDir(), name + "-" + id + ".properties");
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private RuneLiteProfiles() {
    }

    static File profilesDir() {
        return new File(Tools.getRuneLiteDir(), ".runelite/profiles2");
    }

    /** Adds a new profile (like RuneLite's own "Import profile") from an exported .properties file. */
    static String importProfile(String wantedName, InputStream settings) throws IOException {
        File dir = profilesDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Unable to create " + dir);
        File file = new File(dir, "profiles.json");
        JsonObject root = new JsonObject();
        if (file.isFile()) {
            try (Reader reader = new FileReader(file)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
        JsonArray profiles = root.getAsJsonArray("profiles");
        if (profiles == null) {
            profiles = new JsonArray();
            root.add("profiles", profiles);
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        for (JsonElement e : profiles) {
            JsonObject p = e.getAsJsonObject();
            if (p.has("name")) names.add(p.get("name").getAsString());
        }
        String base = wantedName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (base.isEmpty() || base.startsWith("$")) base = "Imported Profile";
        String name = base;
        for (int n = 1; names.contains(name); n++) name = base + " (" + n + ")";
        long id = System.currentTimeMillis() * 1000 + (long) (Math.random() * 1000);
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + "-" + id + ".properties"))) {
            IOUtils.copy(settings, out);
        }
        JsonObject p = new JsonObject();
        p.addProperty("id", id);
        p.addProperty("name", name);
        p.addProperty("sync", false);
        p.addProperty("active", false);
        p.addProperty("rev", -1);
        p.add("defaultForRsProfiles", new JsonArray());
        profiles.add(p);
        File tmp = new File(dir, "profiles.json.tmp");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            new Gson().toJson(root, writer);
        }
        if (!tmp.renameTo(file)) throw new IOException("Unable to write " + file);
        return name;
    }

    static List<Profile> load() {
        List<Profile> list = new ArrayList<>();
        File file = new File(profilesDir(), "profiles.json");
        if (!file.isFile()) return list;
        try (Reader reader = new FileReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray profiles = root.getAsJsonArray("profiles");
            if (profiles == null) return list;
            for (JsonElement element : profiles) {
                JsonObject p = element.getAsJsonObject();
                if (!p.has("name")) continue;
                // Skip RuneLite's internal profiles (e.g. "$rsprofile")
                String name = p.get("name").getAsString();
                if (name.startsWith("$")) continue;
                boolean active = p.has("active") && p.get("active").getAsBoolean();
                list.add(new Profile(name, active, p.has("id") ? p.get("id").getAsLong() : -1));
            }
        } catch (Exception e) {
            android.util.Log.w("RuneLiteProfiles", "Unable to read " + file, e);
        }
        return list;
    }
}
