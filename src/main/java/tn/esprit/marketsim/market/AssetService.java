package tn.esprit.marketsim.market;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.NotFoundException;

@Service
@RequiredArgsConstructor
public class AssetService {

    private final AssetRepository repository;

    public List<Asset> findAll() {
        return repository.findAll();
    }

    public Asset findOne(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Actif introuvable"));
    }

    public Asset create(Asset asset) {
        asset.setId(null);
        return repository.save(asset);
    }

    public Asset update(Long id, Asset data) {
        Asset asset = findOne(id);
        asset.setSymbol(data.getSymbol());
        asset.setName(data.getName());
        asset.setType(data.getType());
        asset.setPrice(data.getPrice());
        asset.setChangePct(data.getChangePct());
        return repository.save(asset);
    }

    public void delete(Long id) {
        repository.delete(findOne(id));
    }
}
