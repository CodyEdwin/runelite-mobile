package com.codyedwin.runelitemobile.shim;

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

    private static boolean sCharacterPassed;

    /**
     * No remembered character yet (first start of RuneLite Mobile, or the user picked one before
     * this existed): if the saved Jagex accounts contain exactly one character, use it.
     */
    static void autoSelectSingleCharacter() {
        if (sCharacterPassed) return;
        try {
            net.runelite.client.rs.auth.JagexAuthService service = net.runelite.client.RuneLite.getInjector()
                    .getInstance(net.runelite.client.rs.auth.JagexAuthService.class);
            service.loadFromStore().thenRun(() -> {
                String sessionId = null;
                net.runelite.client.rs.auth.Account only = null;
                int count = 0;
                for (net.runelite.client.rs.auth.Session session : service.getSessions()) {
                    net.runelite.client.rs.auth.Account[] accounts = service.getAccounts(session.getSessionId());
                    if (accounts == null) continue;
                    for (net.runelite.client.rs.auth.Account account : accounts) {
                        count++;
                        only = account;
                        sessionId = session.getSessionId();
                    }
                }
                if (count == 1 && only.getDisplayName() != null) {
                    System.out.println("JagexSessionMemory: one saved Jagex character, selecting it");
                    service.switchJagexAccount(sessionId, Long.toString(only.getAccountId()), only.getDisplayName());
                } else {
                    System.out.println("JagexSessionMemory: " + count + " saved Jagex characters, pick one in the Jagex Accounts panel");
                }
            });
        } catch (Throwable t) {
            System.err.println("JagexSessionMemory: " + t);
        }
    }

    static String[] addSavedCharacter(String[] args) {
        if (Arrays.asList(args).contains("--character")) {
            sCharacterPassed = true;
            return args;
        }
        try {
            File f = file();
            if (!f.isFile()) return args;
            String name = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            if (name.isEmpty()) return args;
            List<String> list = new ArrayList<>(Arrays.asList(args));
            list.add("--character");
            list.add(name);
            sCharacterPassed = true;
            System.out.println("JagexSessionMemory: reusing saved Jagex session for the last character");
            return list.toArray(new String[0]);
        } catch (Exception e) {
            System.err.println("JagexSessionMemory: " + e);
            return args;
        }
    }
}
