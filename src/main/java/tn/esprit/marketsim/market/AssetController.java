package tn.esprit.marketsim.market;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/market/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService service;

    @GetMapping
    public List<Asset> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public Asset get(@PathVariable Long id) {
        return service.findOne(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Asset create(@Valid @RequestBody Asset asset) {
        return service.create(asset);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Asset update(@PathVariable Long id, @Valid @RequestBody Asset asset) {
        return service.update(id, asset);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
