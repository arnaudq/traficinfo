resource "oci_apigateway_gateway" "this" {
  compartment_id = var.compartment_id
  display_name   = var.gateway_name
  endpoint_type  = "PUBLIC"
  subnet_id      = var.subnet_id
  ip_mode        = "IPV4"
  freeform_tags  = var.tags
}
