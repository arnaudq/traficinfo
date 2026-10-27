package org.traficinfo.parsers.nim;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** Manual CSV-to-JSON check, defaulting to ~/Downloads/NIM-truncated.csv. */
public final class NIMParserProgram {
  private static final Logger LOG = LoggerFactory.getLogger(NIMParserProgram.class);

  private NIMParserProgram() {}

  /** Optionally supply an input CSV path and an output JSON path. */
  public static void main(String[] args) throws IOException {
    if (args.length > 2) {
      throw new IllegalArgumentException("Usage: NIMParserProgram [input.csv [output.json]]");
    }
    Path input = args.length > 0 ? Path.of(args[0])
        : Path.of(System.getProperty("user.home"), "Downloads", "NIM-truncated.csv");
    String name = input.getFileName().toString();
    String jsonName = name.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")
        ? name.substring(0, name.length() - 4) + ".json" : name + ".json";
    Path output = args.length > 1 ? Path.of(args[1]) : input.resolveSibling(jsonName);
    if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
      throw new IllegalArgumentException("Input and output paths must differ");
    }

    LOG.info("Reading {}", input);
    List<NIMComptage> counts = new NIMComptageParser().parseComptages(input.toFile(), new Properties());
    try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
      new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(writer, counts);
    }
    LOG.info("Wrote {} camera/year counts to {}", counts.size(), output);
  }
}
