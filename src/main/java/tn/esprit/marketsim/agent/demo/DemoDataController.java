package tn.esprit.marketsim.agent.demo;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.demo.DemoDataService.DemoResult;
import tn.esprit.marketsim.user.User;

/** Module 6 : remplit le compte connecte avec des agents de demonstration (soutenance). */
@RestController
@RequestMapping("/api/agents/demo")
@RequiredArgsConstructor
public class DemoDataController {

    private final DemoDataService demoDataService;

    @PostMapping
    public DemoResult generate(@AuthenticationPrincipal User user) {
        return demoDataService.generate(user);
    }
}
