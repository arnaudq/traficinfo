# OCI edge module

Creates one public IPv4 OCI API Gateway in an existing compartment and public
regional subnet, plus a `tiles` deployment at `/tiles`. Provider configuration is
supplied by the calling environment.

Required inputs are `compartment_id`, `subnet_id`, `gateway_name`,
`jawg_access_token`, `jawg_style`, `tile_requests_per_second`, and
`tile_allowed_origins`; `tags` is an optional map of freeform tags. Outputs are
`gateway_id`, `gateway_base_url`, and `tile_url_template`.

The caller owns network prerequisites: Internet routing, stateful TCP 443 ingress,
and egress to upstream services. This module does not manage the existing VCN,
subnet, route table, security lists, or compartment.

## Tile proxy

`GET /tiles/{z}/{x}/{tile}` forwards to
`https://tile.jawg.io/<configured-style>/{z}/{x}/{tile}`. The last segment is the
whole filename, such as `2862.png` or `2862@2x.png`. The Leaflet output uses
`/tiles/{z}/{x}/{y}.png`. This route supports one configured style and a fixed
upstream hostname; it does not expose an arbitrary backend URL or style choice.

Dev defaults to the `jawg-light` style, 50 requests per second per client IP,
and `tile_allowed_origins = ["*"]`. Override `jawg_style`,
`tile_requests_per_second`, or `tile_allowed_origins` in the private dev variable
file as needed.

Set `jawg_access_token` in `~/.config/traficinfo/dev.tfvars` or supply it through
`TF_VAR_jawg_access_token`. It is marked sensitive in both the root and module.

The request policy overwrites `access-token` with the sensitive input and filters
out all other query parameters. No language or worldview override is forwarded.
The token is stored in OCI deployment configuration and OpenTofu state; it is
not part of the public URL output. `sensitive` redacts normal CLI output but does
not encrypt the token. Do not enable request tracing or publish state or plans.
Retained state versions also contain the token.

Requests are public, with no authentication or referrer validation. CORS allows
the configured origins for GET without credentials. CORS does not restrict
ordinary image embedding or non-browser clients. The per-client-IP rate limit
controls request rate, not total Jawg consumption. No response cache or Object
Storage route is provisioned.

After applying, run
`tofu -chdir=infra/environments/dev output -raw tile_url_template` from the
repository root to print the public URL template. The webapp's base map is
configured separately; adding the deployment does not switch its tile source.
When adopting Jawg in Leaflet, update the provider attribution as well as the URL.
