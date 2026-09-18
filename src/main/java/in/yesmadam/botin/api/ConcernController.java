package in.yesmadam.botin.api;

import in.yesmadam.botin.api.dto.L1Group;
import in.yesmadam.botin.api.dto.Option;
import in.yesmadam.botin.catalogue.CatalogueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The menu, served straight from the catalogue table.
 *
 * There is no hard-coded concern list behind either endpoint. Switching a concern on
 * is an UPDATE, and it shows up here on the next request with no deploy.
 */
@RestController
@RequestMapping("/help/concerns")
public class ConcernController {

    private final CatalogueService catalogue;

    public ConcernController(CatalogueService catalogue) {
        this.catalogue = catalogue;
    }

    /** Every L1 path, in display order, each with a count of what is live under it. */
    @GetMapping
    public List<L1Group> l1Paths() {
        return catalogue.l1Groups();
    }

    /**
     * The active L2 concerns under one L1 path.
     *
     * An L1 that exists but has nothing built returns an empty list with 200, not 404 —
     * "this path has nothing available yet" is a real answer, and it is a different
     * thing from "this path does not exist", which is a 404.
     */
    @GetMapping("/{l1Code}")
    public ResponseEntity<List<Option>> concernsIn(@PathVariable String l1Code) {
        if (!catalogue.l1Exists(l1Code)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(catalogue.activeConcernsIn(l1Code));
    }
}
