# Hotel Price V2 — India Fair-Price Model (Production Methodology)

Date: 2026-08-14. Status: artifact + predictor + endpoint + tests built.
**Not integrated with Spring or Android. Nothing committed.**

## 1. What this model actually claims

> For the user's **first night**, at **1 room / 2 adults / 1 night**, is this
> hotel's current **INR** quote inside the historical fair-price range?

It does **not** claim:

- that a multi-night booking **total** is cheap or expensive,
- that the price **will** drop later,
- anything about hotels outside **India**.

`WAIT AND RECHECK` means exactly that — recheck later. It is **not** a
prediction that the price will fall. This dataset cannot support future-price
forecasting and we do not pretend otherwise.

## 2. Data provenance and the currency assumption

PromptCloud *"Travel & Hotel Listing from Booking.com 2020"* (Kaggle, **CC0**),
marketing sample `20200301–20200331`, **29,988 source documents** → 165,065
expanded offers → **152,512 cleaned offers / 13,187 hotel keys**.
Crawl dates 2020-03-01…03-24, check-in 2020-03-01…04-07, lead 0–15 days.

**Currency is a stated prototype assumption, not a fact in the data.** The
source has no currency field. Evidence: 100% of `pageurl`s are
`booking.com/hotel/in/` (single country), and magnitudes (P25 1,814 / P50 3,174
/ P75 5,940) match INR. Recorded in the artifact as `currencyBasis`.

**Training context is proven, not assumed.** Every one of the 29,988 pageurls
carries Booking.com query params showing `checkout − checkin = 1 night`,
`no_rooms=1`, `group_adults=2`, `group_children=0`. Hence
`comparisonBasis = PER_NIGHT_1ROOM_2ADULTS`.

## 3. Architecture

```
KNOWN hotel   (unambiguous normalized name AND >= 5 training offers)
      -> B2 hotel-own historical quantiles          predictionSource = "HISTORICAL"

UNKNOWN / ambiguous / insufficient history
      -> M1 CatBoost MultiQuantile                  predictionSource = "ML"

      -> sorted([P25, P50, P75])
      -> decision band
      -> compare one-night INR quote
      -> CHEAP / FAIR / EXPENSIVE
```

Chosen because the segments disagree, consistently, across all five temporal
splits: on **known** hotels B2 beat M1 by ~37% (M1 won 0/5); on **unknown**
hotels M1 beat B2 by ~8% (M1 won 5/5). The hybrid beat **both** on 5/5
(vs B2 +4.51%, vs M1 +14.27%).

**Quantile sorting is deliberate post-processing, not a hidden fix.** CatBoost
quantile regression crossed on ~0.023% of validation rows; production sorts the
three values before the band is computed.

## 4. Features (frozen)

`hotel_key`, `room_category`, `breakfast_category`, `cancellation_category`,
`lead_time_days`, `ci_month`, `ci_dow`, `ci_weekend`, `cr_dow`

Excluded, with reasons recorded in the artifact:

| Excluded | Why |
|---|---|
| `occ` | PromptCloud value is the room's **advertised capacity** (1–70); LiteAPI `adultCount` is the **requested party size**. Different quantities — cannot be served honestly. |
| `currentPrice` | Never a feature. It is the thing being judged. |
| `price_rank`, `default_rank` | Rank fields, not prices. |

Training and inference share **one** normalization implementation:
`ml/india_serving_features.py` (the training tree imports it via a shim).

## 5. Decision band

```
BUSINESS_TOLERANCE = 0.15          # product rule, not an ML parameter
decision_low  = min(P25, 0.85 * P50)
decision_high = max(P75, 1.15 * P50)
```

The band only ever widens, so `low <= P25 <= P50 <= P75 <= high` holds by
construction. Audited on 104,688 held-out rows: **0 semantic contradictions**,
FAIR share 42.8% → **49.4%**, and quote-perturbation flip rate roughly halved
(±2%: 6.82% → 4.32%; ±5%: 13.02% → 9.84%).

## 6. The one-night probe

LiteAPI returns **stay totals**, not per-night rates, so a multi-night quote is
not comparable to this model. The probe (`ml/india_liteapi_probe.py`) therefore
issues its own request: same hotel, user's real check-in, `checkout = check-in
+ 1 day`, 1 room, 2 adults, `currency: INR`.

We deliberately do **not** compute `total / nights` — multi-night totals are not
linear in nights (discounts), so that division would be wrong.

The probe is **isolated** from the Agent's hotel-search/booking flow, which
prices whole stays in USD for budget logic and must not be disturbed.

Because the Agent's search result does not carry the traveller's specific
room/rate, the probe selects the **cheapest valid one-night offer** and reports
that honestly as `comparisonOfferSelection = "CHEAPEST_COMPARABLE_ONE_NIGHT"`.

## 7. Scope guards — the model declines rather than extrapolates

| Condition | Response |
|---|---|
| country not India | `predictionAvailable: false`, `UNSUPPORTED_MARKET` |
| currency not INR | `predictionAvailable: false`, `UNSUPPORTED_MARKET` |
| `lead_time_days > 15` | `predictionAvailable: false`, **`UNSUPPORTED_LEAD_TIME`** |
| check-in before booking (`lead < 0`) | `predictionAvailable: false`, `INVALID_INPUT` |
| `currentPrice` NaN / ±Inf / ≤ 0 | rejected (HTTP 422 at the edge, `INVALID_INPUT` in-process) |
| no comparable one-night offer | `predictionAvailable: false`, `NO_COMPARABLE_RATE` |
| artifact missing | `predictionAvailable: false`, `MODEL_ERROR` |

**Lead time is a hard guard, not a clamp.** Training support is strictly
0–15 days. Outside it the model has no evidence, so we return nothing rather
than a clamped or extrapolated number. `MIN_LEAD_DAYS`/`MAX_LEAD_DAYS` are
asserted against the artifact's recorded `leadTimeRange`, so the guard cannot
silently drift from the data.

`/data/hotels` returns `country`; the existing search flow discards it, so the
probe fetches it explicitly. The model is never applied to other markets.

Practical consequence: a traveller planning more than two weeks ahead gets
**no verdict**. That is the correct behaviour for this dataset and a real
coverage limit of the feature, not a bug to be tuned away.

## 8. Known limitations

1. **March 2020 data**, ~6 years old, and the tail of that window overlaps the
   onset of COVID travel disruption in India.
2. **Currency is assumed INR**, not stated in the source.
3. **Lead time 0–15 days only** — no long-horizon bookings in training, and
   requests outside that window are declined (`UNSUPPORTED_LEAD_TIME`), so the
   feature simply does not cover advance planning.
4. Intervals still under-cover slightly (≈49.4% inside the band vs a 50% target).
5. Hotel matching is **exact normalized name**; 191 names (1.45%) are ambiguous
   and are routed to ML rather than guessed. No fuzzy matching.
6. `room_category` is a coarse 8-class mapping; it cannot distinguish
   "Deluxe King" from "Deluxe Double".

## 9. Integration note (must not be lost at merge)

This branch's `main.py` registers only `ml_router`; `agent_router` is still a
TODO here, while `origin/main` already registers it. When these branches merge,
the final `main.py` must include **both**:

```python
app.include_router(agent_router)
app.include_router(ml_router)
```

and keep the Agent startup cleanup loop. This branch intentionally did not add
`agent_router` — its local `agent/routes.py` is an empty 9-line stub, so
registering it here would give false assurance and risk clobbering main's real
integration.
