output "gateway_id" {
  description = "OCID of the public API gateway."
  value       = oci_apigateway_gateway.this.id
}

output "gateway_base_url" {
  description = "HTTPS origin for future API deployments on this gateway."
  value       = "https://${oci_apigateway_gateway.this.hostname}"
}
