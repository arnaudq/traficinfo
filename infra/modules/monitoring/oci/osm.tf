locals {
  osm_alarms = {
    NEEDS_REVIEW = {
      name        = "TraficInfo-OSMWayMappingsNeedReview"
      title       = "TraficInfo OSM mappings need review"
      summary     = "OSM mappings require manual review. See the datasource dimensions and metric values."
      guidance    = "Inspect OSMWayMapping_NEEDS_REVIEW.json in the OSM bucket. Review candidates and upload any approved assignments for the next hourly OSM refresh."
      count_label = "review"
    }
    NOT_FOUND = {
      name        = "TraficInfo-OSMWayMappingsNotFound"
      title       = "TraficInfo OSM road ways not found"
      summary     = "OSM reverse lookups found no suitable road way. See the datasource dimensions and metric values."
      guidance    = "Inspect each affected datasource's <datasourceId>_OSMWayMapping.json in the OSM bucket and its stored reverse lookup responses. Check source coordinates and road references, and correct mappings where appropriate."
      count_label = "NOT_FOUND"
    }
  }
}

resource "oci_monitoring_alarm" "osm" {
  for_each = local.osm_alarms

  compartment_id                                = var.compartment_id
  metric_compartment_id                         = var.compartment_id
  metric_compartment_id_in_subtree              = false
  destinations                                  = [var.notification_topic_id]
  display_name                                  = each.value.name
  namespace                                     = "traficinfo"
  query                                         = "OSMWayMappings[1h]{status = \"${each.key}\"}.groupBy(datasourceId).max() > 0"
  severity                                      = "WARNING"
  is_enabled                                    = true
  pending_duration                              = "PT1M"
  repeat_notification_duration                  = "PT24H"
  message_format                                = "ONS_OPTIMIZED"
  is_notifications_per_metric_dimension_enabled = false
  notification_title                            = "${each.value.title} - {{status}}"
  alarm_summary                                 = each.value.summary
  body                                          = <<-EOT
    ${each.value.guidance}

    Dimensions identify affected datasources; metric values show their ${each.value.count_label} counts. Values are the maximum within the last hour.

    Daily reminders apply while continuously firing. Recovery, missing metrics or a new firing episode can cause additional notifications.

    View alarm: {{alarmUrl}}
  EOT
}
