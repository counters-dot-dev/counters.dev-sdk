# conformance/ is synced — do not edit here

This directory is **mirrored in** from the private counters.dev service repository, which is the
source of truth for it. The service and all three SDKs assert against the same vectors, so the
vectors have to live next to the service that they also pin.

**What that means for you:**

- Changes made to `conformance/**` in *this* repository will be **overwritten** by the next sync.
- If a vector is wrong, or you need a new one to cover a behaviour an SDK gets wrong, open an issue
  (or a pull request that leaves `conformance/` alone and describes the vector you want). A
  maintainer will land it upstream and sync it here.
- Everything else in this repository — the three SDKs, their tests, their example apps — is
  developed here and takes pull requests normally.

The same applies to [`../openapi/`](../openapi/), which is the API contract.
