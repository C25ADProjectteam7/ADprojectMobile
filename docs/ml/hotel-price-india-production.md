# Hotel Price V3 — India Fair-Price Model (Production Methodology)

Date: 2026-08-15. Model version `india-v3-m2`, superseding `india-hybrid-v21`
on the **unseen-hotel path only**. The HISTORICAL path is unchanged since v1.
Status: artifact + predictor + endpoint + tests integrated end-to-end.

## 0. What changed in V3, in one paragraph

The unseen-hotel model is no longer trained on the 2020 PromptCloud scrape. It
is trained on a **LiteAPI-native 2026 snapshot**, so training and serving share
a provider, an id space and a price level by construction — and, critically, it
can use **starRating**, the hotel-class field the 2020 data never had. A metadata
audit showed starRating explains ~42% of log-price variance where guest rating
explains ~5%, and no amount of name-matching could retrofit it onto the old
dataset (19% match coverage). The HISTORICAL path was left exactly as it was.

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

## 2. Data provenance and currency

PromptCloud *"Travel & Hotel Listing from Booking.com 2020"* (Kaggle, **CC0**),
marketing sample `20200301–20200331`, **29,988 source documents** → 165,065
expanded offers → **152,512 cleaned offers / 13,187 hotel keys**.
Crawl dates 2020-03-01…03-24, check-in 2020-03-01…04-07, lead 0–15 days.

**Currency is INR, evidenced by the source itself.** Every one of the 29,988
audited `pageurl`s carries `;selected_currency=INR` in its query string, and
100% are `booking.com/hotel/in/`. This is a property of the data, not an
assumption — the earlier "prototype assumption" wording has been retired.
Recorded in the artifact as `currencyBasis`.

**Training context is proven, not assumed.** Every one of the 29,988 pageurls
carries Booking.com query params showing `checkout − checkin = 1 night`,
`no_rooms=1`, `group_adults=2`, `group_children=0`. Hence
`comparisonBasis = PER_NIGHT_1ROOM_2ADULTS`.

## 3. Architecture

```
KNOWN hotel   (unambiguous normalized name AND >= 5 training offers)
      -> B2 hotel-own historical quantiles     predictionSource = "HISTORICAL"
      -> sorted([P25,P50,P75])
      -> x 1.3707        <- 2020 data, MUST be rebased
      -> decision band -> compare quote

UNSEEN / ambiguous / insufficient history
      -> V3 M2 CatBoost MultiQuantile          predictionSource = "ML"
      -> sorted([P25,P50,P75])
      -> CQR widen, qhat = 0.4207              (ML PATH ONLY)
      -> x 1.0           <- 2026-native, MUST NOT be rebased
      -> decision band -> compare quote
```

**The two paths have different temporal semantics and this is deliberate.**
B2 quantiles are March 2020 prices and are rebased with the MoSPI hotel-lodging
CPI factor. The V3 model was trained on 2026 LiteAPI rates and is already on the
current price level — applying any CPI factor to it would double-count
inflation. Regression tests assert both behaviours.

The B2 half is **unchanged from v1** — its tables are byte-identical, built from
the same cleaned offer table. Only the unseen-hotel half was replaced.

**Quantile sorting is deliberate post-processing, not a hidden fix.** CatBoost
quantile regression crossed on ~0.023% of validation rows; production sorts the
three values before anything else happens to them.

**Conformal calibration is applied to the ML path only.** The HISTORICAL
quantiles are a hotel's own empirical history and were never part of the
conformal procedure, so widening them with M2D's `qhat` would be unjustified.

## 4. Why M1 was replaced

M1 was not broken; it was **inadequate at generalizing to unseen hotels under
live distribution shift**. Its only hotel-discriminative feature was
`hotel_key`, which carries no information for a hotel that was never in
training. The controlled test is unambiguous — four very different unseen
profiles, priced with no access to any current quote:

| profile (Mumbai, same dates/room) | M1 P50 | M2D P50 |
|---|---:|---:|
| premium family, score 9.2, 3,668 reviews | 2,030 | **27,810** |
| budget family, score 7.0, 300 reviews | 2,030 | **3,098** |
| unknown family, score 9.2, 3,668 reviews | 2,030 | **13,427** |
| unknown family, score 6.0, 40 reviews | 2,030 | **2,905** |
| spread | **1.00x** | **9.57x** |

M1 returned the same number for all four. On the live 28-hotel unseen replay
its median `current / adjusted P50` was **5.42x**, with 16 of 28 hotels priced
below INR 3,000 — including five-star properties.

## 5. Features (frozen)

The HISTORICAL path needs no features. The **M2D** unseen-hotel model uses only
attributes observable for *any* hotel, at training time and from a LiteAPI
response alike:

| feature | training source | serving source |
|---|---|---|
| `city` | `pageurl` `dest_id` -> 35 labels | LiteAPI `city`, via frozen alias table |
| `review_score` | `rating_count` (0–10 score) | LiteAPI `rating` |
| `has_review_score` | is the score present | is the rating present and non-zero |
| `log_review_count` | `log1p(review_count)` | `log1p(reviewCount)` |
| `family` | leading n-gram of the name | same rule, same frozen vocabulary |
| `room_category` / `breakfast_category` / `cancellation_category` | PromptCloud room strings | LiteAPI room/board/refundable |
| `lead_time_days`, `ci_month`, `ci_dow`, `ci_weekend`, `cr_dow` | dates | dates |

Excluded, with reasons recorded in the artifact:

| Excluded | Why |
|---|---|
| `hotel_key` | Exact identity is meaningless for an unseen hotel and was the direct cause of the M1 collapse. |
| `occ` | PromptCloud value is the room's **advertised capacity** (1–70); LiteAPI `adultCount` is the **requested party size**. Different quantities. |
| `currentPrice` | Never a feature. It is the thing being judged. |
| `price_rank`, `default_rank` | Rank fields, not prices. |

**Name families are learned from names only.** The vocabulary is the set of
leading 1–3-grams shared by at least 15 distinct training hotels; price is never
consulted, so no brand is hand-labelled premium or budget. Leading articles and
generic property nouns (`the`, `a`, `an`, `at`, `hotel`, `hotels`) are dropped
first — without that step "The Taj Mahal Palace" never reached the `taj` family.
Unmatched names become `UNKNOWN`.

**Missing review scores mean "unknown", never "worst".** LiteAPI reports an
absent rating as `0.0`, not null. In the training data a missing score genuinely
correlates with cheap properties (41% of Budget offers lack one vs 15.6% of
Premium), so a model left to infer from missingness alone pushes unrated hotels
toward the budget prior. At serving time that association does not hold — the
unrated LiteAPI properties are four- and five-star international brands. The
frozen rule substitutes the training median (`7.6`, stored in artifact metadata)
and sets `has_review_score = 0` so the model can distinguish "average" from
"genuinely low".

Training and inference share **one** normalization implementation:
`ml/india_serving_features.py` (the training tree imports it via a shim).

## 6. Interval calibration and temporal rebasing

**Conformal quantile calibration (ML path only).** Raw M2D intervals covered
only ~42% of held-out offers against a 50% target. A normalized conformal
adjustment, `E = max(qlo - y, y - qhi) / (qhi - qlo)`, fitted on a
hotel-group-separated holdout (25% of training hotels, never overlapping the
model fit or the evaluation set), widens the endpoints and leaves P50 untouched.
Coverage moves to **0.493** in grouped CV and 0.500 on the calibration holdout,
for a ~19% increase in interval width.

**Temporal rebasing (both paths).** The training prices are from March 2020.
Predictions are multiplied by **1.3707**, derived from MoSPI's *Hotel Lodging
Charges* CPI item — chained across the 2026 base-year revision:

```
(178.2 / 132.4) x (104.9 / 103.0) = 1.3459 x 1.0184 = 1.3707
 Dec2025/Feb2020      Jul2026/Dec2025
 old item 6.1.04.2.2.07.0 (2012=100)   new item 330 (2024=100)
```

March 2020 itself has no published index — MoSPI suspended price collection
during the COVID lockdown — so the last pre-COVID month is the anchor. On the
HISTORICAL path, where the same hotel can be compared to itself six years later,
this factor explains **90%** of the observed gap (1.43x -> 1.04x).

**The current quote is never adjusted.** Only the model's 2020-basis quantiles
are moved onto a 2026 basis; the live LiteAPI price is compared as-is.

## 7. Decision band

```
BUSINESS_TOLERANCE = 0.15          # product rule, not an ML parameter
decision_low  = min(P25, 0.85 * P50)
decision_high = max(P75, 1.15 * P50)
```

Computed **after** sorting, calibration and rebasing. The band only ever widens,
so `low <= P25 <= P50 <= P75 <= high` holds by construction. The published
values are the ones used for the verdict, so echoing back `decisionLow` always
returns FAIR.

## 8. The one-night probe

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

## 9. Scope guards — the model declines rather than extrapolates

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

## 10. Validation — V3

**Dataset.** LiteAPI-native, collected 2026-08-15 under the frozen contract
(1 room / 2 adults / 0 children / 1 night / INR / `guestNationality=IN`), lead
times 1/3/7/10/14, 12 Indian markets.

| set | hotels | rows | used for |
|---|---:|---:|---|
| TRAIN | 852 | 18,882 | model fitting only |
| CAL | 150 | 2,888 | calibration only, never fitted |
| TEST | 173 | 3,228 | read once, after locking |

**All three hotelId sets are mutually disjoint**, asserted at build time. CAL and
TEST were drawn from hotels never used in any previous experiment and their
manifests were written and hashed *before* any rate was requested.

**starRating is the primary hotel-class signal** — it explains 52.5% of
log-price variance in the V3 dataset (guest rating 5.4%, market alone 3.2%).

**Untouched TEST result**, hotel-balanced (each hotel contributes equal weight,
so one 5-star property with 50 rows cannot outweigh a 1-star with 5):

| | B2 (market x star x room) | V3 M2 |
|---|---:|---:|
| MdAPE | 0.335 | **0.289** |
| pinball | 1219.1 | **1097.3** raw / 1101.2 calibrated |
| median actual / predicted P50 | 1.01 | **0.98** |

**M2 beat a strong B2 baseline by ~13.8% MdAPE and ~10.0% pinball** on the
untouched TEST — development had measured +13.6% / +10.1%, so essentially
nothing shrank. M2 won 4/5 star buckets, 9/12 markets, 5/5 lead times and 6/7
room categories.

`MdAPE 0.289` is a **median absolute percentage error**. It is not an accuracy
figure and must never be reported as "71.1% accurate".

**Interval calibration.** Global conformal widening with qhat = 0.4207 moved
empirical P25-P75 coverage on TEST from **0.329 to 0.549**. P50 is provably
unchanged. This is **group-disjoint empirical calibration**: CAL hotels are
permanently excluded from fitting, but rows are clustered within hotels, so
row-level exchangeability does not hold and **no formal marginal coverage
guarantee is claimed**.

## 10b. Earlier validation (V2.1, historical path)

**Whole-hotel GroupKFold.** Five folds split on `hotel_key`, so every validation
hotel is completely unseen — no hotel ever has some offers in training and
others in validation. One fixed split manifest was shared by every candidate.

| | M1 (v1) | M2D (v2.1) |
|---|---:|---:|
| pinball | 1648.6 | **1324.0** |
| MdAPE | 0.430 | **0.360** |
| Premium MdAPE | 0.583 | **0.377** |
| Premium actual/predicted P50 | 2.37x | **1.42x** |
| P25–P75 coverage (after CQR) | 0.434 | **0.493** |

The **Premium** segment matters most: M1's median predicted P50 for premium
unseen hotels was INR 2,894 against a true level several times higher.

**Live multi-city replay.** 50 real LiteAPI hotels across Mumbai, New Delhi,
Bengaluru, Hyderabad and Chennai; the 28 that take the unseen-hotel path were
replayed against the production artifact using saved quotes:

| | M1 | M2D (production artifact) |
|---|---:|---:|
| median current / adjusted P50 | 5.42x | **0.91x** |
| hotels priced below INR 3,000 | 16 | **0** |
| worst case | 15.01x | **2.13x** |

**Missing-rating MNAR finding.** The five unrated hotels in that set were the
worst outliers under the earlier candidate (median 2.66x). Offline they showed
no bias at all — because in training, missing genuinely does mean cheap. The
mismatch is in the *missingness mechanism*, not the values, and the frozen
imputation rule brings them to **0.99x**. Note these five are all in Bengaluru,
so the live evidence for this specific fix is thin (see limitations).

## 11. Known limitations

1. **One booking date.** The V3 snapshot varies lead time (1-14 days) but not
   the booking date, and carries no seasonal history. It therefore supports
   **current fair-price estimation and bands only — not price-movement
   forecasting.**
2. **Missing-star uncertainty remains a limitation.** The UNRATED-star cell
   reached only 0.320 calibrated coverage against 0.50 nominal; a single global
   qhat under-covers it, and no segment-specific calibration is applied.
3. **LiteAPI inventory and rate availability define the serving population.**
   Rate availability rises monotonically with star class (unrated 11.4% ->
   5-star 81.0%), so the model describes the hotels LiteAPI actually prices —
   not the whole Indian market.
4. **Calibrated TEST coverage is 0.549** against a 0.50 nominal: slightly
   conservative rather than optimistic, but not exact.
5. **173 TEST hotels** is a modest final sample; the Jaipur/Kolkata/Pune
   regressions and Goa's 1.30 ratio may be sample noise.
6. **HISTORICAL-path training observations are from March 2020**, ~6 years old, and
   the tail of that window overlaps the onset of COVID travel disruption in
   India. The temporal factor rebases the level; it cannot restore structure
   that the 2020 market did not have.
2. **The temporal adjustment is a single national figure.** It uses MoSPI's
   all-India lodging CPI, so city-specific inflation differences remain
   uncorrected. Same-hotel evidence suggests Bengaluru ran materially hotter
   (~1.64x residual) than Chennai/Mumbai/Delhi/Hyderabad (0.98–1.11x), **but
   that rests on only two hotels — far too little to justify a city multiplier,
   which is why none is applied.**
3. **LiteAPI live/sandbox inventory does not match the historical Booking.com
   distribution.** Training is dominated by budget properties (median INR
   3,225); the live inventory is weighted toward four- and five-star chains.
4. **The missing-rating fix is validated on five live hotels, all in
   Bengaluru** — geographically confounded, and thin evidence.
5. **This remains an India-market prototype, not a global pricing model.**
6. **Lead time 0–15 days only** — no long-horizon bookings in training, and
   requests outside that window are declined (`UNSUPPORTED_LEAD_TIME`), so the
   feature simply does not cover advance planning.
7. Hotel matching is **exact normalized name**; 191 names (1.45%) are ambiguous
   and are routed to ML rather than guessed. No fuzzy matching.
8. `room_category` is a coarse 8-class mapping; it cannot distinguish
   "Deluxe King" from "Deluxe Double".
9. Name families cover ~36% of hotels; the rest fall back to `UNKNOWN` and are
   priced from city, review score and review volume alone.

## 12. Integration note (must not be lost at merge)

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
