resource "oci_apigateway_deployment" "tiles" {
  compartment_id = var.compartment_id
  gateway_id     = oci_apigateway_gateway.this.id
  display_name   = "tiles"
  path_prefix    = "/tiles"
  freeform_tags  = var.tags

  specification {
    request_policies {
      rate_limiting {
        rate_key                    = "CLIENT_IP"
        rate_in_requests_per_second = var.tile_requests_per_second
      }

      cors {
        allowed_origins              = var.tile_allowed_origins
        allowed_methods              = ["GET"]
        is_allow_credentials_enabled = false
      }
    }

    routes {
      # OCI parameters replace a whole segment; "tile" includes .png or @2x.png.
      path    = "/{z}/{x}/{tile}"
      methods = ["GET"]

      backend {
        type = "HTTP_BACKEND"
        url  = "https://tile.jawg.io/${var.jawg_style}/$${request.path[z]}/$${request.path[x]}/$${request.path[tile]}"
      }

      request_policies {
        query_parameter_transformations {
          set_query_parameters {
            items {
              name      = "access-token"
              values    = [var.jawg_access_token]
              if_exists = "OVERWRITE"
            }
          }

          # Filtering runs after transformations, so retain the injected token.
          filter_query_parameters {
            type = "ALLOW"

            items {
              name = "access-token"
            }
          }
        }
      }
    }
  }
}
