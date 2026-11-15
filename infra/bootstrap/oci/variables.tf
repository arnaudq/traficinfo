variable "region" {
  description = "OCI region for the state bucket."
  type        = string
}

variable "compartment_id" {
  description = "Existing TraficInfo compartment OCID."
  type        = string
}

variable "namespace" {
  description = "Existing Object Storage namespace."
  type        = string
}
