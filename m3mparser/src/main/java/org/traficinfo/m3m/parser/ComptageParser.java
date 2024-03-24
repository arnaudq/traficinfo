package org.traficinfo.m3m.parser;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Properties;

/*
 * Generic data parser interface.
 */
public interface ComptageParser {

  /**
   * Get comptages from Reader.
   * @param dataReader data file as reader.
   * @return list of comptages.
   * @throws IOException if we failed to parse the file.
   */
  List<? extends Comptage> parseComptages(Reader dataReader)
          throws IOException;

  /**
   * Get comptages from Reader.
   * @param dataReader data file as reader.
   * @param idToDisplayNames id to displayname if displayname cant be constructed from data.
   * @return list of comptages.
   * @throws IOException if we failed to parse the file.
   */
  List<? extends Comptage> parseComptages(Reader dataReader, Properties idToDisplayNames)
          throws IOException;

  /**
   * Get comptages from file.
   * @param dataFile data file.
   * @param idToDisplayNames id to displayname if displayname cant be constructed from data.
   * @return list of comptages.
   * @throws IOException if we failed to parse the file.
   */
  List<? extends Comptage> parseComptages(File dataFile, Properties idToDisplayNames)
    throws IOException;
}
