output "gateway_id" {
  description = "OCID of the new dev gateway."
  value       = module.edge.gateway_id
}

output "gateway_base_url" {
  description = "HTTPS origin for dev routes."
  value       = module.edge.gateway_base_url
}

output "tile_url_template" {
  description = "Public tile URL template for Leaflet."
  value       = module.edge.tile_url_template
}
