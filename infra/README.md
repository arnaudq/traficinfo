# Infrastructure

OpenTofu configurations live in `environments/<environment>` and call modules
under `modules/<capability>/<cloud>`. Only dev is configured at present.
The configuration uses standard `.tf` syntax shared with Terraform; validation
is performed with OpenTofu. Commit each environment's `.terraform.lock.hcl`.

## Dev gateway

Private deployment settings select the existing compartment, region, and public
subnet. This configuration references that network without managing it. The
subnet must allow stateful TCP 443 ingress and have Internet routing and upstream
egress.

The first apply creates the [edge resources](modules/edge/oci/README.md) and adopts
the existing functions Object Storage policy through the
[policies module](modules/policies/oci/README.md).
That policy excludes the configured state bucket from application functions'
access. Subsequent plans should show no changes unless configuration or
infrastructure has changed. Keep `state_bucket_name` in the private dev variables
consistent with the bucket in the private dev backend settings.

## Monitoring

The [monitoring module](modules/monitoring/oci/README.md) manages three function
failure alarms and two OSM mapping status alarms. Set `function_application_id`
and `notification_topic_id` in the private `dev.tfvars` to reference the existing
Functions application and Notifications topic. Topic subscriptions remain outside
Terraform; there is no recipient variable or subscription verification.

Use the same `tofu plan` and `tofu apply` commands below to manage all five alarms.
The former function and OSM alarm setup scripts have been replaced by this module.

## Private deployment settings

Keep account identifiers and backend locations outside this repository:

```text
~/.config/traficinfo/
├── dev.tfvars
├── bootstrap.tfvars
├── dev.s3.tfbackend
└── bootstrap.local.tfbackend
```

`.config` is the default per-user configuration directory in the
[XDG Base Directory Specification](https://specifications.freedesktop.org/basedir/latest/),
not a Terraform or OpenTofu convention. It is used here to group local CLI
configuration outside Git. OpenTofu accepts these files anywhere and does not
discover this directory automatically; the commands below pass explicit paths.
If you prefer another location, change those paths consistently.

The repository contains only placeholder templates:

- `environments/dev/terraform.tfvars.example` for `dev.tfvars`.
- `bootstrap/oci/terraform.tfvars.example` for `bootstrap.tfvars`.
- `environments/dev/backend.s3.tfbackend.example` for `dev.s3.tfbackend`.
- `bootstrap/oci/backend.local.tfbackend.example` for `bootstrap.local.tfbackend`.

For a new workstation, copy and fill these examples in the private directory.
Use permissions `0700` for that directory and `0600` for its files. Back up the
private settings separately; Git does not preserve them. Actual `.tfvars` and
`.tfbackend` files under `infra/` are also ignored as a secondary safeguard.
Keep OCI authentication keys and passwords in their existing credential stores.
Component-specific settings are documented in each module's README.

## Authentication and commands

Install OpenTofu 1.13 or newer, then run from the repository root. Assuming
`source ./scripts/setup.sh` has already initialized the shared environment, load
provider authentication once per shell:

```sh
source ./infra/oci-env.sh
```

The normal workflow has three commands. `init` is needed on the first run and
when backend, module, or provider settings change. `apply` shows a plan and asks
for confirmation before making changes:

```sh
tofu -chdir=infra/environments/dev init -backend-config="$HOME/.config/traficinfo/dev.s3.tfbackend"
tofu -chdir=infra/environments/dev plan -var-file="$HOME/.config/traficinfo/dev.tfvars"
tofu -chdir=infra/environments/dev apply -var-file="$HOME/.config/traficinfo/dev.tfvars"
```

The authentication helper reads the file selected by `OCI_CLI_CONFIG_FILE`, using
`OCI_CLI_PROFILE` or `DEFAULT`, and exports API-key authentication settings for the
provider. The provider has no custom config-file-path argument; these environment
variables ensure it uses the same identity as the CLI rather than another default
config. Use a fresh shell without conflicting `TF_VAR_auth`, `TF_VAR_tenancy_ocid`,
`TF_VAR_user_ocid`, `TF_VAR_fingerprint`, or `TF_VAR_private_key*` overrides, which
the OCI provider gives precedence over `OCI_*` settings. CI can supply the
provider's authentication environment variables directly without this helper.
Authentication keys stay in the existing local OCI config/key files. Do not put
credentials or Jawg tokens in committed files. `-var-file` supplies resource
inputs for `plan` and `apply`; `-backend-config` supplies state-storage settings
only during `init`. They serve different purposes.

Optional checks and output inspection:

```sh
tofu -chdir=infra fmt -check -recursive
tofu -chdir=infra/environments/dev validate
tofu -chdir=infra/environments/dev output
```

To apply exactly a reviewed saved plan instead, add
`-out="$HOME/.local/state/traficinfo/dev.tfplan"` to the `plan` command above,
then run `tofu -chdir=infra/environments/dev apply "$HOME/.local/state/traficinfo/dev.tfplan"`.
The saved plan already contains the input values; do not pass `-var-file` when
applying it. Saved plans contain deployment identifiers and may contain secrets;
keep them outside the checkout and remove them when no longer needed. The private
state directory must exist before saving a plan there. Git also ignores plans
accidentally written under `infra/`.

## State

Dev uses the private, versioned `traficinfo-infra-state` Object Storage bucket in
the configured compartment and region. Its state key is `dev/terraform.tfstate`.
OpenTofu's S3 backend uses OCI's S3-compatible endpoint
with native lockfiles enabled. Everyone applying dev must use this same backend;
the state object is first written when an operation persists state.

On 2026-11-15, a competing OpenTofu plan was verified to reject an existing
lock with HTTP 412, and the temporary verification lock was removed afterward.

Backend authentication is separate from OCI provider authentication. Create an
OCI Customer Secret Key for your OCI user, and save its access-key/secret-key pair
in the local `~/.aws/credentials` file:

```ini
[traficinfo-state]
aws_access_key_id = <OCI customer access key>
aws_secret_access_key = <OCI customer secret key>
```

Use file permissions `0600` (`chmod 600 ~/.aws/credentials`). The AWS filename is
the standard format read by the S3 backend; no AWS account is involved. The
private backend settings name only the profile. Each operator should use their own
credentials. The key has the OCI user's permissions; the profile name does not
restrict access. Provider authentication still uses `infra/oci-env.sh` and the
existing API signing key. Never commit backend credentials, state, or saved plans.

Grant state access only to infrastructure operators and CI identities. The
existing `traficinfo_fn` Object Storage policy excludes this bucket. Any future
compartment-wide grants must preserve that separation. Bucket versioning retains
previous state versions for recovery; no expiry rule is configured.

## State-storage bootstrap

`bootstrap/oci` owns only the state bucket, with `prevent_destroy` enabled.
Keeping this root separate means destroying dev cannot delete its state bucket.
The bucket is private and versioning is enabled. Dev owns the functions Object
Storage policy through the policies module; that policy also has
`prevent_destroy` enabled.

Bootstrap runs only when changing state-storage infrastructure. With provider
authentication loaded:

```sh
tofu -chdir=infra/bootstrap/oci init -backend-config="$HOME/.config/traficinfo/bootstrap.local.tfbackend"
tofu -chdir=infra/bootstrap/oci plan -var-file="$HOME/.config/traficinfo/bootstrap.tfvars"
tofu -chdir=infra/bootstrap/oci apply -var-file="$HOME/.config/traficinfo/bootstrap.tfvars"
```

Bootstrap itself uses local state outside the checkout. The private backend
settings specify its absolute path, currently
`~/.local/state/traficinfo/bootstrap/terraform.tfstate`. `.local/state` is the XDG
default location for persistent user state, distinct from `.config` for settings.
Use directory permissions `0700` and state-file permissions `0600`.

Retain and back up this state. On another workstation, restore it to a private
location and update `bootstrap.local.tfbackend` before initializing bootstrap;
otherwise OpenTofu will not know the bucket already exists. Ordinary dev commands
use the remote state backend and do not need this bootstrap state.

Recovery snapshots from the relocation are stored outside the checkout under
`~/.local/state/traficinfo/recovery/`. Those snapshots are historical: older ones
can include the policy before it was transferred to dev. They must not replace
the active state without reviewing resource ownership. The bootstrap bucket name
is also specified in the private dev backend settings; changing it requires
reviewing both configurations.
