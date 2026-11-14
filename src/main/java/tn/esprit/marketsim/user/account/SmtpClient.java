package tn.esprit.marketsim.user.account;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Client SMTP (RFC 5321) ecrit avec la bibliotheque standard Java : STARTTLS ou SSL direct, authentification
 * AUTH PLAIN / LOGIN, message MIME multipart (texte + HTML) en UTF-8. Suffisant pour Mailtrap, Gmail
 * (mot de passe d'application), Brevo, Outlook...
 *
 * <p>Securite : verification du certificat et du nom d'hote du serveur en TLS, refus des retours a la ligne
 * dans les adresses (injection d'en-tetes), objet encode (RFC 2047).</p>
 */
public final class SmtpClient {

    public enum Security { NONE, STARTTLS, SSL }

    public record Config(String host, int port, String username, String password, String from, String fromName,
                         Security security, int timeoutMs) {

        public boolean configured() {
            return host != null && !host.isBlank();
        }
    }

    public record Message(String to, String subject, String text, String html) {
    }

    private SmtpClient() {
    }

    private static final class Conn implements AutoCloseable {
        Socket socket;
        BufferedReader in;
        OutputStream out;

        Conn(Socket s) throws IOException {
            attach(s);
        }

        void attach(Socket s) throws IOException {
            socket = s;
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = s.getOutputStream();
        }

        /** Lit une reponse (eventuellement sur plusieurs lignes « 250-... »). */
        Reply read() throws IOException {
            List<String> lines = new ArrayList<>();
            String line;
            do {
                line = in.readLine();
                if (line == null) throw new IOException("Connexion SMTP fermée par le serveur");
                lines.add(line);
            } while (line.length() > 3 && line.charAt(3) == '-');
            int code;
            try {
                code = Integer.parseInt(line.substring(0, 3));
            } catch (RuntimeException e) {
                throw new IOException("Réponse SMTP invalide : " + line);
            }
            return new Reply(code, lines);
        }

        Reply cmd(String command, int... expected) throws IOException {
            out.write((command + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            Reply r = read();
            for (int e : expected) if (r.code() == e) return r;
            // on ne recopie jamais une commande AUTH (elle contient le mot de passe) dans l'erreur
            String shown = command.startsWith("AUTH") ? "AUTH" : command.length() > 40 ? command.substring(0, 40) : command;
            throw new IOException("Le serveur SMTP a refusé « " + shown + " » : " + r.text());
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // deja ferme
            }
        }
    }

    record Reply(int code, List<String> lines) {
        String text() {
            return String.join(" ", lines);
        }

        boolean supports(String capability) {
            return lines.stream().anyMatch(l -> l.length() > 4 && l.substring(4).toUpperCase(Locale.ROOT).startsWith(capability));
        }
    }

    public static void send(Config c, Message m) throws IOException {
        checkAddress(c.from());
        checkAddress(m.to());
        SSLSocketFactory tls = (SSLSocketFactory) SSLSocketFactory.getDefault();
        Socket raw;
        if (c.security() == Security.SSL) {
            raw = secure(tls.createSocket(), c);
            raw.connect(new InetSocketAddress(c.host(), c.port()), c.timeoutMs());
            ((SSLSocket) raw).startHandshake();
        } else {
            raw = new Socket();
            raw.connect(new InetSocketAddress(c.host(), c.port()), c.timeoutMs());
        }
        raw.setSoTimeout(c.timeoutMs());
        try (Conn conn = new Conn(raw)) {
            expect(conn.read(), 220);
            Reply ehlo = conn.cmd("EHLO marketsim.local", 250);
            if (c.security() == Security.STARTTLS) {
                if (!ehlo.supports("STARTTLS")) throw new IOException("Le serveur SMTP ne propose pas STARTTLS");
                conn.cmd("STARTTLS", 220);
                SSLSocket s = secure(tls.createSocket(conn.socket, c.host(), c.port(), true), c);
                s.startHandshake();
                conn.attach(s);
                ehlo = conn.cmd("EHLO marketsim.local", 250);
            }
            if (c.username() != null && !c.username().isBlank()) {
                if (ehlo.supports("AUTH") && ehlo.lines().stream().anyMatch(l -> l.toUpperCase(Locale.ROOT).contains("PLAIN"))) {
                    String token = b64(("\0" + c.username() + "\0" + c.password()).getBytes(StandardCharsets.UTF_8));
                    conn.cmd("AUTH PLAIN " + token, 235);
                } else {
                    conn.cmd("AUTH LOGIN", 334);
                    conn.cmd(b64(c.username().getBytes(StandardCharsets.UTF_8)), 334);
                    conn.cmd(b64(c.password().getBytes(StandardCharsets.UTF_8)), 235);
                }
            }
            conn.cmd("MAIL FROM:<" + c.from() + ">", 250);
            conn.cmd("RCPT TO:<" + m.to() + ">", 250, 251);
            conn.cmd("DATA", 354);
            conn.out.write(dotStuff(mime(c, m)).getBytes(StandardCharsets.UTF_8));
            conn.cmd(".", 250); // le message se termine deja par CRLF
            try {
                conn.cmd("QUIT", 221);
            } catch (IOException ignored) {
                // le message est deja accepte
            }
        }
    }

    private static SSLSocket secure(java.net.Socket s, Config c) {
        SSLSocket ssl = (SSLSocket) s;
        SSLParameters p = ssl.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS"); // verifie que le certificat correspond bien a c.host()
        ssl.setSSLParameters(p);
        return ssl;
    }

    private static void expect(Reply r, int code) throws IOException {
        if (r.code() != code) throw new IOException("Réponse SMTP inattendue : " + r.text());
    }

    static void checkAddress(String a) {
        if (a == null || !a.matches("[^\\s<>@,;]+@[^\\s<>@,;]+\\.[^\\s<>@,;]+")) {
            throw new IllegalArgumentException("Adresse e-mail invalide");
        }
    }

    static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    /** En-tete encode RFC 2047 (accents, emojis). */
    static String encodeHeader(String s) {
        return "=?UTF-8?B?" + b64(s.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    static String mime(Config c, Message m) {
        String boundary = "ms-" + UUID.randomUUID();
        String domain = c.from().substring(c.from().indexOf('@') + 1);
        StringBuilder sb = new StringBuilder();
        sb.append("From: ").append(encodeHeader(c.fromName() == null ? "MarketSim AI" : c.fromName())).append(" <").append(c.from()).append(">\r\n");
        sb.append("To: <").append(m.to()).append(">\r\n");
        sb.append("Subject: ").append(encodeHeader(m.subject())).append("\r\n");
        sb.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())).append("\r\n");
        sb.append("Message-ID: <").append(UUID.randomUUID()).append('@').append(domain).append(">\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append("\"\r\n\r\n");
        part(sb, boundary, "text/plain", m.text());
        if (m.html() != null) part(sb, boundary, "text/html", m.html());
        sb.append("--").append(boundary).append("--\r\n");
        return sb.toString();
    }

    private static void part(StringBuilder sb, String boundary, String type, String content) {
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: ").append(type).append("; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        String enc = b64(content.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < enc.length(); i += 76) sb.append(enc, i, Math.min(enc.length(), i + 76)).append("\r\n");
    }

    /** Une ligne qui commence par « . » est doublee (sinon le serveur croirait a la fin du message). */
    static String dotStuff(String data) {
        return data.replace("\r\n.", "\r\n..");
    }
}
