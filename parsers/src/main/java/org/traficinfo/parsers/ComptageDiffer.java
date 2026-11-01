package org.traficinfo.parsers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.traficinfo.parsers.m3m.M3MComptage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/** Compares comptages by central code, year and a direction or source-specific key. */
public final class ComptageDiffer {
  private static final Logger LOG = LoggerFactory.getLogger(ComptageDiffer.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> IGNORED_FIELDS = Set.of("altStreetName");

  enum ChangeType { ADDED, MODIFIED, DELETED }

  private ComptageDiffer() {
  }

  /**
   * A changed record. A null before denotes an addition; a null after a removal.
   * Both values are the original input objects, not copies.
   */
  public record Diff(Comptage before, Comptage after) {
    public Diff {
      if (before == null && after == null) {
        throw new IllegalArgumentException("A diff must contain a before or after comptage");
      }
    }

    /**
     * Comma-separated JSON field names, sorted alphabetically. Added values have
     * a + prefix and removed values a - prefix. Nulls, blank text, empty containers,
     * numeric zero and maximumSpeed's -1 default represent missing values.
     * Boolean false is a value, not a missing field. Returns null when either
     * comptage is absent; field comparisons only apply when both sides exist.
     */
    public String changedFields() {
      if (before == null || after == null) {
        return null;
      }
      List<String> changed = new ArrayList<>();
      fieldChanges().forEach((field, change) -> changed.add(
          (change == ChangeType.ADDED ? "+" : change == ChangeType.DELETED ? "-" : "") + field));
      return String.join(",", changed);
    }

    /** Includes value transitions on whole-record additions and removals. */
    Map<String, ChangeType> fieldChanges() {
      JsonNode previous = MAPPER.valueToTree(before);
      JsonNode current = MAPPER.valueToTree(after);
      Set<String> fields = new TreeSet<>();
      previous.fieldNames().forEachRemaining(fields::add);
      current.fieldNames().forEachRemaining(fields::add);
      Map<String, ChangeType> changed = new LinkedHashMap<>();
      for (String field : fields) {
        if (IGNORED_FIELDS.contains(field)) {
          continue;
        }
        JsonNode oldValue = previous.get(field);
        JsonNode newValue = current.get(field);
        if (Objects.equals(oldValue, newValue)) {
          continue;
        }
        boolean oldMissing = isMissing(field, oldValue);
        boolean newMissing = isMissing(field, newValue);
        if (!oldMissing || !newMissing) {
          changed.put(field, oldMissing ? ChangeType.ADDED : newMissing ? ChangeType.DELETED : ChangeType.MODIFIED);
        }
      }
      return changed;
    }
  }

  private static boolean isMissing(String field, JsonNode value) {
    return value == null || value.isNull()
        || (value.isTextual() && value.asText().isBlank())
        || (value.isContainerNode() && value.size() == 0)
        || (value.isNumber() && value.asDouble() == 0)
        || ("maximumSpeed".equals(field) && value.isNumber() && value.asDouble() == -1);
  }

  private record Key(String centralId, int year, Object discriminator) {
  }

  /**
   * Returns removals and modifications in before-list order, then additions in
   * after-list order. Records with no non-ignored field changes are omitted.
   * The default key combines central code, year and directional coverage.
   * Duplicate keys on each side produce a warning and retain the first record.
   */
  public static List<Diff> diff(List<? extends Comptage> before, List<? extends Comptage> after) {
    return diff(before, after, Comptage::getDirectionType);
  }

  /** Matches MMM by channel; other sources encode identity in central code and year. */
  public static List<Diff> diff(GeoJsonParser.DataSource source,
                                List<? extends Comptage> before, List<? extends Comptage> after) {
    Objects.requireNonNull(source, "Data source is required");
    validateSource(source, before);
    validateSource(source, after);
    return diff(before, after, source == GeoJsonParser.DataSource.M3MV2
        ? count -> ((M3MComptage) count).getCodeCanal() : count -> null);
  }

  private static void validateSource(GeoJsonParser.DataSource source, List<? extends Comptage> records) {
    for (Comptage record : records) {
      if (!source.getDsComptageClass().isInstance(record)) {
        throw new IllegalArgumentException("Expected " + source.getId() + " comptages, got: " + record);
      }
    }
  }

  /** Uses a source-specific discriminator, such as M3MComptage::getCodeCanal. */
  public static <T extends Comptage> List<Diff> diff(List<? extends T> before, List<? extends T> after,
                                                   Function<? super T, ?> discriminator) {
    Map<Key, Comptage> beforeByKey = index(before, "before", discriminator);
    Map<Key, Comptage> afterByKey = index(after, "after", discriminator);
    List<Diff> differences = new ArrayList<>();

    beforeByKey.forEach((key, previous) -> {
      Comptage current = afterByKey.get(key);
      Diff difference = new Diff(previous, current);
      if (current == null || !difference.fieldChanges().isEmpty()) {
        differences.add(difference);
      }
    });
    afterByKey.forEach((key, current) -> {
      if (!beforeByKey.containsKey(key)) {
        differences.add(new Diff(null, current));
      }
    });
    return differences;
  }

  private static <T extends Comptage> Map<Key, Comptage> index(List<? extends T> comptages, String side,
                                                             Function<? super T, ?> discriminator) {
    Map<Key, Comptage> indexed = new LinkedHashMap<>();
    for (T comptage : comptages) {
      Key key = new Key(comptage.getCodeCentrale(), comptage.getAnnee(), discriminator.apply(comptage));
      if (indexed.putIfAbsent(key, comptage) != null) {
        LOG.warn("Duplicate Comptage on {} side for centralid={}, year={} and discriminator={}; keeping the first",
            side, key.centralId(), key.year(), key.discriminator());
      }
    }
    return indexed;
  }
}
