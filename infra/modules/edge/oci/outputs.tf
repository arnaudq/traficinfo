output "gateway_id" {
  description = "OCID of the public API gateway."
  value       = oci_apigateway_gateway.this.id
}

output "gateway_base_url" {
  description = "HTTPS origin for API deployments on this gateway."
  value       = "https://${oci_apigateway_gateway.this.hostname}"
}

output "tile_url_template" {
  description = "Public Leaflet tile URL template."
  value       = "${trimsuffix(oci_apigateway_deployment.tiles.endpoint, "/")}/{z}/{x}/{y}.png"
}
