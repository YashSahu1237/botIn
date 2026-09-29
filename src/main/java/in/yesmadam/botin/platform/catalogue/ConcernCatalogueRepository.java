package in.yesmadam.botin.platform.catalogue;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ConcernCatalogueRepository extends JpaRepository<ConcernCatalogue, String> {

    List<ConcernCatalogue> findByActiveTrueOrderByL1CodeAscDisplayOrderAsc();

    List<ConcernCatalogue> findByL1CodeAndActiveTrueOrderByDisplayOrderAsc(String l1Code);
}
