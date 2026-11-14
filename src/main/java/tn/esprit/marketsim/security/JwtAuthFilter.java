package tn.esprit.marketsim.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserRepository;
import tn.esprit.marketsim.user.account.SessionService;

/**
 * Lit le header "Authorization: Bearer <token>" et authentifie l'utilisateur.
 * Un jeton rattache a une session (claim « sid ») n'est accepte que si la session est encore active :
 * apres une deconnexion ou une revocation, il est refuse immediatement (=> 401).
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final SessionService sessionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                // un jeton d'etape 2 (double authentification) ne donne acces a rien d'autre
                if (claims.get(JwtService.PURPOSE_CLAIM) == null) {
                    String sid = claims.get(JwtService.SESSION_CLAIM, String.class);
                    userRepository.findByEmail(claims.getSubject())
                            .filter(User::isEnabled)
                            .filter(u -> !u.isTemporarilyLocked())
                            .filter(u -> sid == null || sessionService.touchIfActive(sid, u.getId()))
                            .ifPresent(user -> {
                                var auth = new UsernamePasswordAuthenticationToken(
                                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
                                auth.setDetails(sid);
                                SecurityContextHolder.getContext().setAuthentication(auth);
                            });
                }
            } catch (Exception e) {
                // token invalide ou expire : la requete continue sans authentification (=> 401)
            }
        }
        chain.doFilter(request, response);
    }
}
