# Hotel Price V2 — City Validation Report

Date: 2026-08-11. Every number below comes from actually loading the
downloaded CSVs (`training/v2/validate_cities.py`), not from dataset
descriptions. Raw files live in `agent-ml-service/training/data/v2/`
(gitignored). V1 is untouched.

## Target cities (product-aligned, from V2 requirements doc)

Tokyo, Bangkok, Paris, London, New York City, Singapore, Sydney, Barcelona —
latest Inside Airbnb snapshot per city (2026-06-14 … 2026-06-30).

## Per-city validation results

"Usable" = `price_quote_price_per_night` present and > 0.
"Short-stay" = usable AND `minimum_nights ≤ 7` (the hotel-comparable market
segment our product actually asks about; filters out monthly-rental rows).
"Hotel-like" = `property_type` contains "hotel" (Room in hotel / boutique
hotel / aparthotel).

| City | Snapshot | Raw | Usable (parse rate) | Short-stay | Hotel-like (short-stay) | Price median (local) | min_nights>28 | Status |
|---|---|---|---|---|---|---|---|---|
| Tokyo | 2026-06-30 | 34,419 | 32,359 (94%) | 28,647 | 1,823 | 19,579 (JPY) | 11% | **INCLUDE** |
| Bangkok | 2026-06-29 | 31,069 | 28,987 (93%) | 20,845 | 2,923 | 1,592 (THB) | 13% | **INCLUDE** |
| Paris | 2026-06-16 | 77,679 | 48,402 (62%) | 40,969 | 1,471 | 205.5 (EUR) | 14% | **INCLUDE** |
| London | 2026-06-19 | 92,638 | 62,240 (67%) | 58,872 | 1,306 | 180.0 (GBP) | 2% | **INCLUDE** |
| Sydney | 2026-06-16 | 20,573 | 17,784 (86%) | 15,566 | 425 | 291.0 (AUD) | 10% | **INCLUDE** |
| Barcelona | 2026-06-24 | 15,293 | 13,355 (87%) | 8,101 | 472 | 177.7 (EUR) | 37% | **INCLUDE WITH CAUTION** |
| New York City | 2026-06-14 | 30,259 | 21,514 (71%) | 4,985 | 1,498 | 174.7 (USD) | **77%** | **INCLUDE WITH CAUTION** |
| Singapore | 2026-06-29 | 3,097 | 2,592 (84%) | 1,378 | 528 | 127.5 (SGD) | 46% | **INCLUDE WITH CAUTION** |

Notes per CAUTION city:
- **New York City:** 77% of listings have `minimum_nights > 28` — the
  Local-Law-18-style short-stay restriction pushes most supply into monthly
  rentals. Quote-night median is 30. Only the ~5k short-stay subset reflects
  hotel-comparable pricing; the rest is a different market and must be
  excluded. Its hotel-like share is actually the best of all cities (1,498
  of 4,985 = 30%).
- **Singapore:** 46% long-stay (known local minimum-stay regulation, seen in
  the first inspection). Small absolute volume (1,378 short-stay) but high
  hotel-like share (528 = 38%).
- **Barcelona:** 37% long-stay (tourist-license restrictions). Short-stay
  subset (8,101) is still healthy.

No city failed validation outright — every city has `accommodates`,
`room_type`, `property_type` 100% populated on usable rows and all required
columns present. Tokyo needed a URL-encoding fix (non-ASCII "kantō" path
segment) — engineering issue, not a data issue.

## Currency mapping (empirical, NOT from the symbol column)

**Critical finding:** the raw `price` strings carry a literal `$` prefix in
ALL 8 cities — including Bangkok (median 1,592) and Tokyo (median 19,579),
which are obviously THB and JPY magnitudes, not dollars. **The currency
symbol in the data is untrustworthy; magnitude + city is the real evidence.**
This is the V1 currency-mislabeling failure mode again, caught at the data
layer this time.

| City | Source currency (assigned by city, magnitude-verified) |
|---|---|
| Tokyo | JPY |
| Bangkok | THB |
| Paris | EUR |
| Barcelona | EUR |
| London | GBP |
| New York City | USD |
| Singapore | SGD |
| Sydney | AUD |

7 distinct currencies across 8 cities.

> **Update (2026-08-11, during the unified build):** this table was later
> confirmed by direct evidence rather than magnitude reasoning. The
> `price_quote_raw` JSON in `listings.csv` carries an explicit ISO currency
> code, populated for 7 of 8 cities — and **all 7 matched the assignments
> above exactly**. Bangkok's is `null`, but its quote line items are prefixed
> with the `฿` symbol, confirming THB. See
> `hotel-price-v2-dataset-build.md`.

### Normalization strategy (design only — no conversion performed yet)

- **Option A — train in local currency, city as feature:** rejected. The
  target scale would differ by 2 orders of magnitude between cities (JPY
  19,579 vs GBP 180); the model would spend its capacity learning currency
  magnitude rather than price level, error metrics would be dominated by
  JPY/THB rows, and the API contract is USD-only anyway.
- **Option B — convert all prices to USD before training (RECOMMENDED):**
  one consistent target scale; honest alignment with the API's USD-only
  contract; MAE/RMSE comparable across cities. Use **fixed, documented
  reference rates pinned to the snapshot month (2026-06)** — not live FX —
  so training is reproducible and the conversion is auditable. The rate
  table and its source/date must be committed alongside the pipeline.

## Known limitations

- Single snapshot per city: all quotes scraped at one point in time. Stay
  dates (`price_quote_checkin_date`) do spread over future months, so a
  chronological split **on stay date** is possible — but these are
  forward-looking asking prices from one scrape, not historical market
  observations collected across multiple snapshot dates. That means no
  temporal-generalization claim can be made until quarterly snapshots are
  added. See `hotel-price-v2-dataset-build.md` § "What the date axis actually
  supports".
- Short-term-rental platform: hotel-like rows are 10,446 of 179,363
  short-stay rows (~6%). The model will mostly learn the broader
  short-stay accommodation market, with hotels as a minority segment.
- No star rating anywhere (accepted V2 scope decision — declared
  unsupported, not fabricated).
- Parse rates of 62–71% in Paris/London/NYC mean a third of listings carry
  no price quote (inactive/unavailable listings) — excluded rows, not
  corrupted rows.

## Final list approved for V2 training

All 8 cities enter the pool, with a uniform `minimum_nights ≤ 7` short-stay
filter applied to every city (not just the CAUTION ones), yielding:

- **Total raw rows:** 305,027
- **Total usable rows (valid price):** 227,233
- **Total short-stay rows (training pool):** 179,363
- **Hotel-like rows in pool:** 10,446
- **Currencies:** 7 (JPY, THB, EUR×2, GBP, USD, SGD, AUD) → normalize to USD
  at fixed 2026-06 reference rates
