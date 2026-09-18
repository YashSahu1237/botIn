package in.yesmadam.botin.catalogue;

import jakarta.persistence.*;

/**
 * The single source of the L1/L2 taxonomy (ADR-006).
 *
 * Four columns are POINTERS at the artifacts a concern needs. A row on its own
 * does nothing — it is inert until the BPMN process, the fact provider bean, the
 * decision table and the templates all exist. The catalogue removes duplication;
 * it does not remove the build.
 */
@Entity
@Table(name = "concern_catalogue")
public class ConcernCatalogue {

    @Id
    @Column(name = "l2_code", length = 64)
    private String l2Code;

    @Column(name = "l1_code", nullable = false, length = 64)   private String l1Code;
    @Column(name = "l1_label", nullable = false, length = 160) private String l1Label;
    @Column(name = "l2_label", nullable = false, length = 200) private String l2Label;
    @Column(name = "display_order", nullable = false)          private int displayOrder;
    @Column(nullable = false)                                  private boolean active;

    /** Agent-connect trigger B. An authoritative override, checked before any decision table. */
    @Column(name = "mandatory_human", nullable = false)        private boolean mandatoryHuman;

    @Column(name = "process_key", length = 64)   private String processKey;
    @Column(name = "fact_provider", length = 64) private String factProvider;
    @Column(name = "dmn_key", length = 64)       private String dmnKey;
    @Column(name = "togglz_flag", length = 64)   private String togglzFlag;
    @Column(name = "default_tier", length = 8)   private String defaultTier;
    @Column                                      private Integer wave;
    @Column(name = "june_volume")                private Integer juneVolume;
    @Column(length = 4000)           private String notes;

    /**
     * What kinds of ending this concern can have, comma separated — BOT, TICKET,
     * UPHOLD, REROUTE, SELF-SERVE, V2, DEPRECATED, MIXED. Derived from the concern
     * mapping, not invented here. Read-mostly reference data; never queried by
     * element, which is why it is a list in one column rather than a join table.
     */
    @Column(name = "outcome_types", length = 64) private String outcomeTypes;

    /**
     * The concern's code in the EXISTING ticketing taxonomy (SpTicketingConcernEnum).
     * Null where no confident mapping exists — a wrong mapping here would silently
     * merge two different concerns in a report, which is worse than an absent one.
     */
    @Column(name = "legacy_concern_code") private Integer legacyConcernCode;

    protected ConcernCatalogue() { }

    public String getL2Code() { return l2Code; }
    public String getL1Code() { return l1Code; }
    public String getL1Label() { return l1Label; }
    public String getL2Label() { return l2Label; }
    public int getDisplayOrder() { return displayOrder; }
    public boolean isActive() { return active; }
    public boolean isMandatoryHuman() { return mandatoryHuman; }
    public String getProcessKey() { return processKey; }
    public String getFactProvider() { return factProvider; }
    public String getDmnKey() { return dmnKey; }
    public String getTogglzFlag() { return togglzFlag; }
    public String getDefaultTier() { return defaultTier; }
    public Integer getWave() { return wave; }
    public Integer getJuneVolume() { return juneVolume; }
    public String getNotes() { return notes; }
    public String getOutcomeTypes() { return outcomeTypes; }
    public Integer getLegacyConcernCode() { return legacyConcernCode; }
}
