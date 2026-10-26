package org.traficinfo.parsers.mrn;

import com.opencsv.bean.CsvBindByName;
import org.geojson.Point;
import org.traficinfo.parsers.Comptage;

/** A Rouen campaign record. Nullable source measures distinguish blanks from zero. */
public class MRNComptage implements Comptage {

  @CsvBindByName(column = "Type de Campagne")
  private String campaignType;

  @CsvBindByName(column = "Détails de la campagne")
  private String campaignDetails;

  @CsvBindByName(column = "Gestionnaire du réseau")
  private String networkManager;

  @CsvBindByName(column = "Id_SIG")
  private String siteId;

  @CsvBindByName(column = "Année")
  private int annee;

  @CsvBindByName(column = "Commune (Code INSEE)")
  private String communeInsee;

  @CsvBindByName(column = "Commune")
  private String cityName;

  @CsvBindByName(column = "Rue")
  private String streetName;

  @CsvBindByName(column = "Nombre de Sens")
  private String directionCount;

  @CsvBindByName(column = "Orientation du tronçon")
  private String orientation;

  @CsvBindByName(column = "TV_TMJA")
  private Integer tvTmja;

  @CsvBindByName(column = "%PL_TMJA")
  private Integer plPctTmja;

  @CsvBindByName(column = "Vélo_TMJA_PCT_EVO")
  private Integer tvTmjaPctEvo;

  @CsvBindByName(column = "TV_TMJO")
  private Integer tvTmjo;

  @CsvBindByName(column = "%PL_TMJO")
  private Integer plPctTmjo;

  @CsvBindByName(column = "Vélo_TMJO_PCT_EVO")
  private Integer tvTmjoPctEvo;

  @CsvBindByName(column = "TV_HPM")
  private Integer tvHpm;

  @CsvBindByName(column = "TV_HPM (heure)")
  private String tvHpmHour;

  @CsvBindByName(column = "TV_HPS")
  private Integer tvHps;

  @CsvBindByName(column = "TV_HPS (heure)")
  private String tvHpsHour;

  @CsvBindByName(column = "UVP_TMJA")
  private Integer uvpTmja;

  @CsvBindByName(column = "UVP_TMJO")
  private Integer uvpTmjo;

  @CsvBindByName(column = "UVP_HPM")
  private Integer uvpHpm;

  @CsvBindByName(column = "UVP_HPM (heure)")
  private String uvpHpmHour;

  @CsvBindByName(column = "UVP_HPS")
  private Integer uvpHps;

  @CsvBindByName(column = "UVP_HPS (heure)")
  private String uvpHpsHour;

  @CsvBindByName(column = "X_84")
  private Double longitude;

  @CsvBindByName(column = "Y_84")
  private Double latitude;

  @CsvBindByName(column = "geopoint")
  private String geopoint;

  private Long osmWayId;

  private String codeCentrale;

  private String displayName;


  private String moyenneHeureDePointeMatin;

  private String moyenneHeureDePointeSoir;

  private int v85Speed;

  private int meanSpeed;

  private int maximumSpeed = -1;

  private Type type;

  public String getCampaignType() {
    return campaignType;
  }

  public void setCampaignType(String campaignType) {
    this.campaignType = campaignType;
  }

  public String getCampaignDetails() {
    return campaignDetails;
  }

  public void setCampaignDetails(String campaignDetails) {
    this.campaignDetails = campaignDetails;
  }

  public String getNetworkManager() {
    return networkManager;
  }

  public void setNetworkManager(String networkManager) {
    this.networkManager = networkManager;
  }

  public String getSiteId() {
    return siteId;
  }

  public void setSiteId(String siteId) {
    this.siteId = siteId;
  }

  public int getAnnee() {
    return annee;
  }

  public void setAnnee(int annee) {
    this.annee = annee;
  }

  public String getCommuneInsee() {
    return communeInsee;
  }

  public void setCommuneInsee(String communeInsee) {
    this.communeInsee = communeInsee;
  }

  public String getCityName() {
    return cityName;
  }

  public void setCityName(String cityName) {
    this.cityName = cityName;
  }

  public String getStreetName() {
    return streetName;
  }

  public void setStreetName(String streetName) {
    this.streetName = streetName;
  }

  public String getDirectionCount() {
    return directionCount;
  }

  public void setDirectionCount(String directionCount) {
    this.directionCount = directionCount;
  }

  public String getOrientation() {
    return orientation;
  }

  public void setOrientation(String orientation) {
    this.orientation = orientation;
  }

  public Integer getTvTmja() {
    return tvTmja;
  }

  public void setTvTmja(Integer tvTmja) {
    this.tvTmja = tvTmja;
  }

  public Integer getPlPctTmja() {
    return plPctTmja;
  }

  public void setPlPctTmja(Integer plPctTmja) {
    this.plPctTmja = plPctTmja;
  }

  public Integer getTvTmjaPctEvo() {
    return tvTmjaPctEvo;
  }

  public void setTvTmjaPctEvo(Integer tvTmjaPctEvo) {
    this.tvTmjaPctEvo = tvTmjaPctEvo;
  }

  public Integer getTvTmjo() {
    return tvTmjo;
  }

  public void setTvTmjo(Integer tvTmjo) {
    this.tvTmjo = tvTmjo;
  }

  public Integer getPlPctTmjo() {
    return plPctTmjo;
  }

  public void setPlPctTmjo(Integer plPctTmjo) {
    this.plPctTmjo = plPctTmjo;
  }

  public Integer getTvTmjoPctEvo() {
    return tvTmjoPctEvo;
  }

  public void setTvTmjoPctEvo(Integer tvTmjoPctEvo) {
    this.tvTmjoPctEvo = tvTmjoPctEvo;
  }

  public Integer getTvHpm() {
    return tvHpm;
  }

  public void setTvHpm(Integer tvHpm) {
    this.tvHpm = tvHpm;
  }

  public String getTvHpmHour() {
    return tvHpmHour;
  }

  public void setTvHpmHour(String tvHpmHour) {
    this.tvHpmHour = tvHpmHour;
  }

  public Integer getTvHps() {
    return tvHps;
  }

  public void setTvHps(Integer tvHps) {
    this.tvHps = tvHps;
  }

  public String getTvHpsHour() {
    return tvHpsHour;
  }

  public void setTvHpsHour(String tvHpsHour) {
    this.tvHpsHour = tvHpsHour;
  }

  public Integer getUvpTmja() {
    return uvpTmja;
  }

  public void setUvpTmja(Integer uvpTmja) {
    this.uvpTmja = uvpTmja;
  }

  public Integer getUvpTmjo() {
    return uvpTmjo;
  }

  public void setUvpTmjo(Integer uvpTmjo) {
    this.uvpTmjo = uvpTmjo;
  }

  public Integer getUvpHpm() {
    return uvpHpm;
  }

  public void setUvpHpm(Integer uvpHpm) {
    this.uvpHpm = uvpHpm;
  }

  public String getUvpHpmHour() {
    return uvpHpmHour;
  }

  public void setUvpHpmHour(String uvpHpmHour) {
    this.uvpHpmHour = uvpHpmHour;
  }

  public Integer getUvpHps() {
    return uvpHps;
  }

  public void setUvpHps(Integer uvpHps) {
    this.uvpHps = uvpHps;
  }

  public String getUvpHpsHour() {
    return uvpHpsHour;
  }

  public void setUvpHpsHour(String uvpHpsHour) {
    this.uvpHpsHour = uvpHpsHour;
  }

  public Double getLongitude() {
    return longitude;
  }

  public void setLongitude(Double longitude) {
    this.longitude = longitude;
  }

  public Double getLatitude() {
    return latitude;
  }

  public void setLatitude(Double latitude) {
    this.latitude = latitude;
  }

  public String getGeopoint() {
    return geopoint;
  }

  public void setGeopoint(String geopoint) {
    this.geopoint = geopoint;
  }

  public Long getOsmWayId() {
    return osmWayId;
  }

  public void setOsmWayId(Long osmWayId) {
    this.osmWayId = osmWayId;
  }

  public String getCodeCentrale() {
    return codeCentrale;
  }

  public void setCodeCentrale(String codeCentrale) {
    this.codeCentrale = codeCentrale;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getMoyenneHeureDePointeMatin() {
    return moyenneHeureDePointeMatin;
  }

  public void setMoyenneHeureDePointeMatin(String moyenneHeureDePointeMatin) {
    this.moyenneHeureDePointeMatin = moyenneHeureDePointeMatin;
  }

  public String getMoyenneHeureDePointeSoir() {
    return moyenneHeureDePointeSoir;
  }

  public void setMoyenneHeureDePointeSoir(String moyenneHeureDePointeSoir) {
    this.moyenneHeureDePointeSoir = moyenneHeureDePointeSoir;
  }

  public int getV85Speed() {
    return v85Speed;
  }

  public void setV85Speed(int v85Speed) {
    this.v85Speed = v85Speed;
  }

  public int getMeanSpeed() {
    return meanSpeed;
  }

  public void setMeanSpeed(int meanSpeed) {
    this.meanSpeed = meanSpeed;
  }

  public int getMaximumSpeed() {
    return maximumSpeed;
  }

  public void setMaximumSpeed(int maximumSpeed) {
    this.maximumSpeed = maximumSpeed;
  }

  public Type getType() {
    return type;
  }

  public void setType(Type type) {
    this.type = type;
  }

  @Override
  public DirectionType getDirectionType() {
    if (directionCount == null) {
      return DirectionType.UNKNOWN;
    }
    return switch (directionCount.trim()) {
      case "2 sens" -> DirectionType.BIDIRECTIONAL;
      case "1 sens", "Sens unique" -> DirectionType.UNIDIRECTIONAL;
      default -> DirectionType.UNKNOWN;
    };
  }


  @Override
  public Point getGeometry() {
    return longitude == null || latitude == null ? null : new Point(longitude, latitude);
  }

  @Override
  public int getMoyenneJourAnnuelle() {
    return tvTmja == null ? 0 : tvTmja;
  }

  @Override
  public void setMoyenneJourAnnuelle(int value) {
    tvTmja = value;
  }
}
