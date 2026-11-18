package tn.esprit.marketsim.analysis.fundamental;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Module 3 : source de donnees fondamentales indisponible -> HTTP 503, avec le meme format de reponse que le
 * gestionnaire commun ({@code timestamp}, {@code status}, {@code message}). Limite au controleur de l'analyse
 * fondamentale : le gestionnaire commun (404, 400) n'est pas modifie.
 */
@RestControllerAdvice(assignableTypes = FundamentalsController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FundamentalsExceptionHandler {

    @ExceptionHandler(FundamentalsUnavailableException.class)
    public ResponseEntity<Map<String, Object>> unavailable(FundamentalsUnavailableException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
