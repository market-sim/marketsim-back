package tn.esprit.marketsim.user.account;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

import jakarta.annotation.PreDestroy;

/**
 * Envoi des e-mails de la plateforme. Chaque message est d'abord range dans la boite d'envoi (table mail_outbox,
 * visible par l'administrateur). Si un serveur SMTP est configure (Mailtrap, Gmail, Brevo...), il est ensuite
 * envoye pour de vrai, en arriere-plan apres la validation de la transaction : l'utilisateur n'attend pas le
 * serveur mail, et un e-mail n'est jamais envoye pour une action annulee. Le statut (SENT / FAILED) est note.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final MailMessageRepository repository;
    private final SmtpClient.Config smtp;
    private final String frontendUrl;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mail-sender");
        t.setDaemon(true);
        return t;
    });

    public MailService(MailMessageRepository repository,
                       @Value("${app.mail.smtp.host:}") String host,
                       @Value("${app.mail.smtp.port:587}") int port,
                       @Value("${app.mail.smtp.username:}") String username,
                       @Value("${app.mail.smtp.password:}") String password,
                       @Value("${app.mail.smtp.security:STARTTLS}") String security,
                       @Value("${app.mail.from:no-reply@marketsim.tn}") String from,
                       @Value("${app.mail.from-name:MarketSim AI}") String fromName,
                       @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.repository = repository;
        this.smtp = new SmtpClient.Config(host, port, username, password, from, fromName,
                SmtpClient.Security.valueOf(security.trim().toUpperCase()), 15000);
        this.frontendUrl = frontendUrl;
    }

    public record MailStatus(boolean smtpConfigured, String host, int port, String security, String from) {
    }

    public MailStatus status() {
        return new MailStatus(smtp.configured(), smtp.configured() ? smtp.host() : null, smtp.port(), smtp.security().name(), smtp.from());
    }

    @Transactional
    public MailMessage send(String to, String subject, String body, String actionUrl, String kind) {
        MailMessage m = new MailMessage();
        m.setRecipient(to);
        m.setSubject(subject);
        m.setBody(body);
        m.setActionUrl(actionUrl);
        m.setKind(kind);
        m.setCreatedAt(Instant.now());
        m.setStatus(smtp.configured() ? "PENDING" : "LOCAL");
        repository.save(m);
        log.info("[e-mail] a {} : {}{}", to, subject, actionUrl == null ? "" : " -> " + actionUrl);
        if (smtp.configured()) {
            Long id = m.getId();
            SmtpClient.Message msg = new SmtpClient.Message(to, subject, text(body, actionUrl), html(subject, body, actionUrl));
            Runnable job = () -> deliver(id, msg);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        sender.submit(job);
                    }
                });
            } else {
                sender.submit(job);
            }
        }
        return m;
    }

    private void deliver(Long id, SmtpClient.Message msg) {
        String status;
        String error = null;
        try {
            SmtpClient.send(smtp, msg);
            status = "SENT";
        } catch (Exception e) {
            status = "FAILED";
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[e-mail] echec de l'envoi a {} : {}", msg.to(), error);
        }
        String finalStatus = status;
        String finalError = error == null ? null : error.length() > 300 ? error.substring(0, 300) : error;
        repository.findById(id).ifPresent(m -> {
            m.setStatus(finalStatus);
            m.setError(finalError);
            if ("SENT".equals(finalStatus)) m.setSentAt(Instant.now());
            repository.save(m);
        });
    }

    @Transactional(readOnly = true)
    public List<MailMessage> latest() {
        return repository.findTop50ByOrderByCreatedAtDesc();
    }

    @PreDestroy
    void shutdown() {
        sender.shutdown();
    }

    // ─── modele d'e-mail ─────────────────────────────────────────────────────────────────────

    static String text(String body, String actionUrl) {
        return (body == null ? "" : body) + (actionUrl == null ? "" : "\n\n" + actionUrl)
                + "\n\n— MarketSim AI, simulateur pédagogique de trading (argent virtuel).";
    }

    /** E-mail HTML aux couleurs de MarketSim (tableaux + styles en ligne : compatible Gmail et Outlook). */
    String html(String subject, String body, String actionUrl) {
        String safeBody = HtmlUtils.htmlEscape(body == null ? "" : body).replace("\n", "<br>");
        String button = actionUrl == null ? "" : """
                <tr><td style="padding:8px 32px 28px">
                  <a href="%s" style="display:inline-block;background:#2563EB;color:#ffffff;text-decoration:none;font-weight:600;
                     padding:12px 22px;border-radius:10px;font-size:15px">Ouvrir MarketSim</a>
                  <p style="color:#64748B;font-size:12px;margin:14px 0 0">Si le bouton ne marche pas, copie ce lien :<br>
                     <span style="color:#2563EB;word-break:break-all">%s</span></p>
                </td></tr>""".formatted(HtmlUtils.htmlEscape(actionUrl), HtmlUtils.htmlEscape(actionUrl));
        return """
                <!doctype html><html lang="fr"><body style="margin:0;background:#EEF2F7;font-family:Segoe UI,Arial,sans-serif">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="padding:28px 12px"><tr><td align="center">
                  <table role="presentation" width="560" cellpadding="0" cellspacing="0" style="max-width:560px;background:#ffffff;border-radius:16px;overflow:hidden">
                    <tr><td style="background:#0D1B3E;padding:20px 32px">
                      <span style="color:#F59E0B;font-weight:700;font-size:18px">Market</span><span style="color:#38BDF8;font-weight:700;font-size:18px">Sim AI</span>
                      <div style="color:#94A3B8;font-size:12px">Simulation Platform</div>
                    </td></tr>
                    <tr><td style="padding:28px 32px 8px">
                      <h1 style="margin:0 0 12px;font-size:20px;color:#0F172A">%s</h1>
                      <p style="margin:0;font-size:15px;line-height:1.6;color:#334155">%s</p>
                    </td></tr>
                    %s
                    <tr><td style="background:#F8FAFC;padding:16px 32px;color:#94A3B8;font-size:11px">
                      Tu reçois cet e-mail parce que tu as un compte MarketSim AI. Tout l'argent du simulateur est virtuel.<br>
                      Gérer mes notifications : <a href="%s/profile" style="color:#64748B">%s/profile</a>
                    </td></tr>
                  </table>
                </td></tr></table></body></html>""".formatted(HtmlUtils.htmlEscape(subject), safeBody, button,
                HtmlUtils.htmlEscape(frontendUrl), HtmlUtils.htmlEscape(frontendUrl));
    }
}
