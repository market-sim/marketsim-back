package tn.esprit.marketsim.search;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.User;

/** Recherche globale (barre du haut). */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService service;

    @GetMapping
    public SearchService.Results search(@RequestParam(defaultValue = "") String q, @AuthenticationPrincipal User user) {
        return service.search(user, q);
    }
}
