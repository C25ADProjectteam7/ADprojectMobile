# Hotel Price Dataset Shortlist

Research only at the time this document was written — no data downloaded in
bulk, no scraping performed, no model trained, no code changed. This document
evaluates candidate datasets against the fields already defined in
`HotelPriceRequest`/`HotelPriceResponse` (`agent-ml-service/ml/schemas.py`)
and the criteria in `docs/ml/hotel-price-dataset-requirements.md`.

**Update (2026-08-11):** The Primary candidate below was downloaded, loaded,
and used to train a first baseline. Results, the confirmed V1 feature
decision, and real data-quality findings (not just paper-reading) are in
`docs/ml/hotel-price-baseline-results.md`. The recommendation below held up
against real data — city/star_rating are genuinely absent, not just
under-documented.

**Verification method note:** each candidate was checked against its primary
source where one exists — a peer-reviewed data article, the platform's own
challenge/rules page, or the dataset's source code/repository — not just
search-engine summaries. One exception is flagged explicitly below (Candidate
4): Kaggle's dataset pages are JavaScript-rendered and could not be fetched
directly, so that entry relies on search-result snippets only and is marked
lower-confidence for that reason.

---

## Selection Criteria

Derived from `docs/ml/hotel-price-dataset-requirements.md` and the live API contract:

- Real per-booking or per-search **price** field usable as a `price_per_night` proxy
- **Date** information usable for a genuine time-based train/val/test split (not just a static snapshot)
- Fields that plausibly map to `city`, `hotel_star_rating`, `room_type`, `number_of_guests`, `check_in_date`/`check_out_date`, `booking_date`/lead time
- A stated, checkable **license** that permits at least non-commercial academic/project use
- Enough rows to support a meaningful time-based split (not a handful of records)
- No obvious data-leakage structure (e.g., post-outcome fields mixed in with pre-booking fields)

---

## Candidate Comparison Table

| # | Dataset | License | Size | Geographic Coverage | Time Period | Price Target | Verification Confidence |
|---|---|---|---|---|---|---|---|
| 1 | Hotel Booking Demand (Antonio, Almeida, Nunes, 2019) | CC BY (open access, Data in Brief) | 119,390 bookings (H1: 40,060 + H2: 79,330) | 2 hotels, Portugal only (Algarve resort + Lisbon city) | Arrivals 2015-07-01 to 2017-08-31 | ADR (Average Daily Rate) | High — verified via peer-reviewed article full text |
| 2 | Personalize Expedia Hotel Searches (ICDM 2013, Kaggle) | Kaggle competition data — restricted to competition/research use, no ownership/redistribution rights granted | ~9.9M training impressions, 399,344 search lists | Anonymized country/destination IDs — no real city names | Historical (exact years not confirmed) | `price_usd` (ambiguous: per-night vs. total not confirmed) | Medium — fields verified via multiple secondary academic sources, not the raw file itself |
| 3 | Trivago RecSys Challenge 2019 | Non-exclusive, **non-commercial research use only** | 927,142 items (hotels), 981,655 users, 1,202,064 sessions, 19.7M actions | Multi-city (global), but city is *search destination*, not a clean city field per row | Not confirmed from sources reviewed | Pipe-separated impression prices (per-night vs. total not documented in sources reviewed) | Medium — license/size verified via official challenge page and source code, but no check-in dates or room type confirmed present |
| 4 | TBO Hotels Dataset (Kaggle, `raj713335`) | Not stated / unclear — page description mentions "collected from hotel websites, OTAs, review platforms" | 1,000,000+ rows, 16 columns, **one row per hotel** (not per stay) | Multi-country (claimed) | Not applicable — static catalog, no date field | Unclear — likely a static "rate" field, not a date-varying price | **Low** — Kaggle's page could not be fetched directly; based on search-snippet summaries only |

---

## Candidate 1: Hotel Booking Demand (Antonio, Almeida & Nunes, 2019)

- **Dataset name:** Hotel booking demand datasets
- **Official/source page:** Antonio, N., de Almeida, A., & Nunes, L. (2019). *Hotel booking demand datasets*. Data in Brief, 22, 41–49. DOI: [10.1016/j.dib.2018.11.126](https://doi.org/10.1016/j.dib.2018.11.126). Open-access full text mirrored on PubMed Central: [PMC6297060](https://pmc.ncbi.nlm.nih.gov/articles/PMC6297060/). Also widely mirrored on Kaggle (`jessemostipak/hotel-booking-demand`) — convenient for download, but the journal article is the authoritative source for fields/license.
- **License / permitted usage:** Published as an **open access article under the CC BY license** (per the article's own statement). This is the most permissive license of any candidate reviewed — no restriction to competition/research-only use.
- **Data size:** 119,390 total bookings — H1 (resort hotel, Algarve): 40,060; H2 (city hotel, Lisbon): 79,330. 31 variables per row.
- **Geographic coverage:** Exactly 2 hotels, both in Portugal. **Not multi-city** — this is the dataset's central limitation for our use case.
- **Time period:** Bookings with arrival dates from 2015-07-01 to 2017-08-31; reservation status dates extend from late 2014 to September 2017.
- **Price target:** ADR (Average Daily Rate) — industry-standard per-night revenue metric, the closest available proxy for `predicted_price_per_night`.
- **Fields present:**
  - ✅ Date-related: `lead_time` (days between booking and arrival, integer), `arrival_date_year`/`arrival_date_month`/`arrival_date_day_of_month`, `reservation_status_date`
  - ✅ Stay duration: `stays_in_weekend_nights` + `stays_in_week_nights` (sums to nights)
  - ✅ Guests: `adults`, `children`, `babies` (sum to approximate `number_of_guests`)
  - ✅ Room type: `reserved_room_type`, `assigned_room_type` (letter codes, e.g., A/B/C/D — not our `single`/`double`/`twin`/`suite` enum, needs a mapping decision)
  - ❌ **City:** not present as a variable — only 2 fixed properties, not a general location feature
  - ❌ **Star rating:** not present at all
  - ❌ **Currency:** not stated explicitly (ADR is presumably EUR, since both hotels are in Portugal; not confirmed in the article text reviewed)
  - ❌ **Explicit booking date:** not a direct field, but derivable as `arrival_date − lead_time`
- **Suitable for time-based split:** Yes — spans over 2 years, has real calendar dates, explicitly recommended in the source article's discussion of typical uses (revenue management / ML).
- **Data quality risks:** The authors themselves note that "the distribution of some variables differ between non-canceled and canceled bookings" (e.g., nationality often only known at check-in) — canceled vs. completed bookings need to be handled deliberately, not pooled naively.
- **Data leakage risks:** `reservation_status` and `reservation_status_date` describe the *outcome* of the booking (checked-out / canceled / no-show) and must be excluded from a price-prediction feature set — they are determined after or alongside the price, not before it.
- **Match to `HotelPriceRequest`:** Partial — strong on dates/guests/stay-length/room-type-as-category, but has **no city field and no star rating field**, which are two of the eight input fields our API accepts today.
- **Usage difficulty:** Low — single CSV-equivalent structure per hotel, well-documented, no account/terms-of-service gate to download.
- **Main limitation:** Only 2 properties in 1 country. A model trained on this data cannot actually learn how price varies by city or star rating — those inputs would have to be dropped or treated as fixed/ignored for a first baseline, which does not match what the current mock predictor already does (city- and star-rating-sensitive pricing).

---

## Candidate 2: Personalize Expedia Hotel Searches — ICDM 2013 (Kaggle)

- **Dataset name:** Personalize Expedia Hotel Searches — ICDM 2013 (a.k.a. "Expedia personalized sort")
- **Official/source page:** [kaggle.com/c/expedia-personalized-sort](https://www.kaggle.com/c/expedia-personalized-sort) (competition data page; requires a free Kaggle account and acceptance of competition rules to download)
- **License / permitted usage:** Governed by Kaggle competition rules, not an open data license. Confirmed via Kaggle's competition-IP guidance: participants "may not use data other than the Competition Data" and participation "shall not be construed as having or being granted a license under, or any right of ownership in, any of the data." This is a **real restriction** — using this data outside the original competition context (e.g., in a team project deliverable) is legally murkier than the other candidates, and should not be assumed permitted without further reading of the specific competition's full rules page.
- **Data size:** ~9,917,530 training rows across 399,344 unique search result lists (per academic sources describing the dataset, e.g., arXiv:1311.7679).
- **Geographic coverage:** Global search traffic, but locations are represented as **anonymized numeric IDs** (`visitor_location_country_id`, `prop_country_id`, `srch_destination_id`) — no human-readable city names are released.
- **Time period:** Historical Expedia search logs; exact calendar range not confirmed in the sources reviewed this session.
- **Price target:** `price_usd` — the displayed price at search time. **Not confirmed** whether this represents per-night or total-stay price; this would need to be checked against the actual data dictionary before use, not assumed.
- **Fields present:**
  - ✅ Star rating: `prop_starrating` (numeric — closer to a direct match than any other candidate)
  - ✅ Guests: `srch_adults_count`, `srch_children_count`
  - ✅ Stay length / lead time: `srch_length_of_stay`, `srch_booking_window` (booking window = lead time in days)
  - ✅ Currency: prices normalized to USD by construction — actually matches our current USD-only restriction directly
  - ❌ **City:** no real city names — only opaque destination/country IDs
  - ❌ **Room type:** no explicit single/double/twin/suite-style field found in the sources reviewed (only `srch_room_count`, the number of rooms searched, not a type)
- **Suitable for time-based split:** Plausible (has `date_time` for the search event) but not independently confirmed this session.
- **Data quality risks:** Academic sources note the competition data was filtered/transformed (e.g., log-transforming `price_usd` and `srch_booking_window`, restricting price to $10–$1,000) before being released — meaning the raw file may already reflect someone else's cleaning decisions that would need to be understood, not blindly trusted.
- **Data leakage risks:** `click_bool`/`booking_bool` (whether a result was clicked/booked) are outcome labels for the original ranking task, not inputs — must be excluded from a price-prediction feature set to avoid leaking the original competition's target into ours.
- **Match to `HotelPriceRequest`:** Better than Candidate 1 on star rating and currency, but **loses on city (no real names) and room type (not present)** — and the license question is a genuine blocker to resolve before committing to this dataset, not just a formality.
- **Usage difficulty:** Medium — requires a Kaggle account, competition rules acceptance, and legal clarity on reuse before this is safe to build a real deliverable on.
- **Main limitation:** The license restriction and the anonymized-city problem are both serious; this is why it's ranked Fallback, not Primary.

---

## Candidate 3: Trivago RecSys Challenge 2019 Dataset

- **Dataset name:** ACM RecSys Challenge 2019 dataset (trivago)
- **Official/source page:** [recsys.trivago.cloud/challenge/dataset/](https://recsys.trivago.cloud/challenge/dataset/) (requires account + terms acceptance to download); described academically in Adamczak et al., "Session-based Hotel Recommendations Dataset," ACM (DOI 10.1145/3412379).
- **License / permitted usage:** Confirmed via search of the official challenge terms: trivago grants "a non-exclusive license to use the Data Set solely for the purposes of research and development of data science for **non-commercial purposes**." This rules out any commercial use and should be treated as research-only.
- **Data size:** 927,142 items (accommodations), 981,655 users, 1,202,064 sessions, 19,715,327 total user actions.
- **Geographic coverage:** Global (multi-city search sessions), but confirmed fields center on `city` as a *search-destination* context field, not a structured per-row hotel location suitable for direct feature use the way our `city` field is used.
- **Time period:** Not confirmed from the sources reviewed this session.
- **Price target:** `prices` — a pipe-separated list of prices for the items shown in a single impression (i.e., prices are attached to a *search result set*, not a single stay). Whether these are per-night or total, and in what currency, was **not documented** in any source reviewed.
- **Fields present (confirmed via the dataset's own preprocessing code on GitHub):** `session_id`, `user_id`, `step`, `action_type`, `reference`, `timestamp`, `impressions`, `prices`, `city`, `platform`, `device`, `current_filters`; item metadata has `item_id` and a `properties` list (may include a "X Star" tag, inconsistently).
  - ❌ **No check-in/check-out date fields**
  - ❌ **No stay-length or lead-time field**
  - ❌ **No number-of-guests field**
  - ❌ **No structured room-type field**
  - ⚠️ Star rating only appears as an inconsistent free-text property tag, not a clean numeric column
- **Suitable for time-based split:** Only at the session/click level (via `timestamp`), not at the stay level — there is no "date of stay" to split on at all.
- **Data quality risks:** Designed for a *clickstream/ranking* task, not a price-regression task — repurposing it means treating something it wasn't built for.
- **Data leakage risks:** `action_type`/`reference`/click sequence encode the original recommendation task's outcome; not directly relevant if only `prices`/`item_metadata` are used, but easy to accidentally leak session outcome signals in if reused carelessly.
- **Match to `HotelPriceRequest`:** Weak — missing 4 of 8 input fields entirely (dates, lead time, guests, room type).
- **Usage difficulty:** Medium-high — account + non-commercial terms required, and the data would need substantial reshaping to even resemble a per-stay pricing dataset.
- **Main limitation / reason for rejection:** Structurally the wrong shape for this task (built for click ranking, not price-per-stay regression) and legally restricted to non-commercial research.

---

## Candidate 4: TBO Hotels Dataset (Kaggle, `raj713335`)

- **Dataset name:** "Hotels Dataset" / TBO Hotels Dataset
- **Official/source page:** [kaggle.com/datasets/raj713335/tbo-hotels-dataset](https://www.kaggle.com/datasets/raj713335/tbo-hotels-dataset) — **could not be fetched directly this session** (Kaggle's dataset pages are JavaScript-rendered; the fetch tool only returned the page title). Everything below comes from search-engine result snippets, not a direct read of the dataset card — flagged as lower confidence for exactly this reason.
- **License / permitted usage:** Not confirmed — no explicit license text was found in the snippets reviewed.
- **Data size:** Reported as 1,000,000+ rows, 16 columns.
- **Geographic coverage:** Reported as multi-country (has `countryCode`/`countryName` columns), not independently verified further.
- **Time period:** Not applicable in the way the other candidates are — **this appears to be one row per hotel** (a catalog/snapshot), not one row per stay or per date.
- **Price target:** Unclear from the snippets reviewed — likely a single "rate" field per hotel, not a date-varying price.
- **Fields present:** Only `countryCode`/`countryName` were confirmed from snippets; the dataset description mentions "rates, reviews, amenities, location, and star rating" in general terms but the full 16-column list was not retrievable this session.
- **Suitable for time-based split:** **No** — if it is genuinely one row per hotel with no date dimension, there is nothing to split on chronologically, which fails a hard requirement in `docs/ml/hotel-price-dataset-requirements.md`.
- **Data quality risks:** Description states data was "collected from various sources, such as hotel websites, online travel agencies, and review platforms" — this phrasing suggests aggregation/scraping from multiple third-party sites, which raises both data-consistency and legality-of-source questions that would need real investigation before trusting it.
- **Data leakage risks:** Not assessable without seeing the actual columns.
- **Match to `HotelPriceRequest`:** Cannot be properly assessed without the full column list — this itself is disqualifying for a project with a Friday deadline.
- **Usage difficulty:** Unknown.
- **Main limitation / reason for rejection:** Two independent, serious problems — (1) apparently no per-stay date dimension at all, which breaks the time-based split requirement outright, and (2) unclear/unverified provenance and license. Either one alone would be enough to deprioritize it; both together make it a clear reject rather than a "needs more digging" candidate.

---

## Recommendation

### Primary: Candidate 1 — Hotel Booking Demand (Antonio, Almeida & Nunes, 2019)

**Why this is the best fit for a first-version baseline:**
- It is the only candidate with a fully open, unambiguous license (CC BY) — no account gate, no competition-rules ambiguity, no non-commercial restriction to manage.
- It has a genuine per-night price target (ADR), genuine calendar dates, and enough time span (2+ years) to do a real time-based split, which is the single hardest requirement in `docs/ml/hotel-price-dataset-requirements.md` to satisfy with a "convenient" dataset.
- It's peer-reviewed and widely used academically, so its structure and known quirks (the canceled-vs-completed distribution difference the authors themselves flag) are well understood, not a surprise waiting to happen mid-sprint.

**Which current API fields exist in the dataset:** stay duration (nights), lead time (→ derivable booking date), number of guests, room type (as a category needing remapping).

**Which fields do not exist:** `city` (only 2 fixed properties), `hotel_star_rating` (absent entirely), `currency` (unstated, presumed EUR).

**Does the feature set need adjusting?** Yes. A first baseline trained on this data would necessarily predict ADR as a function of lead time, stay length, room-type category, guest count, and season/month — **not** as a function of city or star rating, because the dataset cannot supply either. This is a real gap between what the mock API's contract implies (`city` and `hotel_star_rating` both influence the predicted price) and what a first real model trained on this data could actually do.

**Can this be trained without changing the API Contract?** The request/response *field names* in `schemas.py` would not need to change. But there is a meaningful honesty question underneath that: if `city` and `hotel_star_rating` are accepted by the API but ignored by the real model internally, that needs to be disclosed the same way `is_mock` discloses the current mock's limitations today — swapping in a real model that silently ignores two of its own input fields would be a worse failure mode than the current, honestly-labeled mock.

**Would synthetic/mock data be needed to supplement this?** Not decided here — this is a real open question worth raising at the meeting (e.g., whether to scope city/star-rating support out of v1 entirely, or look for a second multi-city dataset to combine later), not something to resolve unilaterally in a research document.

**Biggest risk:** Training a model that looks authoritative (`model_status: "trained"`) while actually only reflecting two Portuguese hotels' pricing dynamics — the risk isn't technical failure, it's **overclaiming generalization the data doesn't support**, which is exactly the kind of mistake this whole project has been careful to avoid with the mock so far.

### Fallback: Candidate 2 — Personalize Expedia Hotel Searches (ICDM 2013)

Larger scale and has real star ratings and USD-normalized pricing, which Candidate 1 lacks — but the anonymized city IDs and the unresolved competition-license question make it a worse starting point than Candidate 1 unless those two issues get specifically resolved first (confirm license terms in full, and decide whether opaque destination IDs are an acceptable substitute for `city`).

### Rejected / Lower Priority

- **Candidate 3 (Trivago RecSys 2019):** wrong data shape for this task (click-ranking dataset, not a per-stay pricing dataset) — missing dates, lead time, guests, and room type entirely.
- **Candidate 4 (TBO Hotels Dataset):** likely no date dimension at all (fails the time-based split requirement outright) and unverified/unclear provenance and license.

---

## Proposed Baseline Plan

**Design only — nothing below has been executed.**

- **Target:** `adr` (Average Daily Rate) from Candidate 1, used as the training proxy for `predicted_price_per_night`. `predicted_total_price` continues to be derived as `predicted_price_per_night × nights`, consistent with how the current mock already computes it.
- **Features (v1, given what the dataset can actually supply):** `lead_time`, `arrival_date_month` (season), total nights (`stays_in_weekend_nights + stays_in_week_nights`), total guests (`adults + children + babies`), `reserved_room_type`, `market_segment`, `deposit_type`, and a coarse `hotel` indicator (H1 vs. H2) as the only available location signal. **Explicitly excludes** `city` and `hotel_star_rating` as true predictive features in v1, per the gap identified above.
- **Preprocessing:** reconstruct a real `arrival_date` from the year/month/day components; derive `booking_date = arrival_date − lead_time`; encode `reserved_room_type`/`market_segment`/`hotel` categorically; filter out canceled bookings for the initial price model (cancellation dynamics are a separate question from pricing dynamics, and pooling them without a deliberate decision risks the exact distribution-shift issue the original authors warned about); investigate and handle any zero/negative/extreme-outlier `adr` rows before training, not after.
- **Train / validation / test split:** strictly time-based on `arrival_date` — earliest ~70% chronologically for train, next ~15% for validation, most recent ~15% for test. No shuffling across the boundary, per the reasoning already documented in `docs/ml/hotel-price-dataset-requirements.md`.
- **Baseline model progression:** Linear Regression first (sanity-check lower bound) → Random Forest Regressor → XGBoost, matching the candidates already named in the dataset requirements doc.
- **Evaluation metrics:** MAE, RMSE, MAPE — same three already specified in the dataset requirements doc, computed on the held-out time-based test split only.
- **Definition of Done:** a trained baseline model with MAE/RMSE/MAPE reported on a genuine time-based split, **plus** a written, explicit disclosure (in code comments, the API contract doc, and the response `message` field) that this first real model does not use `city` or `hotel_star_rating` as predictive inputs. `model_status` should only move from `"mock"` to `"trained"` once that disclosure exists — not simply once a `.joblib` file exists.

---

## 45-Second Meeting Update

> "I reviewed four real candidate datasets against our actual API schema, checking each one's own official page or paper rather than just search summaries. The best fit is the Antonio/Almeida/Nunes 'Hotel Booking Demand' dataset — fully open CC-BY license, about 120,000 real bookings across two Portugal hotels, with genuine dates, lead time, room type, and guest counts, so we could finally do a proper time-based split instead of the mock formula. The catch: it only covers two hotels in one country, so it has no city field and no star-rating field — meaning a first real model trained on it would predict lead-time and seasonality patterns well, but would not actually generalize across city or star rating the way our API currently implies, and we'd need to disclose that honestly rather than pretend it's solved. Fallback is the Expedia ICDM 2013 dataset — bigger, has real star ratings — but its city data is anonymized IDs and its license is competition-restricted, so it's a weaker legal and structural fit. Next step, if the team agrees, is a scoped baseline on the Hotel Booking Demand data with a real time-based split, while we explicitly scope city and star-rating support out of v1."
