package org.traficinfo.parsers.mrn;

import com.opencsv.bean.CsvToBeanBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.ComptageParser;

import java.io.File;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class MRNComptageParser implements ComptageParser {
  public static final String COUNTER_PREFIX = "MRN_";
  private static final Logger LOG = LoggerFactory.getLogger(MRNComptageParser.class);

  @Override
  public List<MRNComptage> parseComptages(Reader reader) throws IOException {
    return parseComptages(reader, new Properties());
  }

  @Override
  public List<MRNComptage> parseComptages(Reader csvReader, Properties displayNames)
      throws IOException {
    PushbackReader reader = new PushbackReader(csvReader, 1);
    int first = reader.read();
    if (first != -1 && first != '\uFEFF') {
      reader.unread(first);
    }
    List<MRNComptage> sourceRows;
    try {
      sourceRows = new CsvToBeanBuilder<MRNComptage>(reader)
          .withSeparator(';').withType(MRNComptage.class).build().parse();
    } catch (RuntimeException e) {
      throw new IOException("Invalid MRN CSV", e);
    }
    List<MRNComptage> counts = new ArrayList<>();
    int skipped = 0;
    for (MRNComptage count : sourceRows) {
      Comptage.Type type = typeFor(count.getCampaignType(), count.getCampaignDetails());
      if (type == null) {
        skipped++;
        continue;
      }
      String id = trim(count.getSiteId());
      if (id == null) {
        throw new IOException("Missing Id_SIG in MRN CSV");
      }
      if (count.getAnnee() <= 0 || count.getAnnee() > Year.now().getValue()) {
        throw new IOException("Invalid Année for MRN counter " + id);
      }
      count.setSiteId(id);
      count.setCodeCentrale(COUNTER_PREFIX + id);
      count.setType(type);
      count.setStreetName(trim(count.getStreetName()));
      count.setCityName(trim(count.getCityName()));
      String direction = trim(count.getDirectionCount());
      count.setDirectionCount(direction);
      if (direction != null && !List.of("2 sens", "1 sens", "Sens unique").contains(direction)) {
        LOG.warn("Skipping MRN counter {} with unknown direction {}", id, direction);
        skipped++;
        continue;
      }
      applyCoordinates(count);
      String street = count.getStreetName();
      String city = count.getCityName();
      String name = street == null ? city : city == null ? street : street + " - " + city;
      count.setDisplayName(displayNames.getProperty(count.getCodeCentrale(),
          name == null ? count.getCodeCentrale() : name));
      count.setMoyenneHeureDePointeMatin(peak(count.getTvHpm(), count.getTvHpmHour()));
      count.setMoyenneHeureDePointeSoir(peak(count.getTvHps(), count.getTvHpsHour()));
      counts.add(count);
    }
    LOG.info("Nb MRN comptages: {}; skipped unsupported records: {}", counts.size(), skipped);
    return counts;
  }

  private static Comptage.Type typeFor(String campaign, String details) {
    campaign = trim(campaign);
    if (campaign == null) {
      return null;
    }
    return switch (campaign) {
      case "Comptage permanent en section", "Comptage ponctuel en section",
          "Comptage ponctuel directionnel sur carrefour" -> Comptage.Type.VEHICULES;
      case "Comptage permanent de vélos" -> Comptage.Type.BICYCLES;
      case "Comptage ponctuel de modes doux" -> {
        String population = trim(details);
        yield "Vélos".equals(population) ? Comptage.Type.BICYCLES
            : "Piétons / Vélos".equals(population) ? Comptage.Type.PIETONS_VELOS : null;
      }
      default -> null;
    };
  }

  private static String peak(Integer volume, String hour) {
    if (volume == null || volume <= 0) {
      return null;
    }
    String interval = trim(hour);
    if (interval == null) {
      return volume.toString();
    }
    if (interval.startsWith("[") && interval.endsWith("]")) {
      interval = interval.substring(1, interval.length() - 1);
    }
    return volume + " (" + interval + ")";
  }

  private static void applyCoordinates(MRNComptage count) throws IOException {
    if (count.getLongitude() == null || count.getLatitude() == null) {
      String geopoint = trim(count.getGeopoint());
      if (geopoint != null) {
        String[] coordinates = geopoint.split(",", -1);
        try {
          if (coordinates.length != 2) {
            throw new NumberFormatException("Expected latitude, longitude");
          }
          double latitude = Double.parseDouble(coordinates[0].trim());
          double longitude = Double.parseDouble(coordinates[1].trim());
          // Use the complete fallback pair, rather than mixing coordinate sources.
          count.setLatitude(latitude);
          count.setLongitude(longitude);
        } catch (NumberFormatException e) {
          throw new IOException("Invalid geopoint for MRN counter " + count.getSiteId(), e);
        }
      }
    }
    Double lon = count.getLongitude();
    Double lat = count.getLatitude();
    if ((lon != null && (!Double.isFinite(lon) || lon < -180 || lon > 180))
        || (lat != null && (!Double.isFinite(lat) || lat < -90 || lat > 90))) {
      throw new IOException("Invalid coordinates for MRN counter " + count.getSiteId());
    }
  }

  private static String trim(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  @Override
  public List<MRNComptage> parseComptages(File file, Properties displayNames) throws IOException {
    try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
      return parseComptages(reader, displayNames);
    }
  }
}
