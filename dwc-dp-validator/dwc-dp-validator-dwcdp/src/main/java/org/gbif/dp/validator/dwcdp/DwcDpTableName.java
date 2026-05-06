package org.gbif.dp.validator.dwcdp;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reserved DwC-DP table names as defined in the DwC-DP profile:
 * https://raw.githubusercontent.com/gbif/dwc-dp/0.1/dwc-dp/dwc-dp-profile.json
 *
 * <p>Resources whose {@code name} is in this set are subject to the stricter DwC-DP
 * field-level constraints. Resources with other names are treated as supplementary.
 */
public enum DwcDpTableName {
  AGENT("agent"),
  AGENT_AGENT_ROLE("agent-agent-role"),
  AGENT_IDENTIFIER("agent-identifier"),
  AGENT_MEDIA("agent-media"),
  BIBLIOGRAPHIC_RESOURCE("bibliographic-resource"),
  CHRONOMETRIC_AGE("chronometric-age"),
  CHRONOMETRIC_AGE_AGENT_ROLE("chronometric-age-agent-role"),
  CHRONOMETRIC_AGE_ASSERTION("chronometric-age-assertion"),
  CHRONOMETRIC_AGE_MEDIA("chronometric-age-media"),
  CHRONOMETRIC_AGE_PROTOCOL("chronometric-age-protocol"),
  CHRONOMETRIC_AGE_REFERENCE("chronometric-age-reference"),
  EVENT("event"),
  EVENT_AGENT_ROLE("event-agent-role"),
  EVENT_ASSERTION("event-assertion"),
  EVENT_IDENTIFIER("event-identifier"),
  EVENT_MEDIA("event-media"),
  EVENT_PROTOCOL("event-protocol"),
  EVENT_PROVENANCE("event-provenance"),
  EVENT_REFERENCE("event-reference"),
  GEOLOGICAL_CONTEXT("geological-context"),
  GEOLOGICAL_CONTEXT_MEDIA("geological-context-media"),
  IDENTIFICATION("identification"),
  IDENTIFICATION_AGENT_ROLE("identification-agent-role"),
  IDENTIFICATION_REFERENCE("identification-reference"),
  IDENTIFICATION_TAXON("identification-taxon"),
  MATERIAL("material"),
  MATERIAL_AGENT_ROLE("material-agent-role"),
  MATERIAL_ASSERTION("material-assertion"),
  MATERIAL_GEOLOGICAL_CONTEXT("material-geological-context"),
  MATERIAL_IDENTIFIER("material-identifier"),
  MATERIAL_MEDIA("material-media"),
  MATERIAL_PROTOCOL("material-protocol"),
  MATERIAL_PROVENANCE("material-provenance"),
  MATERIAL_REFERENCE("material-reference"),
  MATERIAL_USAGE_POLICY("material-usage-policy"),
  MEDIA("media"),
  MEDIA_AGENT_ROLE("media-agent-role"),
  MEDIA_ASSERTION("media-assertion"),
  MEDIA_IDENTIFIER("media-identifier"),
  MEDIA_PROVENANCE("media-provenance"),
  MEDIA_USAGE_POLICY("media-usage-policy"),
  MOLECULAR_PROTOCOL("molecular-protocol"),
  MOLECULAR_PROTOCOL_AGENT_ROLE("molecular-protocol-agent-role"),
  MOLECULAR_PROTOCOL_ASSERTION("molecular-protocol-assertion"),
  MOLECULAR_PROTOCOL_REFERENCE("molecular-protocol-reference"),
  NUCLEOTIDE_ANALYSIS("nucleotide-analysis"),
  NUCLEOTIDE_ANALYSIS_ASSERTION("nucleotide-analysis-assertion"),
  NUCLEOTIDE_SEQUENCE("nucleotide-sequence"),
  OCCURRENCE("occurrence"),
  OCCURRENCE_AGENT_ROLE("occurrence-agent-role"),
  OCCURRENCE_ASSERTION("occurrence-assertion"),
  OCCURRENCE_IDENTIFIER("occurrence-identifier"),
  OCCURRENCE_MEDIA("occurrence-media"),
  OCCURRENCE_PROTOCOL("occurrence-protocol"),
  OCCURRENCE_REFERENCE("occurrence-reference"),
  ORGANISM("organism"),
  ORGANISM_ASSERTION("organism-assertion"),
  ORGANISM_IDENTIFIER("organism-identifier"),
  ORGANISM_INTERACTION("organism-interaction"),
  ORGANISM_INTERACTION_AGENT_ROLE("organism-interaction-agent-role"),
  ORGANISM_INTERACTION_ASSERTION("organism-interaction-assertion"),
  ORGANISM_INTERACTION_MEDIA("organism-interaction-media"),
  ORGANISM_INTERACTION_REFERENCE("organism-interaction-reference"),
  ORGANISM_REFERENCE("organism-reference"),
  ORGANISM_RELATIONSHIP("organism-relationship"),
  PROTOCOL("protocol"),
  PROTOCOL_REFERENCE("protocol-reference"),
  PROVENANCE("provenance"),
  RESOURCE_RELATIONSHIP("resource-relationship"),
  SURVEY("survey"),
  SURVEY_AGENT_ROLE("survey-agent-role"),
  SURVEY_ASSERTION("survey-assertion"),
  SURVEY_IDENTIFIER("survey-identifier"),
  SURVEY_PROTOCOL("survey-protocol"),
  SURVEY_REFERENCE("survey-reference"),
  SURVEY_TARGET("survey-target"),
  USAGE_POLICY("usage-policy");

  private final String tableName;

  DwcDpTableName(String tableName) {
    this.tableName = tableName;
  }

  /** The kebab-case name as it appears in the datapackage.json {@code name} field. */
  public String tableName() {
    return tableName;
  }

  /** Pre-built set of all reserved names for O(1) lookup. */
  public static final Set<String> ALL_NAMES =
    Arrays.stream(values())
      .map(DwcDpTableName::tableName)
      .collect(Collectors.toUnmodifiableSet());

  /** Returns true if {@code name} is a reserved DwC-DP table name (case-sensitive). */
  public static boolean isReserved(String name) {
    return ALL_NAMES.contains(name);
  }
}
