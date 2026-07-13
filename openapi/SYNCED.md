# openapi/ is synced — do not edit here

`openapi.yaml` is the counters.dev API contract. It is **mirrored in** from the private counters.dev
service repository, which is the source of truth for it, and it is the *shape* source of truth for
every SDK in this repository.

**What that means for you:**

- Changes made to `openapi/openapi.yaml` in *this* repository will be **overwritten** by the next
  sync.
- The SDK clients are **hand-written** — there is no code generation. `scripts/openapi-drift/check.mjs`
  (run by the `openapi-validate` workflow) diffs each SDK's operation inventory against the spec, so a
  hand-written client cannot silently drift from the contract.
- If the spec is wrong, open an issue. If an SDK is missing an operation the spec defines, that is an
  SDK bug and a pull request against the SDK is welcome.

The same applies to [`../conformance/`](../conformance/), which pins API *behaviour*.
