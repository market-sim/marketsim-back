package tn.esprit.marketsim.user.anomaly;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class LoginRequestContext {

    public static final String DEVICE_HINT_HEADER = "X-Device-Hint";

    public String userAgent() {
        HttpServletRequest request = current();
        return request == null ? null : request.getHeader("User-Agent");
    }

    public String deviceHint() {
        HttpServletRequest request = current();
        return request == null ? null : request.getHeader(DEVICE_HINT_HEADER);
    }

    private static HttpServletRequest current() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest();
        }
        return null;
    }
}
