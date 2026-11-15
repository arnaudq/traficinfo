variable "compartment_id" {
  description = "OCID of the existing compartment for the gateway."
  type        = string
}

variable "subnet_id" {
  description = "OCID of an existing public regional subnet with HTTPS ingress and Internet routing."
  type        = string
}

variable "gateway_name" {
  description = "Gateway display name; OCI does not require it to be unique."
  type        = string
}

variable "tags" {
  description = "Freeform tags applied to the gateway."
  type        = map(string)
  default     = {}
}
