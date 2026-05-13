# Recommendation Event and Rank-Trace Contract

This contract is the Phase 1 ES-only foundation for AI recommendation quality work. It defines the durable event and rank-trace shape used by the question recommendation and personalized recommendation lanes before any pgvector production cutover.

## Scope and invariants

- Target service: `itplace-user-api`.
- Phase 1 storage/routing posture: Elasticsearch remains the production recommendation vector/search baseline.
- pgvector is not a production primary path in this phase; any future pgvector work must stay disabled or shadow-only until separate gates pass.
- Raw question/search text and precise location are not canonical analytics fields. Store redacted, normalized, hashed, or bucketed values unless a separate retention policy explicitly allows raw capture.
- Every recommendation response that can be evaluated later must be tied together by `request_id`, `impression_id`, `rank`, and `algorithm_version`.

## Canonical event envelope

| Field | Required | Notes |
| --- | --- | --- |
| `event_id` | Yes | UUID or stable idempotency key. Duplicate `event_id` must be ignored or rejected. |
| `schema_version` | Yes | Current contract version, e.g. `recommendation-event.v1`. |
| `request_id` | Yes for recommendation/question flows | One recommendation/question request chain. Legacy logs without this value should be backfilled with a generated migration request id and marked incomplete. |
| `impression_id` | Yes for shown candidates and follow-up clicks | One shown candidate attribution unit. Click/detail/favorite/use events should carry the impression id when known. |
| `user_id_hash` | Yes | Pseudonymous analytics key. Avoid raw user id in event marts; keep any raw id mapping controlled inside service storage. |
| `session_id` | Optional | Session attribution when available. |
| `event_type` | Yes | Allowed values listed below. |
| `service_type` | Yes for recommendation/question flows | `question_recommendation` or `personalized_recommendation`. |
| `source_surface` | Yes | Examples: `question_chat`, `personalized_list`, `search`, `map`, `benefit_detail`, `favorite`, `usage`. |
| `target_type` | Optional | `benefit`, `partner`, `category`, `store`, `question`, or `recommendation_request`. |
| `benefit_id` | Optional | Required when target is a benefit or when a shown/clicked candidate has a benefit. |
| `partner_id` | Optional | Required when target is a partner or when the benefit partner is known. |
| `store_id` | Optional | Required for store-specific map/question result attribution when known. |
| `rank` | Required for impressions and clicks | One-based displayed rank. Preserve original shown rank even if later re-ranked. |
| `candidate_source` | Required for recommendation candidates | Examples: `es_vector`, `es_lexical`, `question_memory`, `favorite_neighbor`, `history_neighbor`, `popularity`, `db_fallback`, `pgvector_shadow`. |
| `algorithm_version` | Yes for recommendation/question flows | Ranking/scoring version, e.g. `es_quality_v1`. Must change when weights or candidate generation change. |
| `experiment_arm` | Optional but expected online | Examples: `baseline_es_current`, `es_quality_v1`, `hybrid_shadow`. |
| `query_text_redacted` | Optional | Redacted search text only. Do not persist raw search text by default. |
| `question_text_redacted` | Optional | Redacted question text only. Do not persist raw question text by default. |
| `normalized_intent` | Optional | Preferred derived feature for search/question intent. |
| `category_tags` | Optional | Normalized category/situation labels used by retrieval/ranking. |
| `geo_bucket` | Optional | Coarse bucket/grid. Do not persist precise latitude/longitude by default. |
| `consent_basis` | Yes | Internal policy value such as `service_contract` or `consent`. |
| `metadata` | Optional | JSON object for schema-valid extension fields. Must not contain raw PII. |
| `occurred_at` | Yes | Event time. |
| `ingested_at` | Yes | Server ingest time. |

## Allowed event types

- `recommendation_request`
- `recommendation_impression`
- `recommendation_click`
- `search_submit`
- `search_result_impression`
- `search_result_click`
- `benefit_detail_view`
- `favorite_add`
- `favorite_remove`
- `benefit_use`
- `question_ask`
- `question_result_click`
- `dismiss`
- `skip`
- `feedback_positive`
- `feedback_negative`

## Rank trace contract

Rank trace is one row/document per recommendation request. It is the audit source for deterministic ranking, grounded explanations, rollback checks, and offline replay.

| Field | Required | Notes |
| --- | --- | --- |
| `request_id` | Yes | Must match event envelope `request_id`. |
| `schema_version` | Yes | Current contract version, e.g. `recommendation-rank-trace.v1`. |
| `service_type` | Yes | `question_recommendation` or `personalized_recommendation`. |
| `algorithm_version` | Yes | Same value emitted on related events. |
| `experiment_arm` | Optional | Same value emitted on related events. |
| `candidate_ids` | Yes | Ordered candidate benefit/partner/store ids before final display filtering. |
| `candidate_sources` | Yes | Per-candidate sources such as `es_vector`, `favorite_neighbor`, `db_fallback`. |
| `score_components` | Yes | Stable numeric component map per candidate. Include only evidence actually used. |
| `shown_ids` | Yes | Ordered ids returned to the user. |
| `impression_ids` | Yes | One id per shown candidate for downstream click/detail/use attribution. |
| `fallback_flags` | Yes | Examples: `es_failure`, `openai_timeout`, `empty_vector_result`, `cached_recommendation`. Empty list when none. |
| `latency_ms` | Yes | Layer timings where possible: embedding, retrieval, ranking, explanation, total. |
| `privacy_flags` | Yes | Mark whether text was redacted, geo was bucketed, or attribution is incomplete. |
| `created_at` | Yes | Server creation time. |

### Initial score component names

Use stable names so tests and evaluation fixtures can compare versions:

- `semantic_similarity`
- `intent_category_match`
- `eligibility_value_match`
- `location_store_fit`
- `behavior_affinity`
- `recent_intent`
- `favorite_signal`
- `usage_signal`
- `detail_signal`
- `negative_fatigue`
- `popularity_prior`
- `freshness_business_priority`

LLM-generated explanations may mention a user signal only when the corresponding score component or event evidence is present in the rank trace.

## Legacy `LogDocument` mapping

Current Mongo `LogDocument` fields:

| Legacy field | Canonical mapping | Notes |
| --- | --- | --- |
| `id` | `event_id` | Keep stable during backfill. Generate UUID only when missing. |
| `userId` | `user_id_hash` | Hash/pseudonymize before analytics export. |
| `event = click` | `recommendation_click` or `search_result_click` | Use path/param/source context to decide. If unknown, map to `recommendation_click` with `metadata.legacy_event = click` and attribution incomplete. |
| `event = search` | `search_submit` or `search_result_impression` | Benefit response advice may represent result exposure; preserve `metadata.legacy_event = search`. |
| `event = detail` | `benefit_detail_view` | Carry `benefit_id`/`partner_id` when available. |
| `benefitId` | `benefit_id` | Optional for partner/store-only events. |
| `benefitName` | `metadata.legacy_benefit_name` | Do not use as identity if id exists. |
| `partnerId` | `partner_id` | Optional. |
| `partnerName` | `metadata.legacy_partner_name` | Do not use as identity if id exists. |
| `path` | `source_surface`, `metadata.legacy_path` | Normalize known paths to `search`, `map`, `benefit_detail`, or `personalized_list`. |
| `param` | `query_text_redacted` or `metadata.legacy_param_redacted` | Redact before persistence in canonical storage. |
| `loggingAt` | `occurred_at` | `ingested_at` is canonical ingestion/backfill time. |

Backfilled rows that cannot recover `request_id`, `impression_id`, `rank`, `algorithm_version`, or `candidate_source` must set an attribution completeness flag in metadata or rank trace rather than inventing precise values.

## Privacy and PII rules

- Apply redaction before writing `query_text_redacted` or `question_text_redacted`. Remove obvious email, phone, card-like, and resident-registration-like patterns.
- Prefer `normalized_intent`, `category_tags`, and content hashes over raw text.
- Persist `geo_bucket` instead of precise lat/lng. Precise coordinates may be transient request inputs only unless separately approved.
- LLM prompts for explanations should include candidate ids, score components, and normalized evidence, not raw PII.
- Deletion/export handling must be able to locate events by `user_id_hash` and any controlled raw-id mapping.

## Rollback gates

- `recommendation.vector.primary=es` or equivalent configuration must force ES-only response candidates.
- `recommendation.pgvector.enabled=false` must prevent pgvector from affecting user-visible responses.
- ES failure must set a fallback flag and use the current DB/default fallback path where available.
- OpenAI embedding/explanation failures must return controlled service-unavailable or template fallback behavior, not untracked partial responses.
- Replaying the legacy log backfill twice must be idempotent by `event_id` or stable idempotency key.

## Observability gates

Before Phase 1 is considered ready, dashboards or exported checks should include:

- Request count by `service_type`, `algorithm_version`, and `experiment_arm`.
- Attribution completeness: share of impressions/clicks with `request_id`, `impression_id`, `rank`, and `candidate_source` present. Initial alert threshold: below 95% for new recommendation traffic.
- p50/p95/p99 latency by layer: embedding, retrieval, deterministic ranking, LLM explanation, total.
- ES query latency/error/fallback counts.
- OpenAI embedding and chat call counts plus cost estimate.
- Cache hit/miss/invalidation reason for personalized recommendation.
- Funnel metrics: impression -> click -> detail -> favorite -> use.
- Grounded explanation violation count. Initial alert threshold: above 1%.
- Event ingestion lag and backfill mismatch count.

## Lightweight verification checklist

- Grep this file for the mandatory fields: `request_id`, `impression_id`, `rank`, `algorithm_version`, `experiment_arm`, `candidate_source`, `consent_basis`, and `schema_version`.
- Confirm legacy mappings mention `LogDocument`, `event`, `benefitId`, `partnerId`, `path`, `param`, and `loggingAt`.
- Confirm rollback and observability sections are present.
