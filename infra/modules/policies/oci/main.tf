resource "oci_identity_policy" "function_objects" {
  compartment_id = var.compartment_id
  name           = "grant-fn-access-to-use-objects"
  description    = "Grant Functions access to use OOS resources"
  statements = [
    "Allow dynamic-group traficinfo_fn to use objects in compartment id ${var.compartment_id} where target.bucket.name != '${var.state_bucket_name}'"
  ]

  lifecycle {
    prevent_destroy = true
  }
}
