package org.traficinfo.parsers;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.List;

/** Parses accident records without count-specific display names or measurements. */
public interface AccidentParser {
  /** The caller retains ownership of the reader. */
  List<? extends Accident> parseAccidents(Reader reader) throws IOException;

  List<? extends Accident> parseAccidents(File file) throws IOException;
}
