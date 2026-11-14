package tn.esprit.marketsim.user.profile;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserRepository;
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLogService;

/**
 * Photo de profil. L'image envoyee est decodee puis entierement redessinee en PNG carre 256 x 256 :
 * un fichier qui n'est pas une vraie image est refuse, et les metadonnees (position GPS d'une photo de
 * telephone, appareil...) disparaissent.
 */
@Service
@RequiredArgsConstructor
public class AvatarService {

    static final int SIZE = 256;
    static final int MAX_BYTES = 3 * 1024 * 1024;
    static final int MAX_SIDE = 6000;

    private final UserAvatarRepository repository;
    private final UserRepository userRepository;
    private final ActivityLogService activityLogService;

    /** @param dataUrl image en « data:image/...;base64,... » ou en base64 simple */
    @Transactional
    public User upload(User user, String dataUrl) {
        byte[] png = normalize(decode(dataUrl));
        UserAvatar a = repository.findById(user.getId()).orElseGet(UserAvatar::new);
        a.setUserId(user.getId());
        a.setAvatarKey(UUID.randomUUID().toString());
        a.setData(png);
        a.setUpdatedAt(Instant.now());
        repository.save(a);
        User u = userRepository.findById(user.getId()).orElseThrow();
        u.setAvatarKey(a.getAvatarKey());
        userRepository.save(u);
        activityLogService.recordAfterCommit(u.getId(), u.getId(), ActivityAction.AVATAR_UPDATE, "UPLOAD");
        return u;
    }

    @Transactional
    public User remove(User user) {
        repository.deleteById(user.getId());
        User u = userRepository.findById(user.getId()).orElseThrow();
        u.setAvatarKey(null);
        userRepository.save(u);
        activityLogService.recordAfterCommit(u.getId(), u.getId(), ActivityAction.AVATAR_UPDATE, "REMOVE");
        return u;
    }

    @Transactional(readOnly = true)
    public Optional<byte[]> image(String key) {
        return repository.findByAvatarKey(key).map(UserAvatar::getData);
    }

    static byte[] decode(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) throw new IllegalArgumentException("Aucune image reçue");
        String b64 = dataUrl.contains(",") ? dataUrl.substring(dataUrl.indexOf(',') + 1) : dataUrl;
        if (b64.length() > MAX_BYTES * 4 / 3 + 8) throw new IllegalArgumentException("Image trop lourde (3 Mo maximum)");
        try {
            return Base64.getMimeDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Image illisible");
        }
    }

    /** Recadre au centre en carre et redimensionne en 256 x 256, encode en PNG. */
    static byte[] normalize(byte[] raw) {
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException e) {
            src = null;
        }
        if (src == null) throw new IllegalArgumentException("Ce fichier n'est pas une image JPEG, PNG, GIF ou BMP valide");
        if (src.getWidth() > MAX_SIDE || src.getHeight() > MAX_SIDE) throw new IllegalArgumentException("Image trop grande");
        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, SIZE, SIZE);
        g.drawImage(src, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null);
        g.dispose();
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(out, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Encodage PNG impossible", e);
        }
    }
}
