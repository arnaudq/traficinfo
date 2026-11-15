# OCI edge module

Creates one public IPv4 OCI API Gateway in an existing compartment and public
regional subnet. Provider configuration is supplied by the calling environment.

Required inputs are `compartment_id`, `subnet_id`, and `gateway_name`; `tags` is an
optional map of freeform tags. Outputs are `gateway_id` and `gateway_base_url`.

The caller owns network prerequisites: Internet routing, stateful TCP 443 ingress,
and egress to upstream services. This module does not manage the existing VCN,
subnet, route table, security lists, or compartment.

This first version creates the gateway only. API deployments, Jawg token injection,
referrer checks, CORS, rate limits, and Object Storage routes are not configured.
The base URL has no application routes until an API deployment is added.
