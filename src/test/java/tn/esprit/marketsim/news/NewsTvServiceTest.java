package tn.esprit.marketsim.news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Reperage de la diffusion en direct dans la page YouTube : pas de faux positif. */
class NewsTvServiceTest {

    private static final String CHANNEL = "UCIALMKvObZNtJ6AmdCLP7Lg";

    private static String page(String videoId, boolean live, String channelId) {
        return "<html><head><link rel=\"canonical\" href=\"https://www.youtube.com/watch?v=" + videoId + "\"></head><body>"
                + "...\"videoDetails\":{\"videoId\":\"" + videoId + "\",\"title\":\"Titre \\\"cite\\\" du direct\",\"lengthSeconds\":\"0\","
                + (live ? "\"isLive\":true," : "") + "\"keywords\":[\"finance\"],\"channelId\":\"" + channelId + "\",\"isOwnerViewing\":false}..."
                + "</body></html>";
    }

    @Test
    void aLiveStreamOfTheRightChannelIsFound() {
        assertEquals(Optional.of("QB5BNdBFujE"), NewsTvService.parseLiveVideoId(page("QB5BNdBFujE", true, CHANNEL), CHANNEL));
    }

    @Test
    void aReplayOfTheSameChannelIsNotMistakenForALiveStream() {
        assertTrue(NewsTvService.parseLiveVideoId(page("QB5BNdBFujE", false, CHANNEL), CHANNEL).isEmpty());
    }

    @Test
    void aLiveStreamFromAnotherChannelIsRejected() {
        assertTrue(NewsTvService.parseLiveVideoId(page("AAAAAAAAAAA", true, "UCautreChaine0000000000"), CHANNEL).isEmpty());
    }

    @Test
    void channelPagesWithoutAPlayerOrJunkAreIgnored() {
        assertTrue(NewsTvService.parseLiveVideoId("<html><link rel=\"canonical\" href=\"https://www.youtube.com/channel/" + CHANNEL + "\"></html>", CHANNEL).isEmpty());
        assertTrue(NewsTvService.parseLiveVideoId("", CHANNEL).isEmpty());
        assertTrue(NewsTvService.parseLiveVideoId(null, CHANNEL).isEmpty());
        assertTrue(NewsTvService.parseLiveVideoId("\"isLiveNow\":true", CHANNEL).isEmpty(), "un simple drapeau 'live' ailleurs dans la page ne suffit pas");
    }

    @Test
    void theRightStreamIsPickedWhenSeveralPlayersAppear() {
        String html = page("BBBBBBBBBBB", true, "UCautreChaine0000000000") + page("QB5BNdBFujE", true, CHANNEL);
        assertEquals(Optional.of("QB5BNdBFujE"), NewsTvService.parseLiveVideoId(html, CHANNEL));
    }

    @Test
    void videoIdsAreAlwaysElevenSafeCharacters() {
        // l'identifiant est place tel quel dans l'adresse du lecteur : seuls [A-Za-z0-9_-]{11} sont acceptes
        assertTrue(NewsTvService.parseLiveVideoId(page("QB5B\"><script>", true, CHANNEL), CHANNEL).isEmpty());
    }
}
