package com.codyedwin.runelitemobile.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.codyedwin.runelitemobile.Tools;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/** Reads RuneLite's own configuration profiles (~/.runelite/profiles2/profiles.json). */
final class RuneLiteProfiles {
    static final class Profile {
        final String name;
        final boolean active;

        Profile(String name, boolean active) {
            this.name = name;
            this.active = active;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private RuneLiteProfiles() {
    }

    static List<Profile> load() {
        List<Profile> list = new ArrayList<>();
        File file = new File(Tools.getRuneLiteDir(), ".runelite/profiles2/profiles.json");
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
                list.add(new Profile(name, active));
            }
        } catch (Exception e) {
            android.util.Log.w("RuneLiteProfiles", "Unable to read " + file, e);
        }
        return list;
    }
}
