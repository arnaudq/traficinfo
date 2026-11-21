variable "region" {
  description = "OCI region containing the existing TraficInfo network."
  type        = string
}

variable "compartment_id" {
  description = "OCID of the existing TraficInfo compartment."
  type        = string
}

variable "subnet_id" {
  description = "OCID of the existing public subnet in the TraficInfo VCN."
  type        = string
}

variable "gateway_name" {
  description = "Display name for the new dev gateway."
  type        = string
}

variable "state_bucket_name" {
  description = "Existing infrastructure state bucket excluded from application functions access."
  type        = string
}

variable "function_objects_policy_id" {
  description = "Existing application functions Object Storage policy to adopt."
  type        = string
}

variable "jawg_access_token" {
  description = "Jawg token supplied through private settings or TF_VAR_jawg_access_token; stored in state."
  type        = string
  sensitive   = true
  nullable    = false
}

variable "jawg_style" {
  description = "Jawg style used by the tile proxy."
  type        = string
  default     = "jawg-light"
}

variable "tile_requests_per_second" {
  description = "Maximum tile requests per second per client IP."
  type        = number
  default     = 50
}

variable "tile_allowed_origins" {
  description = "Allowed browser CORS origins for the public tile proxy."
  type        = list(string)
  default     = ["*"]
}

variable "function_application_id" {
  description = "OCID of the existing Functions application monitored by failure alarms."
  type        = string
}

variable "notification_topic_id" {
  description = "OCID of the existing Notifications topic receiving function alarms."
  type        = string
}
