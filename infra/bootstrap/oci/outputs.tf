output "state_bucket_name" {
  description = "Private versioned bucket for infrastructure state."
  value       = oci_objectstorage_bucket.state.name
}
