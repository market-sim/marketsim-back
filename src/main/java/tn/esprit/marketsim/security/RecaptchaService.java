package tn.esprit.marketsim.security;

import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Google reCAPTCHA v2 (case « Je ne suis pas un robot ») sur la connexion, l'inscription et le mot de passe oublie :
 * bloque les robots qui essaient des milliers de mots de passe. Le jeton du navigateur est verifie aupres de Google.
 *
 * <p>Par defaut, les cles de TEST officielles de Google sont utilisees (la case s'affiche avec un avertissement
 * « test » et la verification reussit toujours). Pour la production, definir RECAPTCHA_SITE_KEY et RECAPTCHA_SECRET
 * avec des cles creees sur https://www.google.com/recaptcha/admin. Cles vides = reCAPTCHA desactive.</p>
 */
@Service
public class RecaptchaService {

    public static final String HEADER = "X-Captcha-Token";
    private static final Logger log = LoggerFactory.getLogger(RecaptchaService.class);
    private static final Pattern SUCCESS = Pattern.compile("\"success\"\\s*:\\s*true");
    private static final String VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify";

    private final String siteKey;
    private final String secret;
    private final boolean failOpen;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .proxy(ProxySelector.getDefault())
            .build();

    public RecaptchaService(@Value("${app.recaptcha.site-key:}") String siteKey,
                            @Value("${app.recaptcha.secret:}") String secret,
                            @Value("${app.recaptcha.fail-open:false}") boolean failOpen) {
        this.siteKey = siteKey == null ? "" : siteKey.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.failOpen = failOpen;
    }

    public boolean enabled() {
        return !siteKey.isEmpty() && !secret.isEmpty();
    }

    public String siteKey() {
        return enabled() ? siteKey : null;
    }

    /** Leve IllegalArgumentException (=> 400) si la case n'a pas ete cochee ou si Google refuse le jeton. */
    public void verify(String token, String remoteIp) {
        if (!enabled()) return;
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Coche la case « Je ne suis pas un robot ».");
        }
        Boolean ok;
        try {
            ok = callGoogle(token.trim(), remoteIp);
        } catch (Exception e) {
            log.warn("reCAPTCHA : verification impossible ({})", e.getMessage());
            ok = null;
        }
        if (ok == null) {
            if (failOpen) return;
            throw new IllegalArgumentException("Vérification anti-robot indisponible (Google injoignable) : réessaie dans un instant.");
        }
        if (!ok) {
            throw new IllegalArgumentException("Vérification anti-robot échouée ou expirée : recoche la case.");
        }
    }

    /** Vrai/faux selon Google ; null si Google n'a pas pu etre joint. Surchargeable dans les tests. */
    protected Boolean callGoogle(String token, String remoteIp) throws Exception {
        String body = "secret=" + enc(secret) + "&response=" + enc(token)
                + (remoteIp == null || remoteIp.isBlank() ? "" : "&remoteip=" + enc(remoteIp));
        HttpRequest req = HttpRequest.newBuilder(URI.create(VERIFY_URL))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return null;
        return SUCCESS.matcher(res.body()).find();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
