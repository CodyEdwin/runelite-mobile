/*
 * Android override of RuneLite's JagexAuthService (decompiled from the bundled runelite.jar,
 * 4e8b0bc5f-1.13.7-SNAPSHOT). It is placed before runelite.jar on the classpath by the launcher.
 *
 * Desktop RuneLite runs a bundled native helper (JagexAuth-<os>-<arch>) which opens a GTK/WebKit
 * window for the Jagex OAuth login and prints "id_token: <jwt>". That cannot work on Android:
 * the helper needs glibc, GTK3 and WebKit2GTK and an X11/Wayland display, and Android (targetSdk
 * 29+) refuses to execute files written to the app's data/cache directories anyway. The only
 * visible effect was "Add Jagex Account" doing nothing (the exception is only logged).
 *
 * On Android the login runs in an Android WebView activity instead. This class asks the launcher
 * to open it through java.awt.Desktop#browse with a private runelite-jagexauth:// URI and waits
 * for the activity to send the id_token back over a loopback socket.
 *
 * Keep this file in sync with upstream if the bundled runelite.jar is updated.
 */
package net.runelite.client.rs.auth;

import com.google.gson.Gson;
import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.OSType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class JagexAuthService {
   private static final Logger log = LoggerFactory.getLogger(JagexAuthService.class);
   private final Client client;
   private final ClientThread clientThread;
   private final JagexAuthClient authClient;
   private final SessionStore sessionStore;
   private final Gson gson;
   private final ScheduledExecutorService executor;
   private final List<Session> sessions = new ArrayList<>();
   private final Map<String, Account[]> accounts = new HashMap<>();

   public CompletableFuture<Void> loadFromStore() {
      return CompletableFuture.runAsync(() -> {
         try {
            List<Session> sessions = this.sessionStore.load();
            synchronized (this) {
               this.sessions.clear();
               this.accounts.clear();

               for (Session session : sessions) {
                  this.loadSession(session);
               }
            }
         } catch (Exception var7) {
            log.error("Unable to load saved Jagex accounts", var7);
         }
      }, this.executor);
   }

   public synchronized List<Session> getSessions() {
      return new ArrayList<>(this.sessions);
   }

   public synchronized Account[] getAccounts(String sessionId) {
      return this.accounts.get(sessionId);
   }

   public synchronized void removeSession(Session session) {
      List<Session> updatedAccounts = new ArrayList<>(this.sessions);
      if (updatedAccounts.remove(session)) {
         try {
            this.sessionStore.save(updatedAccounts);
         } catch (IOException var4) {
            log.error("Unable to remove Jagex account session for {}", session.getNickname(), var4);
            return;
         }

         this.sessions.clear();
         this.sessions.addAll(updatedAccounts);
         this.accounts.remove(session.getSessionId());
      }
   }

   public void performJagexAuthFlow() throws IOException, InterruptedException {
      String idToken = this.launchJagexAuth();
      this.handleJagexAuthCompletion(idToken);
   }

   private String launchJagexAuth() throws IOException, InterruptedException {
      if (isAndroid()) {
         return launchAndroidJagexAuth();
      }

      URL url = getJagexAuthURL();
      if (url == null) {
         throw new RuntimeException("jagexauth not found");
      } else {
         Path temp;
         try (InputStream in = url.openStream()) {
            temp = Files.createTempFile("runelite_jagexauth", "");
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
         }

         temp.toFile().setExecutable(true);
         Process process = new ProcessBuilder(temp.toAbsolutePath().toString(), "--prompt=login").redirectErrorStream(true).start();
         String idToken = "";
         BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));

         String s;
         try {
            while ((s = in.readLine()) != null) {
               s = s.trim();
               String[] split = s.split(" ");
               if (split.length > 1 && split[0].equals("id_token:")) {
                  idToken = split[1];
               }
            }
         } catch (Throwable var11) {
            try {
               in.close();
            } catch (Throwable var8) {
               var11.addSuppressed(var8);
            }

            throw var11;
         }

         in.close();
         if (idToken.isBlank()) {
            throw new RuntimeException("Login did not return an id token");
         } else {
            process.waitFor();
            Files.delete(temp);
            return idToken;
         }
      }
   }

   /** Scheme handled by the Android launcher (MainActivity#openLink). */
   private static final String ANDROID_AUTH_SCHEME = "runelite-jagexauth";
   private static final int ANDROID_AUTH_TIMEOUT_MS = 15 * 60 * 1000;

   private static boolean isAndroid() {
      return Boolean.getBoolean("runelite.android") || System.getProperty("os.version", "").startsWith("Android");
   }

   private static String launchAndroidJagexAuth() throws IOException {
      byte[] secretBytes = new byte[16];
      new SecureRandom().nextBytes(secretBytes);
      StringBuilder secretBuilder = new StringBuilder();
      for (byte b : secretBytes) {
         secretBuilder.append(String.format("%02x", b));
      }
      String secret = secretBuilder.toString();

      try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
         server.setSoTimeout(ANDROID_AUTH_TIMEOUT_MS);
         URI uri = URI.create(ANDROID_AUTH_SCHEME + "://login?port=" + server.getLocalPort() + "&secret=" + secret);
         if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("The Android launcher bridge (Desktop#browse) is not available");
         }
         log.info("Opening Android Jagex login activity");
         Desktop.getDesktop().browse(uri);

         while (true) {
            Socket socket;
            try {
               socket = server.accept();
            } catch (SocketTimeoutException e) {
               throw new IOException("Timed out waiting for the Jagex login to finish", e);
            }

            try (Socket s = socket) {
               s.setSoTimeout(10000);
               BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
               String line = reader.readLine();
               if (line == null) {
                  continue;
               }

               // "<secret> id_token: <jwt>" or "<secret> error <message>"
               String[] parts = line.trim().split(" ", 3);
               if (parts.length < 2
                  || !MessageDigest.isEqual(parts[0].getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
                  log.warn("Ignoring unexpected connection on the Jagex login socket");
                  continue;
               }

               if ("id_token:".equals(parts[1]) && parts.length == 3 && !parts[2].isBlank()) {
                  return parts[2].trim();
               }

               throw new IOException("Jagex login did not complete: " + (parts.length == 3 ? parts[2] : parts[1]));
            }
         }
      }
   }

   private void handleJagexAuthCompletion(String idToken) throws IOException {
      JagexAuthJwt jwt = JagexAuthJwt.decode(idToken, this.gson);
      String subject = jwt.getSub();
      String nickname = jwt.getNickname();
      String sessionId = this.authClient.getSessionId(idToken);
      Session session = new Session(sessionId, subject, nickname);
      synchronized (this) {
         if (this.sessions.stream().anyMatch(a -> subject.equals(a.getSub()))) {
            log.info("Jagex account {} ({}) already exists", subject, nickname);
         } else {
            this.loadSession(session);
            this.sessionStore.save(this.sessions);
         }
      }
   }

   private void loadSession(Session session) {
      try {
         Account[] characters = this.authClient.getAccounts(session.getSessionId());
         this.sessions.add(session);
         this.accounts.put(session.getSessionId(), characters);
      } catch (IOException var3) {
         log.error("Unable to load characters for Jagex account {}", session.getNickname(), var3);
      }
   }

   public void switchJagexAccount(String sessionId, String sub, String displayName) {
      this.clientThread.invoke(() -> this.client.switchJagexAccount(sessionId, sub, displayName));
   }

   private static URL getJagexAuthURL() {
      String propOverride = System.getProperty("runelite.jagexauth");
      if (propOverride != null) {
         Path path = Path.of(propOverride);

         try {
            return path.toUri().toURL();
         } catch (MalformedURLException var4) {
            throw new RuntimeException(var4);
         }
      } else {
         String ext = "";
         String os;
         switch (OSType.getOSType()) {
            case Windows:
               os = "Win";
               ext = ".exe";
               break;
            case MacOS:
               os = "macOS";
               break;
            case Linux:
               os = "Linux";
               break;
            default:
               return null;
         }

         String arch = System.getProperty("os.arch");
         return JagexAuthService.class.getResource("/net/runelite/jagexauth/JagexAuth-" + os + "-" + arch + ext);
      }
   }

   @Inject
   public JagexAuthService(
      Client client, ClientThread clientThread, JagexAuthClient authClient, SessionStore sessionStore, Gson gson, ScheduledExecutorService executor
   ) {
      this.client = client;
      this.clientThread = clientThread;
      this.authClient = authClient;
      this.sessionStore = sessionStore;
      this.gson = gson;
      this.executor = executor;
   }
}
