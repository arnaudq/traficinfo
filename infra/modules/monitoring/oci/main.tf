locals {
  alarms = {
    errors = {
      name      = "TraficInfo-FunctionErrors"
      namespace = "oci_faas"
      query     = "FunctionResponseCount[1m]{applicationId = \"${var.function_application_id}\", responseType = \"Error\", ErrorCode != \"503\"}.groupBy(resourceId, resourceDisplayName, ErrorCode, ErrorMessage).sum() > 0"
      severity  = "CRITICAL"
      title     = "TraficInfo function error"
      summary   = "Function {{dimensions.resourceDisplayName}}: {{status}}. Error {{dimensions.ErrorCode}} - {{dimensions.ErrorMessage}}."
      details   = "Error: {{dimensions.ErrorCode}} - {{dimensions.ErrorMessage}}"
      guidance  = "A non-503 function error was detected. Check the error details and invocation logs."
    }
    availability = {
      name      = "TraficInfo-FunctionAvailability"
      namespace = "oci_faas"
      query     = "FunctionResponseCount[15m]{applicationId = \"${var.function_application_id}\", responseType = \"Error\", ErrorCode = \"503\"}.groupBy(resourceId, resourceDisplayName, ErrorCode, ErrorMessage).sum() >= 3"
      severity  = "WARNING"
      title     = "TraficInfo function availability"
      summary   = "Function {{dimensions.resourceDisplayName}}: {{status}}. Error {{dimensions.ErrorCode}} - {{dimensions.ErrorMessage}}."
      details   = "Error: {{dimensions.ErrorCode}} - {{dimensions.ErrorMessage}}"
      guidance  = "At least three 503 responses were recorded within 15 minutes. OCI could not serve these invocation attempts. Check for a subsequent successful run. This threshold does not mean retries are exhausted."
    }
    application = {
      name      = "TraficInfo-ApplicationFailures"
      namespace = "traficinfo"
      query     = "FunctionFailures[1m]{applicationId = \"${var.function_application_id}\"}.groupBy(resourceId, resourceDisplayName, datasourceId).sum() > 0"
      severity  = "CRITICAL"
      title     = "TraficInfo application failure"
      summary   = "Function {{dimensions.resourceDisplayName}}, datasource {{dimensions.datasourceId}}: {{status}}."
      details   = "Datasource: {{dimensions.datasourceId}}"
      guidance  = "The handler caught an application exception and reported it without failing the OCI invocation. Check invocation logs for exception details and any outputs already written."
    }
  }
}

resource "oci_monitoring_alarm" "function" {
  for_each = local.alarms

  compartment_id                                = var.compartment_id
  metric_compartment_id                         = var.compartment_id
  metric_compartment_id_in_subtree              = false
  destinations                                  = [var.notification_topic_id]
  display_name                                  = each.value.name
  namespace                                     = each.value.namespace
  query                                         = each.value.query
  severity                                      = each.value.severity
  is_enabled                                    = true
  pending_duration                              = "PT1M"
  message_format                                = "ONS_OPTIMIZED"
  is_notifications_per_metric_dimension_enabled = true
  notification_title                            = "${each.value.title} - {{status}}"
  alarm_summary                                 = each.value.summary
  body                                          = <<-EOT
    Function: {{dimensions.resourceDisplayName}}
    State: {{status}}
    Time: {{timestamp}}
    ${each.value.details}

    When FIRING: ${each.value.guidance}

    OK means the error condition has cleared; verify a successful run to confirm recovery.

    View alarm: {{alarmUrl}}
    Function ID: {{dimensions.resourceId}}
  EOT
}
