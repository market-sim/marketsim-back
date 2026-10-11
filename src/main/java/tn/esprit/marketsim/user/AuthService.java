package tn.esprit.marketsim.user;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.security.JwtService;
import tn.esprit.marketsim.user.dto.AuthDtos.AuthResponse;
import tn.esprit.marketsim.user.dto.AuthDtos.LoginRequest;
import tn.esprit.marketsim.user.dto.AuthDtos.RegisterRequest;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new IllegalArgumentException("Cet email est deja utilise");
        }
        User user = User.builder()
                .firstName(req.firstName())
                .lastName(req.lastName())
                .email(req.email())
                .password(passwordEncoder.encode(req.password()))
                .role(Role.USER)
                .enabled(true)
                .build();
        userRepository.save(user);
        return new AuthResponse(jwtService.generateToken(user), user);
    }

    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.email())
                .filter(u -> passwordEncoder.matches(req.password(), u.getPassword()))
                .orElseThrow(() -> new IllegalArgumentException("Email ou mot de passe incorrect"));
        if (!user.isEnabled()) {
            throw new IllegalArgumentException("Compte desactive");
        }
        return new AuthResponse(jwtService.generateToken(user), user);
    }
}
