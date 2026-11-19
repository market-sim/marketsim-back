package tn.esprit.marketsim.analysis.indicator;

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
 * Module 3 : nom de configuration deja utilise -> HTTP 409, avec le meme format de reponse que le gestionnaire
 * commun ({@code timestamp}, {@code status}, {@code message}). Limite au controleur des configurations :
 * le gestionnaire commun (404, 400) n'est pas modifie.
 */
@RestControllerAdvice(assignableTypes = IndicatorConfigController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IndicatorConfigExceptionHandler {

    @ExceptionHandler(DuplicateConfigNameException.class)
    public ResponseEntity<Map<String, Object>> duplicate(DuplicateConfigNameException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.CONFLICT.value());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
