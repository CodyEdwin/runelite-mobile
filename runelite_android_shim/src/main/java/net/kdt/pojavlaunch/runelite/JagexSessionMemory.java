package net.kdt.pojavlaunch.runelite;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Keeps the Jagex login across app restarts.
 *
 * RuneLite (1.13) already stores Jagex account sessions in ~/.runelite/jagex-accounts.dat, but on
 * startup it only picks a character when it is given "--character <name>" (desktop users pick one in
 * the Jagex Accounts panel every time, or the Jagex Launcher passes JX_* environment variables,
 * which we do not have). The Android JagexAuthService override records the character the user picked
 * last; this passes it back as --character so the saved session is used automatically.
 */
public final class JagexSessionMemory {
    private JagexSessionMemory() {
    }

    static File file() {
        return new File(System.getProperty("user.home"), ".runelite/android-last-character.txt");
    }

    /** Called from JagexAuthService.switchJagexAccount (null name = legacy login, forget it). */
    public static void remember(String displayName) {
        try {
            File f = file();
            if (displayName == null || displayName.isEmpty()) {
                Files.deleteIfExists(f.toPath());
            } else {
                f.getParentFile().mkdirs();
                Files.write(f.toPath(), displayName.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            System.err.println("JagexSessionMemory: " + e);
        }
    }

    static String[] addSavedCharacter(String[] args) {
        if (Arrays.asList(args).contains("--character")) return args;
        try {
            File f = file();
            if (!f.isFile()) return args;
            String name = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            if (name.isEmpty()) return args;
            List<String> list = new ArrayList<>(Arrays.asList(args));
            list.add("--character");
            list.add(name);
            System.out.println("JagexSessionMemory: reusing saved Jagex session for the last character");
            return list.toArray(new String[0]);
        } catch (Exception e) {
            System.err.println("JagexSessionMemory: " + e);
            return args;
        }
    }
}
