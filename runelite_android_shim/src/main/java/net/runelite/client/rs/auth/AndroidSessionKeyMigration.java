package net.runelite.client.rs.auth;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * RuneLite encrypts ~/.runelite/jagex-accounts.dat on Linux with SHA-256(user.name [+ /etc/machine-id]).
 * On Android user.name is the app's Linux user (u0_aNNN), which changes when the app is reinstalled
 * or the data moves to another package (XDAetherium -> RuneLite Mobile), and the saved Jagex login
 * then fails with "incorrect key or corrupt encrypted data".
 *
 * RuneLite Mobile pins user.name to a stable value. Before RuneLite starts, a store encrypted with
 * another key (the previous u0_aNNN user) is found by trying the possible Android app users and
 * re-encrypted with the stable key. Nothing is sent anywhere; the file never leaves the device.
 */
public final class AndroidSessionKeyMigration {
    public static final String STABLE_USER = "runelite-mobile";

    private AndroidSessionKeyMigration() {
    }

    private static byte[] key(String user) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(user.getBytes(StandardCharsets.UTF_8));
        try {
            digest.update(Files.readAllBytes(Path.of("/etc/machine-id")));
        } catch (Exception ignored) {
        }
        return digest.digest();
    }

    /** Must run before RuneLite reads the store; sets user.name to {@link #STABLE_USER}. */
    public static void migrate(File runeLiteHome) {
        String originalUser = System.getProperty("user.name", "");
        System.setProperty("user.name", STABLE_USER);
        File store = new File(runeLiteHome, ".runelite/jagex-accounts.dat");
        if (!store.isFile()) return;
        try {
            byte[] data = Files.readAllBytes(store.toPath());
            try {
                Crypt.decryptData(data, key(STABLE_USER));
                return; // already migrated
            } catch (Exception ignored) {
            }
            byte[] plain = tryDecrypt(data, originalUser);
            for (int i = 0; plain == null && i < 20000; i++) plain = tryDecrypt(data, "u0_a" + i);
            if (plain == null) {
                System.err.println("AndroidSessionKeyMigration: saved Jagex accounts could not be decrypted");
                return;
            }
            File backup = new File(store.getPath() + ".bak");
            if (!backup.exists()) Files.copy(store.toPath(), backup.toPath());
            Files.write(store.toPath(), Crypt.encryptData(plain, key(STABLE_USER)));
            java.util.Arrays.fill(plain, (byte) 0);
            System.out.println("AndroidSessionKeyMigration: saved Jagex accounts re-keyed for RuneLite Mobile");
        } catch (Exception e) {
            System.err.println("AndroidSessionKeyMigration: " + e);
        }
    }

    private static byte[] tryDecrypt(byte[] data, String user) {
        if (user == null || user.isEmpty()) return null;
        try {
            return Crypt.decryptData(data, key(user));
        } catch (Exception e) {
            return null;
        }
    }
}
