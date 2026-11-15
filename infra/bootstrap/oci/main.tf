resource "oci_objectstorage_bucket" "state" {
  compartment_id = var.compartment_id
  namespace      = var.namespace
  name           = "traficinfo-infra-state"
  access_type    = "NoPublicAccess"
  storage_tier   = "Standard"
  versioning     = "Enabled"

  freeform_tags = {
    project = "traficinfo"
    purpose = "infrastructure-state"
  }

  lifecycle {
    prevent_destroy = true
  }
}
