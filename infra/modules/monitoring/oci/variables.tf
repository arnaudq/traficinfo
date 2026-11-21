variable "compartment_id" {
  description = "Compartment containing the function alarms and metrics."
  type        = string
}

variable "function_application_id" {
  description = "OCID of the existing Functions application to monitor."
  type        = string
}

variable "notification_topic_id" {
  description = "OCID of the existing Notifications topic receiving alarm messages."
  type        = string
}
