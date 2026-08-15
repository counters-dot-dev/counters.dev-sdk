# Contributing

Thanks for helping improve the counters.dev SDKs. This repository holds three hand-written client
libraries — TypeScript, Java and Go — one per language directory, plus the API contract and the
shared behaviour vectors they are all held to.

These three are the SDKs the maintainers use in production and can vouch for; every other language
is expected to call the [REST API](https://counters.dev) directly, against the same
[`openapi/openapi.yaml`](./openapi) contract these clients are built from. Pull requests adding an
SDK for a fourth language are not being accepted — see the README for the reasoning.

## What CI runs on your pull request

Each SDK has its own workflow (`.github/workflows/sdk-<lang>-ci.yml`) that runs when files in that
language's directory change. On your pull request it runs:

1. that SDK's **unit tests**, including its **conformance** suite (the shared vectors in
   `conformance/`),
2. its **build**, type-check and/or lint gate,
3. a **compile** of that SDK's example app under `<lang>/examples/e2e/`.

Results are posted back as a sticky comment on the pull request.

On pushes to `main`, the three SDK test jobs and the OpenAPI validation job report CI metrics to
counters.dev. Configuring `COUNTERS_API_KEY` as a repository secret enables the live writes. The
metrics helper is fail-open and short-circuits when the secret is absent, so pull requests and
pre-provisioning runs are inert. Per-app time series requires dashboard member-series enablement for
the CI counters before the secret is turned on; leaderboards and total counter series work without
that manual enablement.

### Why there are no end-to-end tests here

Each SDK's example app can be run against a **live counters.dev service** to replay the shared HTTP
vectors through the real client. That service is a separate, private repository, so it cannot be built
or booted from this one. Those end-to-end suites are run by the maintainers, in the service
repository, against a checkout of this repository — a contract change that would break a client is
caught there before it can ship.

The practical consequence: your pull request is validated with **unit tests + conformance vectors +
build + example-app compile**, with no live service and no network. That is enough to catch the great
majority of problems. If a change needs an end-to-end run to be sure, say so in the pull request and a
maintainer will run one.

## Running a suite locally

Every SDK is independent, with its own toolchain. From the repository root:

| SDK | Command |
|---|---|
| TypeScript | `cd typescript && npm ci && npm test` |
| Java | `cd java && ./gradlew test` |
| Go | `cd go && go test ./...` |

Each language's own README has more detail, including a container command if you would rather not
install the toolchain.

The API contract check runs from the repository root and needs only Node:

```sh
node scripts/openapi-drift/check.mjs
```

## `openapi/` and `conformance/` are synced — do not edit them

Both directories are **mirrored in, read-only**, from the private counters.dev service repository,
which is their source of truth: the service asserts against the very same files, which is what makes
them meaningful. Edits made to them here will be overwritten by the next sync.

- Found a wrong or missing **vector**? Open an issue, or a pull request that describes the vector you
  want but leaves `conformance/` untouched. A maintainer will land it upstream and sync it down.
- Think the **spec** is wrong? Open an issue.
- Think an **SDK** doesn't match the spec or the vectors? That's an SDK bug — a pull request fixing
  the SDK is very welcome.

See [`conformance/SYNCED.md`](./conformance/SYNCED.md) and [`openapi/SYNCED.md`](./openapi/SYNCED.md).

## Conventions

- **Amounts and values are decimal strings on the wire, never native numbers.** Arbitrary precision is
  the product's headline guarantee; an SDK that parses a value into a float or an `i64` is broken, even
  if its tests pass. Every SDK proves this end to end with a value larger than a `u64`.
- **The clients are hand-written on purpose.** There is no code generation and no `generated/`
  directory. If you add an operation to one SDK, add it to the other two as well, and add its
  signature to `scripts/openapi-drift/check.mjs` — the drift guard fails the build otherwise.
- **Work in one language directory at a time.** A pull request touching one SDK is much easier to
  review than one touching all three.
- **Add a vector rather than a bespoke assertion** when the behaviour is shared. A vector fans out to
  every implementation for free; a hand-written assertion in one SDK protects only that SDK.
  (Vectors go upstream — see above.)

## Publishing

Publishing is **currently on hold**: every publish workflow is dry-run verified, but no release tag has
been pushed and no package is live yet. Releases are cut by maintainers by pushing a release tag —
`ts-v<semver>` for TypeScript, `java-v<semver>` for Java, and `go/v<semver>` for Go (the Go module
proxy resolves a subdirectory module from that tag form, so Go needs no publish workflow).

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](./LICENSE).
