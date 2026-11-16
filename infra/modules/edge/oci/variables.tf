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

variable "jawg_access_token" {
  description = "Jawg token injected into upstream requests; stored in gateway configuration and state."
  type        = string
  sensitive   = true
  nullable    = false

  validation {
    condition     = length(trimspace(var.jawg_access_token)) > 0
    error_message = "A nonempty Jawg access token is required."
  }
}

variable "jawg_style" {
  description = "Fixed Jawg style ID for the tile backend."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_-]+$", var.jawg_style))
    error_message = "The Jawg style ID must contain only letters, digits, underscores, or hyphens."
  }
}

variable "tile_requests_per_second" {
  description = "Maximum tile requests per second per client IP."
  type        = number

  validation {
    condition     = var.tile_requests_per_second >= 1 && floor(var.tile_requests_per_second) == var.tile_requests_per_second
    error_message = "The tile request rate must be a positive integer."
  }
}

variable "tile_allowed_origins" {
  description = "Origins allowed to read tile responses through browser CORS; not an access-control mechanism."
  type        = list(string)

  validation {
    condition     = length(var.tile_allowed_origins) > 0
    error_message = "At least one tile CORS origin is required."
  }
}
