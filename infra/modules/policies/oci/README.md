# OCI policies module

Manages `grant-fn-access-to-use-objects`, which lets the `traficinfo_fn` dynamic
group use objects in the supplied compartment except the infrastructure state
bucket. Inputs are `compartment_id` and `state_bucket_name`. Provider
configuration comes from the caller; the module contains no account OCIDs.

The dev environment calls this module and records the existing-policy import.
The policy has `prevent_destroy` enabled. The state bucket is owned separately by
bootstrap; its name is supplied as an input and must match the dev backend.
