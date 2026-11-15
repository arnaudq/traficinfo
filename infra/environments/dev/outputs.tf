output "gateway_id" {
  description = "OCID of the new dev gateway."
  value       = module.edge.gateway_id
}

output "gateway_base_url" {
  description = "HTTPS origin for future dev routes."
  value       = module.edge.gateway_base_url
}
