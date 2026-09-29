package in.yesmadam.botin.platform.catalogue;

import in.yesmadam.botin.platform.api.dto.L1Group;
import in.yesmadam.botin.platform.api.dto.Option;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Everything the menu knows, it knows from the catalogue table.
 *
 * There is no hard-coded list of L1 paths anywhere in this codebase. The L1 menu is
 * DERIVED from the distinct l1_code values across all 38 rows, ordered by the lowest
 * display_order in each group. That matters: enabling a concern is a data change, and
 * a data change must never need a code change to show up.
 */
@Service
@Transactional(readOnly = true)
public class CatalogueService {

    private final ConcernCatalogueRepository repository;

    public CatalogueService(ConcernCatalogueRepository repository) {
        this.repository = repository;
    }

    /**
     * Every L1 path, including those with no active concern underneath.
     *
     * A group with zero active concerns is deliberately still returned. Hiding it
     * would make the menu change shape as concerns are switched on, and a partner
     * who learned where "Violations" sits should keep finding it in the same place.
     * Selecting into an empty group lands on the "not available" step, which is a
     * defined outcome rather than a dead end.
     */
    public List<L1Group> l1Groups() {
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Integer> order = new HashMap<>();
        Map<String, Integer> activeCount = new HashMap<>();

        for (ConcernCatalogue c : repository.findAll()) {
            labels.putIfAbsent(c.getL1Code(), c.getL1Label());
            order.merge(c.getL1Code(), c.getDisplayOrder(), Math::min);
            activeCount.merge(c.getL1Code(), c.isActive() ? 1 : 0, Integer::sum);
        }

        List<L1Group> groups = new ArrayList<>();
        labels.forEach((code, label) ->
                groups.add(new L1Group(code, label, activeCount.getOrDefault(code, 0))));
        groups.sort(Comparator.comparingInt(g -> order.getOrDefault(g.code(), Integer.MAX_VALUE)));
        return groups;
    }

    /** The active L2 concerns under one L1 path, in display order. Never the inactive ones. */
    public List<Option> activeConcernsIn(String l1Code) {
        return repository.findByL1CodeAndActiveTrueOrderByDisplayOrderAsc(l1Code).stream()
                .map(c -> new Option(c.getL2Code(), c.getL2Label()))
                .toList();
    }

    /** True when this L1 code exists at all, active or not. */
    /**
     * Every ACTIVE concern, for surfaces that describe the catalogue rather than serve a
     * partner from it — the console's table browser is the only caller today. Inactive
     * rows are excluded here because a concern that is switched off has no rules worth
     * showing; the catalogue itself is where you look to see what is switched off.
     */
    public List<ConcernCatalogue> all() {
        return repository.findByActiveTrueOrderByL1CodeAscDisplayOrderAsc();
    }

    public boolean l1Exists(String l1Code) {
        return repository.findAll().stream().anyMatch(c -> c.getL1Code().equals(l1Code));
    }

    /** The concern row, whether active or not — the caller decides what inactive means. */
    public Optional<ConcernCatalogue> find(String l2Code) {
        return repository.findById(l2Code);
    }

    /**
     * Active means: built, tested, and safe to route a partner into. An inactive row
     * is a concern we know exists and have not built. The distinction is the reason
     * all 38 rows are seeded rather than only the eight.
     */
    public boolean isActive(String l2Code) {
        return find(l2Code).map(ConcernCatalogue::isActive).orElse(false);
    }
}
