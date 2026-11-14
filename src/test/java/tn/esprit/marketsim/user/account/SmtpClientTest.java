package tn.esprit.marketsim.user.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Faux serveur SMTP local : verifie la conversation et le message recu par le serveur. */
class SmtpClientTest {

    static List<String> fakeServer(ServerSocket server, boolean refuseRcpt) throws Exception {
        List<String> log = new ArrayList<>();
        try (Socket s = server.accept()) {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            out.write("220 fake ESMTP\r\n".getBytes());
            String line;
            boolean data = false;
            StringBuilder body = new StringBuilder();
            while ((line = in.readLine()) != null) {
                if (data) {
                    if (line.equals(".")) {
                        data = false;
                        log.add("BODY:" + body);
                        out.write("250 queued\r\n".getBytes());
                    } else body.append(line).append("\n");
                    continue;
                }
                log.add(line);
                String reply;
                if (line.startsWith("EHLO")) reply = "250-fake\r\n250-AUTH PLAIN LOGIN\r\n250 8BITMIME";
                else if (line.startsWith("AUTH PLAIN")) reply = "235 ok";
                else if (line.startsWith("MAIL FROM")) reply = "250 ok";
                else if (line.startsWith("RCPT TO")) reply = refuseRcpt ? "550 no such user" : "250 ok";
                else if (line.equals("DATA")) {
                    reply = "354 go";
                    data = true;
                } else if (line.equals("QUIT")) {
                    out.write("221 bye\r\n".getBytes());
                    break;
                } else reply = "500 ?";
                out.write((reply + "\r\n").getBytes());
            }
        }
        return log;
    }

    @Test
    void sendsAuthenticatedMultipartMessage() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<List<String>> f = CompletableFuture.supplyAsync(() -> {
                try {
                    return fakeServer(server, false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            SmtpClient.Config c = new SmtpClient.Config("127.0.0.1", server.getLocalPort(), "user", "secret", "no-reply@marketsim.tn",
                    "MarketSim AI", SmtpClient.Security.NONE, 5000);
            SmtpClient.send(c, new SmtpClient.Message("yasmine@esprit.tn", "Réinitialisation ✓", "Bonjour\n.ligne", "<p>Bonjour</p>"));
            List<String> log = f.get(5, TimeUnit.SECONDS);
            String auth = log.stream().filter(l -> l.startsWith("AUTH PLAIN ")).findFirst().orElseThrow();
            assertEquals("\0user\0secret", new String(Base64.getDecoder().decode(auth.substring(11)), StandardCharsets.UTF_8));
            assertTrue(log.contains("MAIL FROM:<no-reply@marketsim.tn>"));
            assertTrue(log.contains("RCPT TO:<yasmine@esprit.tn>"));
            String body = log.stream().filter(l -> l.startsWith("BODY:")).findFirst().orElseThrow();
            assertTrue(body.contains("Subject: =?UTF-8?B?"));
            assertTrue(body.contains("multipart/alternative"));
            assertTrue(body.contains("text/html"));
        }
    }

    @Test
    void refusedRecipientIsReportedWithoutLeakingThePassword() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture.runAsync(() -> {
                try {
                    fakeServer(server, true);
                } catch (Exception ignored) {
                    // fin du test
                }
            });
            SmtpClient.Config c = new SmtpClient.Config("127.0.0.1", server.getLocalPort(), "user", "secret", "a@b.tn", null,
                    SmtpClient.Security.NONE, 5000);
            Exception e = assertThrows(java.io.IOException.class,
                    () -> SmtpClient.send(c, new SmtpClient.Message("x@y.tn", "s", "t", null)));
            assertTrue(e.getMessage().contains("550"));
            assertTrue(!e.getMessage().contains("secret"));
        }
    }

    @Test
    void headerInjectionInAddressesIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SmtpClient.checkAddress("a@b.tn\r\nBcc: victim@x.com"));
        assertThrows(IllegalArgumentException.class, () -> SmtpClient.checkAddress("pas-une-adresse"));
    }
}
