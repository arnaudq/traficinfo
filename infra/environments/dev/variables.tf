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
