variable "compartment_id" {
  description = "Compartment OCID for the application functions Object Storage policy."
  type        = string
}

variable "state_bucket_name" {
  description = "Infrastructure state bucket excluded from application functions access."
  type        = string
}
