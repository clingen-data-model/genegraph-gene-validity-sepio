# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Clojure data pipeline that transforms ClinGen Gene Validity Curation (GCI) JSON records into SEPIO/GA4GH GKS RDF and publishes them to Kafka. The app runs as a single Kafka consumer group that reads `gene_validity_all`, transforms each record, and writes to `gene-validity-sepio` (N-Triples) and `gene-validity-sepio-jsonld` (JSON-LD).

## genegraph-framework

This application is built on [genegraph-framework](https://github.com/clingen-data-model/genegraph-framework), a Clojure library for data-driven Kafka stream-processing apps. The entire application is described as a single map and started with `p/init` → `p/start`.

### Key concepts

- **App-def map** — top-level keys: `:kafka-clusters`, `:topics`, `:storage`, `:processors`, `:http-servers`. Components reference each other by keyword; `p/init` wires them together.
- **Events** — plain maps with `::event/` namespaced keys (`::event/data`, `::event/key`, `::event/offset`, etc.). Interceptors receive and return event maps.
- **Effects are deferred** — `event/store`, `event/delete`, `event/publish` accumulate on the event map and execute *after* the interceptor chain completes.
- **Interceptors** — Pedestal interceptors (`:enter` fn receives event map, returns modified event map). Specified as values or fully-qualified symbols in the `:interceptors` vector.

### Component types

| Kind | Types |
|---|---|
| Topics | `:simple-queue-topic`, `:kafka-consumer-group-topic`, `:kafka-producer-topic`, `:kafka-reader-topic` |
| Processors | `:processor` (single-threaded), `:parallel-processor` (3-stage pipeline; `:gate-fn` for per-key ordering) |
| Storage | `:rocksdb` (Nippy/LZ4), `:rdf` (Jena TDB2/SPARQL; use `rdf/tx` for reads), `:gcs-bucket`, `:atom` |
| HTTP | `:http-server` (Pedestal/http-kit; `:endpoints` link routes to processors) |

### Startup / shutdown

Order: system-processor → storage → topics → processors → http-servers (shutdown is reverse). Every app has an internal `:system` `SimpleQueueTopic`; components publish lifecycle events (`:starting`, `:started`, `:up-to-date`, `:exception`) to it. When a consumer group topic is started, it first checks to see if the relevant local state stores are up to date (and updates them if not) before processing records with publish side effects.

### RDF API idioms

The `rdf/` namespace wraps Apache Jena. Key patterns used throughout this codebase:

- **Keyword → IRI** — Clojure keywords like `:cg/Statement` resolve to full IRIs via the framework's prefix registry (no need to declare prefixes in `rdf/create-query` strings). The prefix table at the bottom of this file shows the mappings.
- **Property path traversal** — `(rdf/ld1-> resource [:cg/contributions :cg/contributor])` walks a property path and returns the first value; `rdf/ld->` returns all values. Analogous to `get-in` for RDF.
- **`rdf/->kw`** — converts an RDF resource back to a namespaced keyword (e.g. for use as a map key).
- **`rdf/pp-model`** — pretty-prints a Jena model to stdout; primary REPL inspection tool.
- **`rdf/union`** — merges multiple models into one (non-destructive).
- **`rdf/statements->model`** — constructs a model from a sequence of `[subject predicate object]` triples.

### `rdf/declare-query` convention

`(rdf/declare-query foo-bar)` loads `foo_bar.sparql` from the classpath at the path corresponding to the current namespace. Adding a new query requires both the `rdf/declare-query` declaration in `sepio_model.clj` and a matching `.sparql` file in `src/genegraph/transform/gene_validity/sepio_model/`. Queries declared this way accept an optional params map as a second argument for variable binding.

### Namespace imports

```clojure
(require '[genegraph.framework.protocol :as p]
         '[genegraph.framework.event :as event]
         '[genegraph.framework.storage :as storage]
         '[genegraph.framework.storage.rdf :as rdf]
         '[genegraph.framework.event.store :as event-store]
         '[genegraph.framework.kafka.admin :as kafka-admin]
         '[io.pedestal.interceptor :as interceptor])
```


## Commands

```bash
# Start a REPL with dev dependencies (portal, hato, data.csv)
clj -A:dev

# Build an uberjar
clj -T:build uber

# Run tests
clj -M:test

# Build + push Docker image + kubectl apply
clj -T:build deploy
```

There is no linter configured. Tests use `cognitect.test-runner` and are in the `test/` directory (currently sparse — most validation is done interactively via `genegraph.user`).

## Architecture

### Interceptor pipeline

The core is a Pedestal interceptor chain defined in `genegraph.transform.gene-validity/transform-processor`. Each Kafka event passes through:

1. `recorder/record-event` — saves raw event for replay/debugging
2. `gci-model/add-gci-model` — parses GCI JSON → Jena RDF model via JSON-LD
3. `sepio-model/add-model` — runs SPARQL CONSTRUCT queries to build SEPIO model
4. `versioning/add-version` — computes semantic version, detects change types, stores prior version in RocksDB
5. `add-jsonld` — frames the RDF model as JSON-LD using `resources/frame.json`
6. `add-iri` — finds the `cg:EvidenceStrengthAssertion` IRI to use as Kafka key (note: still uses the old class name; should be updated to `cg:Statement`)
7. `website-event/website-version-interceptor` — generates the stakeholder event format (only fires when `:gene-validity/change-type` is set)
8. `add-publish-actions` — queues publish to both output topics

Event data flows as keys on the event map: `::event/data` → `:gene-validity/gci-model` → `:gene-validity/model` → `:gene-validity/json-ld`.

### GCI → RDF parsing (`gci-model.clj`)

GCI records are JSON-LD. The `context` def is a large inline JSON-LD context that maps GCI field names and string values to RDF IRIs/vocabs. Before parsing, several preprocessing steps clean up the JSON: expanding affiliation strings to IRIs, fixing HPO IDs, removing `associatedClassificationSnapshots`. The result is a Jena `Model` at `:gene-validity/gci-model`.

### SEPIO model construction (`sepio-model.clj` + SPARQL files)

SPARQL CONSTRUCT queries live in `src/genegraph/transform/gene_validity/sepio_model/`. They are declared with `rdf/declare-query` (which loads the matching `.sparql` file by name convention) and applied in sequence to build up the output model. The GCI model is first unioned with `gdm_sepio_relationships.ttl` (which maps GCI type strings to SEPIO/CG criteria IRIs) before querying.

Key ontology terms use the `cg:` prefix (`https://genegraph.clinicalgenome.org/terms/`). The central class is `cg:Statement` (formerly `cg:EvidenceStrengthAssertion` — the old name still appears in some places and needs updating). Evidence structure uses `cg:EvidenceLine` with `cg:hasEvidenceItems` or `cg:evidence` depending on query (there is an inconsistency to resolve).

Publish vs. unpublish is branched in `gci-data->sepio-model`: publish runs the full CONSTRUCT pipeline; unpublish generates a minimal `cg:Statement` with a `cg:Unpublished` contribution.

### Versioning (`versioning.clj`)

Computes a `{:major N :minor N}` version for each curation based on comparison with the prior stored version. Major increments for recurations (detected from GCI `curationReasons` or a 6-month heuristic); minor increments for other changes; no increment if model is isomorphic. Change types (classification, MOI, SOP, evidence, summary text, etc.) are detected by SPARQL queries comparing old and new models. The versioned assertion IRI appends `v{major}.{minor}` to the base IRI.

### Website events (`website_events.clj`)

Generates a stakeholder-defined JSON format. The schema is specified with `clojure.spec.alpha` (root spec: `::event-data`). The `event->base-event` function builds the payload from the SEPIO model. The interceptor only fires when `:gene-validity/change-type` is set (i.e., skips unpublish events with no change). Unpublish events retrieve the previously stored website event and mutate it with `unpublish_date`.

### Validation (`validation.clj`)

A small composable framework: a `tests` vector of functions `model → {:result :pass/:fail ...}`, composed by `validate`. Currently has one check (disconnected evidence lines). Note: still references `cg:EvidenceStrengthAssertion` (needs updating to `cg:Statement`).

### REPL development (`user.clj`)

The primary development workflow is REPL-driven using `genegraph.user`. It has helpers to read from local event store files (`.edn.gz`), run the transform pipeline, and inspect results via Portal. Test data paths are under `/Users/tristan/data/genegraph-neo/`.

The standard pattern for working with test data:

```clojure
(event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-03-24.edn.gz"]
  (->> (event-store/event-seq r)
       (take 10)
       (map genegraph.user/transform-curation)
       (filter some-predicate?)
       first
       tap>))
```

`transform-curation` runs a single event through the full interceptor chain (minus Kafka I/O) and is defined in `user.clj`.

### Storage

RocksDB (`gene-validity-version-store`) is used as a key-value store keyed by `[::versioning/prior-version <proposition-iri>]` and `[::website-event <proposition-iri>]`. Snapshots are stored to GCS and restored on startup.

## Namespaces and prefixes

| Prefix | URI |
|--------|-----|
| `cg:` | `https://genegraph.clinicalgenome.org/terms/` |
| `gci:` | `https://genegraph.clinicalgenome.org/r/` |
| `gcixform:` | `https://genegraph.clinicalgenome.org/r/gcixform/` |
| `dc:` | `http://purl.org/dc/terms/` |

## Active work (branch: `sepio-updates`)

Three parallel work streams:

1. **Experimental evidence enrichment** — SPARQL files for model systems, functional, functional alteration, and rescue evidence are minimally populated. Each captures score and one description field but is missing richer GCI data (model organism, variant details, subtype classifications). There is also a predicate inconsistency: `construct_model_systems_evidence.sparql` uses `cg:hasEvidenceItems` while the other experimental evidence files use `cg:evidence`.

2. **Automated testing** — `validation.clj` provides the framework but needs more checks. The REPL-based approach (run over event store `.edn.gz` files, filter failures) is the current de facto method. A testing strategy that can run over a fixed corpus and assert on model structure is needed before release.

3. **Website events** — The stakeholder format is defined by `clojure.spec.alpha/::event-data` in `website_events.clj`. Known issues are noted in that file's comments (unpublish source_uuid, reasons format). The spec can be used to validate a corpus: `(s/valid? ::event-data event)` / `(s/explain ::event-data event)`.

