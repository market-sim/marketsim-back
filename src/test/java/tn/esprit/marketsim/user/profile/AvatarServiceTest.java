package tn.esprit.marketsim.user.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class AvatarServiceTest {

    static String jpegDataUrl(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", bos);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    @Test
    void imageIsCroppedToA256SquarePng() throws Exception {
        byte[] png = AvatarService.normalize(AvatarService.decode(jpegDataUrl(800, 500)));
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(256, out.getWidth());
        assertEquals(256, out.getHeight());
        assertEquals((byte) 0x89, png[0]); // signature PNG
    }

    @Test
    void nonImageFilesAreRejected() {
        String fake = "data:image/png;base64," + Base64.getEncoder().encodeToString("<script>alert(1)</script>".getBytes());
        assertThrows(IllegalArgumentException.class, () -> AvatarService.normalize(AvatarService.decode(fake)));
        assertThrows(IllegalArgumentException.class, () -> AvatarService.decode(""));
    }
}
