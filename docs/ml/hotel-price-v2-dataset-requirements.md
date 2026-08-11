# Hotel Price V2 — Dataset Requirements & Data Contract

Status: requirements definition (2026-08-11). V1 (`baseline-rf-v1`, commit
`35390b5`) is frozen as the stable integration baseline and is NOT modified
by V2 work.

## Why V2 exists

The V1 dataset (Hotel Booking Demand — 2 Portuguese hotels, 2015–2017, no
city / star-rating / usable room-type / currency fields) supports only a
date-and-guests-driven baseline. The Dataset Fitness Audit (2026-08-11)
verdict was **B: keep dataset for V1, redesign inputs** — and flagged that
city/star/room support requires *different data*, not more feature
engineering. V2's goal is a training dataset that actually covers the
product's claimed input surface.

## Prediction target

**V2 predicts nightly hotel price (per-night rate).** Total stay price stays
a derived quantity (`nightly × nights`), same as V1.

Why nightly, not total:
1. Keeps the existing API/response semantics — no Spring Boot contract change.
2. Total conflates rate level with stay length; nightly isolates the quantity
   the model should actually learn.
3. Industry-standard ADR and nearly all candidate data sources are per-night.

## Field classification

### MUST HAVE (dataset rejected if absent or unusable)
| Field | Notes |
|---|---|
| Nightly price (or ADR, or total÷nights derivable) | The target. Must be a real observed/quoted price, not an index or score. |
| City (or geo coordinates mappable to city) | The #1 gap in V1. Must be the **property's** location, not the guest's origin. |
| Date dimension (stay date, quote date, or snapshot date) | Needed for seasonality features AND a time-based split. A single undated snapshot is rejectable. |
| Property identity (hotel id/name) | Needed to prevent the same property leaking across train/test and to join metadata. |
| Currency (explicit, or single known currency) | V1's EUR-mislabeled-as-USD problem must not repeat. Mixed currencies without labels = rejected. |

### STRONGLY PREFERRED
| Field | Notes |
|---|---|
| Hotel star rating | Second-biggest V1 gap. If missing from pricing data, may come from a joined metadata source. |
| Number of nights / stay duration | Else nightly price must be directly given. |
| Guest count (adults; children separately if possible) | V1 proved guests carry signal. |
| Recency (data from ≥2019, ideally later) | V1's 2015–2017 window is ~9 years stale for 2026 queries. |
| Multiple countries / ≥20 cities | "City-level modeling" needs enough cities to learn city effects, not memorize 2–3. |

### OPTIONAL
| Field | Notes |
|---|---|
| Room type (mappable to single/double/twin/suite) | API accepts it; nice to finally use it, but most public sources lack a clean mapping. |
| Property type (hotel/aparthotel/hostel) | Useful control variable. |
| Review score / number of reviews | Price-relevant metadata if present. |
| Weekend/weekday flag | Derivable if dates exist; standalone flag acceptable. |

### DERIVABLE (do not require as columns)
`lead_time` (booking→check-in), `arrival_month`/season, weekend flag,
`nights` (from date pairs), guest total (from adults+children).

### DO NOT USE (leakage / post-outcome / co-determined with price)
- Anything set at/after check-in (`assigned_room_type`-style fields,
  reservation status, cancellation outcome).
- Channel/segment labels assigned during or after booking
  (`market_segment`, `deposit_type`, `customer_type` — flagged QUESTIONABLE
  in the V1 audit; V2 must not carry them forward).
- Review text/scores *for the specific stay being predicted*.

## Dataset rejection criteria

Reject a candidate if ANY of:
1. **No usable price target** (scores, indexes, or price "levels" 1–4 only).
2. **No property-side location** (guest nationality ≠ hotel city).
3. **Single/duo-property coverage** regardless of row count — V1 already
   taught us row count without diversity doesn't generalize.
4. **No date dimension at all** (static catalog snapshot) — no honest
   time-based split possible.
5. **Unlabeled mixed currencies** — silently corrupts the target scale.
6. **License unusable** (competition-restricted with unclear reuse, ToS-violating scrape, no-redistribution blocking teammates from downloading).
7. **Description-only availability** (cannot actually download/inspect the
   file — claims can't be verified).

## Success criteria for the chosen V2 dataset (or combination)

- ≥20 cities across ≥5 countries, ≥1,000 distinct properties
- Real nightly prices in a known currency (single or labeled)
- A date axis supporting a genuine chronological split
- Star rating available for a majority of rows (directly or via join)
- Downloadable and inspectable tonight-to-this-week, license-clean

## Selected source coverage (verified 2026-08-11)

Inside Airbnb (chosen Primary — see the V2 shortlist discussion) was verified
by enumerating its actual download index, not the marketing copy: **37
countries/regions, 121 distinct city datasets**, quarterly snapshots (latest
2026-06), CC BY 4.0, no account gate. V2 starts with the **8 cities that
intersect the existing mock/API city list** (Tokyo, Bangkok, Paris, London,
New York, Singapore, Sydney, Barcelona — Dubai and Bali are not covered, as
the UAE and Indonesia are not among the 37 countries). This 8-city scope is
a product-alignment decision, not a source limitation; expanding toward the
121 available cities later requires only downloading more files.

Verified per-city findings (Singapore, actual file inspection 2026-08-11):
- `calendar.csv` **no longer carries prices** (availability only) — the
  price signal lives in `listings.csv`'s `price_quote_*` fields (per-night
  quote with real check-in/check-out dates). Trust the files, not the docs.
- `property_type` includes a genuine hotel subset (`Room in hotel`,
  `Room in boutique hotel`, `Room in aparthotel` — 635 of 3,097 Singapore
  listings).
- Singapore quotes are dominated by ~92-night stays (local minimum-stay
  regulation) — each city must be individually inspected before joining the
  training pool; Singapore likely needs filtering or exclusion.
