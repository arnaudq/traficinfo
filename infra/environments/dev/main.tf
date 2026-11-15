module "edge" {
  source = "../../modules/edge/oci"

  compartment_id = var.compartment_id
  subnet_id      = var.subnet_id
  gateway_name   = var.gateway_name

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
