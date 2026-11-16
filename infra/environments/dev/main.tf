module "edge" {
  source = "../../modules/edge/oci"

  compartment_id = var.compartment_id
  subnet_id      = var.subnet_id
  gateway_name   = var.gateway_name

  jawg_access_token        = var.jawg_access_token
  jawg_style               = var.jawg_style
  tile_requests_per_second = var.tile_requests_per_second
  tile_allowed_origins     = var.tile_allowed_origins

  tags = {
    project     = "traficinfo"
    environment = "dev"
  }
}

module "policies" {
  source = "../../modules/policies/oci"

  compartment_id    = var.compartment_id
  state_bucket_name = var.state_bucket_name
}
