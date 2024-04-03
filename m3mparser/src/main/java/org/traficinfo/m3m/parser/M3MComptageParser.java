package org.traficinfo.m3m.parser;

import com.opencsv.bean.CsvToBeanBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class M3MComptageParser implements ComptageParser {

  private static final Logger LOG = LoggerFactory.getLogger(M3MComptageParser.class);

  private static final Map<String, Comptage.Type> TYPE_MAP = new HashMap<>();
  static {
    TYPE_MAP.put("compteur feux", Comptage.Type.VEHICULES);
    TYPE_MAP.put("compteur permanent", Comptage.Type.VEHICULES);
    TYPE_MAP.put("compteur tournant", Comptage.Type.VEHICULES);
    TYPE_MAP.put("compteur ponctuel", Comptage.Type.VEHICULES);
    TYPE_MAP.put("ne pas tenir compte", Comptage.Type.VEHICULES);

    TYPE_MAP.put("compteur permanent vélo", Comptage.Type.BICYCLES);
    TYPE_MAP.put("compteur ponctuel vélo", Comptage.Type.BICYCLES);
  }

  @Override
  public List<? extends Comptage> parseComptages(Reader csvReader) throws IOException {
    return parseComptages(csvReader, new Properties());
  }
  @Override
  public List<? extends Comptage> parseComptages(Reader csvReader, Properties idToDisplayNames)
          throws IOException {
    final List<M3MComptage> comptages =
            new CsvToBeanBuilder<M3MComptage>(csvReader).withType(M3MComptage.class).build().parse();

    setComptageVoieTypeAndDirection(comptages, idToDisplayNames);

    LOG.info("Nb comptages: {}", comptages.size());
    return comptages;
  }

  @Override
  public List<? extends Comptage> parseComptages(File csvFile, Properties idToDisplayNames)
    throws IOException {

    try (Reader reader = new FileReader(csvFile)) {
      return parseComptages(reader, idToDisplayNames);
    }
  }

  static void setComptageVoieTypeAndDirection(final List<M3MComptage> comptages,
                                              Properties idToVoiesProps) {
    comptages.forEach(mmmComptage -> {
      String streetName = mmmComptage.getNomVoie();
      String cityName = mmmComptage.getCityName();
      String displayName = null;
      if (streetName != null && !streetName.isEmpty() && cityName != null && !cityName.isEmpty()) {
        displayName = String.format("%s - %s", streetName, cityName);
      }
      String normalizedDisplayName = idToVoiesProps.getProperty(mmmComptage.getCodeCentrale());
      if (normalizedDisplayName != null ) {
        if (displayName != null && !displayName.equals(normalizedDisplayName)) {
          LOG.warn("Mismatch in displayName for {}: {} versus {}",
            mmmComptage.getCodeCentrale(), displayName, normalizedDisplayName);
        }
      } else {
        normalizedDisplayName = displayName;
      }
      mmmComptage.setDisplayName(normalizedDisplayName);
      mmmComptage.setBidirectionnelle("Z".equals(mmmComptage.getCodeCanal()));
      Comptage.Type type = TYPE_MAP.get(mmmComptage.getTypeCompteur());
      if (type == null) {
        LOG.warn("UNKNOWN TYPE FOR {}\n", mmmComptage);
        type = Comptage.Type.VEHICULES;
      }
      mmmComptage.setType(type);
    });
  }
}
