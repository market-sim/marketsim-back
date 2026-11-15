package tn.esprit.marketsim.common;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Transforme les exceptions en reponses JSON lisibles par le frontend. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    @ExceptionHandler(InsufficientDataException.class)
    public ResponseEntity<Map<String, Object>> insufficientData(InsufficientDataException ex) {
        Map<String, Object> map = body(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), null).getBody();
        // ajoute un statut explicite consommé par le frontend
        if (map != null) map.put("predictionStatus", "INSUFFICIENT_DATA");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(map);
    }

    @ExceptionHandler(ModelUnavailableException.class)
    public ResponseEntity<Map<String, Object>> modelUnavailable(ModelUnavailableException ex) {
        Map<String, Object> map = body(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), null).getBody();
        if (map != null) map.put("predictionStatus", "UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(map);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex) {
        return body(HttpStatus.BAD_REQUEST, "JSON invalide", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> errors.put(e.getField(), e.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "Donnees invalides", errors);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, Object details) {
        Map<String, Object> map = new HashMap<>();
        map.put("timestamp", Instant.now().toString());
        map.put("status", status.value());
        map.put("message", message);
        if (details != null) {
            map.put("errors", details);
        }
        return ResponseEntity.status(status).body(map);
    }
}
