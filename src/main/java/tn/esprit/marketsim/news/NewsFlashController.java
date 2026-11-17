package tn.esprit.marketsim.news;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.news.NewsFlashService.Flash;
import tn.esprit.marketsim.news.NewsFlashService.Topic;
import tn.esprit.marketsim.news.NewsTvService.TvChannel;

/** Flash d'actualites du trading dans le monde (ecran parlant de la page de l'agent). */
@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsFlashController {

    private final NewsFlashService flash;
    private final NewsTvService tv;

    /** @param topic WORLD (defaut), STOCKS, CRYPTO, FOREX, COMMODITIES ou RATES */
    @GetMapping("/flash")
    public Flash flash(@RequestParam(defaultValue = "WORLD") String topic, @RequestParam(defaultValue = "8") int limit) {
        return flash.flash(Topic.parse(topic), limit);
    }

    /** Chaines d'information boursiere en direct (lecteur YouTube officiel cote navigateur). */
    @GetMapping("/tv")
    public List<TvChannel> tv() {
        return tv.channels();
    }
}
