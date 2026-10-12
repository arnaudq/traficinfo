package org.traficinfo.parser;

import org.junit.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class ParserBaseTest {

  protected Reader getReaderFromResource(String filename) throws IOException {
    InputStream inputStream = this.getClass().getResourceAsStream("/" + filename);
    if (inputStream == null) {
      throw new IOException("failed to load " + filename);
    }
    return new InputStreamReader(inputStream, StandardCharsets.UTF_8);
  }
}
