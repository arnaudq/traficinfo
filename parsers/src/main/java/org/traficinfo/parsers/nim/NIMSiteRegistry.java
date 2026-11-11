/*
 * This file is part of TraficInfo.
 * Copyright (C) 2026 Arnaud Quillaud
 *
 * TraficInfo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * TraficInfo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with TraficInfo. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.traficinfo.parsers.nim;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.geojson.LngLatAlt;
import org.traficinfo.parsers.GeoJsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Reviewed, persistent camera/site identities; reference coordinates never follow incoming rows. */
public final class NIMSiteRegistry {
  public static final double TOLERANCE_METRES = 50.0;
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

  public record Site(@JsonProperty("codeCentrale") String codeCentrale,
                     @JsonProperty("cameraId") String cameraId,
                     @JsonProperty("latitude") double latitude,
                     @JsonProperty("longitude") double longitude,
                     @JsonProperty("cameraMoved") boolean cameraMoved,
                     @JsonProperty("firstSeen") String firstSeen,
                     @JsonProperty("lastSeen") String lastSeen,
                     @JsonProperty("positions") List<String> positions,
                     @JsonProperty("inseeCodes") List<String> inseeCodes,
                     @JsonProperty("communes") List<String> communes) {
    @JsonCreator public Site {}
  }

  private final List<Site> sites;
  private final Map<String, List<Site>> byCamera = new HashMap<>();

  public NIMSiteRegistry(List<Site> sites) throws IOException {
    if (sites == null) throw new IOException("NIM site registry must be a JSON array");
    var ids = new HashSet<String>();
    for (Site site : sites) {
      if (site == null || site.cameraId() == null || !site.cameraId().matches("[0-9]+")
          || site.codeCentrale() == null
          || !site.codeCentrale().matches("NIM_" + site.cameraId() + "_S(0[1-9]|[1-9][0-9]+)")
          || !ids.add(site.codeCentrale()) || !Double.isFinite(site.latitude())
          || !Double.isFinite(site.longitude()) || Math.abs(site.latitude()) > 90 || Math.abs(site.longitude()) > 180) {
        throw new IOException("Invalid or duplicate NIM site: " + site);
      }
      try {
        if (LocalDate.parse(site.firstSeen()).isAfter(LocalDate.parse(site.lastSeen()))) {
          throw new IllegalArgumentException("firstSeen is after lastSeen");
        }
      } catch (RuntimeException invalid) {
        throw new IOException("Invalid NIM site dates: " + site.codeCentrale(), invalid);
      }
      if (site.positions() == null || site.inseeCodes() == null || site.communes() == null
          || site.positions().stream().anyMatch(java.util.Objects::isNull)
          || site.inseeCodes().stream().anyMatch(java.util.Objects::isNull)
          || site.communes().stream().anyMatch(java.util.Objects::isNull)) {
        throw new IOException("Missing NIM site labels: " + site.codeCentrale());
      }
      byCamera.computeIfAbsent(site.cameraId(), ignored -> new ArrayList<>()).add(site);
    }
    for (List<Site> cameraSites : byCamera.values()) {
      for (int i = 0; i < cameraSites.size(); i++) {
        for (int j = i + 1; j < cameraSites.size(); j++) {
          Site a = cameraSites.get(i);
          Site b = cameraSites.get(j);
          if (distance(a, b.latitude(), b.longitude()) <= TOLERANCE_METRES) {
            throw new IOException("Overlapping NIM reference sites: " + a.codeCentrale() + ", " + b.codeCentrale());
          }
        }
      }
    }
    this.sites = sites.stream().map(site -> new Site(site.codeCentrale(), site.cameraId(), site.latitude(),
        site.longitude(), byCamera.get(site.cameraId()).size() > 1, site.firstSeen(), site.lastSeen(),
        List.copyOf(site.positions()), List.copyOf(site.inseeCodes()), List.copyOf(site.communes())))
        .sorted(Comparator.comparing(Site::codeCentrale)).toList();
    byCamera.clear();
    for (Site site : this.sites) byCamera.computeIfAbsent(site.cameraId(), ignored -> new ArrayList<>()).add(site);
  }

  public static NIMSiteRegistry bundled() throws IOException {
    try (InputStream input = NIMSiteRegistry.class.getResourceAsStream("/nim-sites.json")) {
      if (input == null) throw new IOException("Missing bundled nim-sites.json; run NIMSiteRegistryUpdater and rebuild");
      return read(input);
    }
  }

  public static NIMSiteRegistry read(Path path) throws IOException {
    try (InputStream input = Files.newInputStream(path)) { return read(input); }
  }

  private static NIMSiteRegistry read(InputStream input) throws IOException {
    return new NIMSiteRegistry(JSON.readValue(input, new TypeReference<List<Site>>() {}));
  }

  public List<Site> sites() { return sites; }

  /** Returns null for a new camera/site; ambiguous matches fail rather than silently choosing a site. */
  public Site find(String camera, Double latitude, Double longitude) throws IOException {
    if (latitude == null || longitude == null) {
      List<Site> candidates = byCamera.getOrDefault(camera, List.of());
      if (candidates.size() > 1) {
        throw new IOException("Ambiguous NIM site: missing coordinates for camera " + camera
            + " with " + candidates.size() + " registered sites");
      }
      return candidates.isEmpty() ? null : candidates.get(0);
    }
    Site match = null;
    for (Site site : byCamera.getOrDefault(camera, List.of())) {
      if (distance(site, latitude, longitude) <= TOLERANCE_METRES) {
        if (match != null) throw new IOException("Ambiguous NIM site for camera " + camera + " at "
            + latitude + ", " + longitude + ": " + match.codeCentrale() + ", " + site.codeCentrale());
        match = site;
      }
    }
    return match;
  }

  static double distance(Site site, double latitude, double longitude) {
    return GeoJsonUtils.distanceMeters(new LngLatAlt(site.longitude(), site.latitude()), new LngLatAlt(longitude, latitude));
  }

  static byte[] serialize(NIMSiteRegistry registry) throws IOException {
    byte[] json = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(registry.sites());
    byte[] terminated = java.util.Arrays.copyOf(json, json.length + 1);
    terminated[json.length] = '\n';
    return terminated;
  }
}
