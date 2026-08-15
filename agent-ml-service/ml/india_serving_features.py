"""
Serving-parity feature normalization — SHARED by training and inference.
========================================================================
Every function here must be called identically at training time and at
production inference time. That is the whole point of this module: the
previous M1 failed its serving audit because training features could not be
reproduced from a LiteAPI response.

Frozen contract (do not extend without a new approval):

    hotel_key             normalize_name(hotel name)
    room_category         STANDARD|DELUXE|SUPERIOR|SUITE|APARTMENT|FAMILY|DORM|OTHER
    breakfast_category    ROOM_ONLY|BREAKFAST|HALF_BOARD|FULL_BOARD|ALL_INCLUSIVE|UNKNOWN
    cancellation_category REFUNDABLE|NON_REFUNDABLE|UNKNOWN
    lead_time_days        check_in - booking/crawl date
    ci_month, ci_dow, ci_weekend, cr_dow

`occ` is deliberately ABSENT: PromptCloud's value is the room's advertised
capacity while LiteAPI's adultCount is the requested party size. They are not
the same quantity, so the feature cannot be served honestly.
"""
from __future__ import annotations

import re
import unicodedata

# ---------------------------------------------------------------- hotel key
def normalize_name(s) -> str:
    """NFKC -> casefold -> punctuation to spaces -> collapse whitespace -> trim."""
    s = unicodedata.normalize("NFKC", str(s or ""))
    s = s.casefold()
    s = re.sub(r"[^\w\s]", " ", s)
    return re.sub(r"\s+", " ", s).strip()


# ---------------------------------------------------------------- room type
# Frozen category set and keyword priority (first match wins). Priority order
# matters: "deluxe suite" must resolve to SUITE, "family apartment" to
# APARTMENT, etc. Rules were fixed during the serving audit and must NOT be
# adjusted in response to validation metrics.
ROOM_CATEGORIES = ("STANDARD", "DELUXE", "SUPERIOR", "SUITE",
                   "APARTMENT", "FAMILY", "DORM", "OTHER")
_ROOM_RULES = (
    ("SUITE",     r"\bsuite\b"),
    ("APARTMENT", r"apartment|studio|\bflat\b|villa|cottage|chalet|bungalow"),
    ("DORM",      r"\bdorm\b|dormitory|shared|\bbunk\b|hostel|\bbed in\b"),
    ("FAMILY",    r"\bfamily\b"),
    ("DELUXE",    r"deluxe|luxury|premium|\bpremier\b"),
    ("SUPERIOR",  r"superior|executive|\bclub\b|\bgrand\b"),
    ("STANDARD",  r"standard|classic|basic|economy|budget|comfort|"
                  r"\bdouble\b|\btwin\b|\bsingle\b|\bqueen\b|\bking\b|\btriple\b|\broom\b"),
)


def room_category(name) -> str:
    """Coarse room class shared by PromptCloud strings and LiteAPI room names."""
    s = normalize_name(name)
    if not s:
        return "OTHER"
    for label, pat in _ROOM_RULES:
        if re.search(pat, s):
            return label
    return "OTHER"


# ---------------------------------------------------------------- breakfast
BREAKFAST_CATEGORIES = ("ROOM_ONLY", "BREAKFAST", "HALF_BOARD",
                        "FULL_BOARD", "ALL_INCLUSIVE", "UNKNOWN")
_BF_TRAIN = {
    "room only": "ROOM_ONLY", "breakfast": "BREAKFAST",
    "half_board": "HALF_BOARD", "full_board": "FULL_BOARD",
    "all_inclusive": "ALL_INCLUSIVE",
}


def breakfast_category(value) -> str:
    """PromptCloud `room_type_breakfast` -> frozen taxonomy."""
    s = normalize_name(value).replace(" ", "_")
    if s in _BF_TRAIN:
        return _BF_TRAIN[s]
    s2 = normalize_name(value)
    if s2 in _BF_TRAIN:
        return _BF_TRAIN[s2]
    return "UNKNOWN"          # covers PromptCloud 'lunch'/'dinner' and blanks


def breakfast_category_from_liteapi(board_type=None, board_name=None) -> str:
    """LiteAPI boardType/boardName -> the SAME frozen taxonomy."""
    bt = (str(board_type or "")).strip().upper()
    by_code = {"RO": "ROOM_ONLY", "BI": "BREAKFAST", "BB": "BREAKFAST",
               "HB": "HALF_BOARD", "FB": "FULL_BOARD", "AI": "ALL_INCLUSIVE"}
    if bt in by_code:
        return by_code[bt]
    n = normalize_name(board_name)
    if "all inclusive" in n:
        return "ALL_INCLUSIVE"
    if "half board" in n:
        return "HALF_BOARD"
    if "full board" in n:
        return "FULL_BOARD"
    if "breakfast" in n:
        return "BREAKFAST"
    if "room only" in n:
        return "ROOM_ONLY"
    return "UNKNOWN"


# ------------------------------------------------------------- cancellation
CANCELLATION_CATEGORIES = ("REFUNDABLE", "NON_REFUNDABLE", "UNKNOWN")
_CANC_TRAIN = {"free_cancellation": "REFUNDABLE",
               "non_refundable": "NON_REFUNDABLE",
               "special_condition": "UNKNOWN"}


def cancellation_category(value) -> str:
    """PromptCloud `room_type_cancellation` -> frozen taxonomy."""
    return _CANC_TRAIN.get(normalize_name(value).replace(" ", "_"), "UNKNOWN")


def cancellation_category_from_liteapi(refundable_tag) -> str:
    """LiteAPI refundableTag -> the SAME frozen taxonomy."""
    t = (str(refundable_tag or "")).strip().upper()
    return {"RFN": "REFUNDABLE", "NRFN": "NON_REFUNDABLE"}.get(t, "UNKNOWN")


# ------------------------------------------------------------------ calendar
def calendar_features(check_in, booking_date) -> dict:
    """lead_time_days + check-in calendar + booking day-of-week."""
    import pandas as pd
    ci = pd.Timestamp(check_in).normalize()
    cr = pd.Timestamp(booking_date).normalize()
    return {"lead_time_days": int((ci - cr).days),
            "ci_month": int(ci.month), "ci_dow": int(ci.dayofweek),
            "ci_weekend": int(ci.dayofweek >= 5), "cr_dow": int(cr.dayofweek)}


FEATURES = ["hotel_key", "room_category", "breakfast_category",
            "cancellation_category", "lead_time_days",
            "ci_month", "ci_dow", "ci_weekend", "cr_dow"]
CATEGORICAL = ["hotel_key", "room_category", "breakfast_category",
               "cancellation_category"]


# =====================================================================
# V2.1 — unseen-hotel (M2D) serving features
# =====================================================================
# The V1 fallback keyed on `hotel_key`, which carries no information for a
# hotel that was never in training, so every unseen property collapsed to one
# global prior. M2D replaces it with attributes that ARE observable for any
# hotel, at training time and from a LiteAPI response alike.
#
# `hotel_key` is deliberately absent from V21_FEATURES: exact hotel identity
# must not drive the unseen-hotel fallback.

V21_FEATURES = ["family", "city", "review_score", "has_review_score",
                "log_review_count", "room_category", "breakfast_category",
                "cancellation_category", "lead_time_days",
                "ci_month", "ci_dow", "ci_weekend", "cr_dow"]
V21_CATEGORICAL = ["family", "city", "room_category", "breakfast_category",
                   "cancellation_category"]

FAMILY_UNKNOWN = "UNKNOWN"
CITY_UNKNOWN = "UNKNOWN"
_FAMILY_MAX_N = 3

# Articles and the most generic property nouns only. Deliberately SHORT: an
# aggressive list would destroy real Indian chains ("The Park", "The Leela",
# "The Fern"). Without this step "The Taj Mahal Palace" never reached the
# `taj` family, because every leading n-gram still began with "the".
_FAMILY_LEAD_DROP = {"the", "a", "an", "at", "hotel", "hotels"}


def _family_tokens(name) -> list:
    tk = [t for t in normalize_name(name).split() if t]
    i = 0
    while i < len(tk) - 1 and tk[i] in _FAMILY_LEAD_DROP:   # never strip to empty
        i += 1
    return tk[i:]


def family_candidates(name) -> list:
    """Leading 1..3-grams of the de-articled name, LONGEST FIRST.

    Longest-first is what makes multi-token families work: "Capital O 123"
    matches `capital o` rather than a bare `capital`.
    """
    tk = _family_tokens(name)
    return [" ".join(tk[:n]) for n in range(min(_FAMILY_MAX_N, len(tk)), 0, -1)]


def family(name, vocabulary) -> str:
    """Deterministic name-family. `vocabulary` is frozen in the artifact.

    The vocabulary was learned from training hotel NAMES ONLY (n-grams shared
    by >= 15 distinct hotels); price was never consulted, so no brand is
    hand-labelled premium or budget - the model learns any price association
    on its own.
    """
    vocab = vocabulary or ()
    for gram in family_candidates(name):          # longest first
        if gram in vocab:
            return gram
    return FAMILY_UNKNOWN


def city_key(city) -> str:
    """Normalized city token used on both sides of the pipeline."""
    return normalize_name(city)


def serving_city(city, city_aliases) -> str:
    """LiteAPI city string -> the city label space learned from dest_id.

    `city_aliases` is frozen in the artifact (normalized alias -> label), so
    "New Delhi" and "Bangalore" resolve to the same labels the model trained
    on. Unmapped cities become UNKNOWN rather than silently inventing a level.
    """
    return (city_aliases or {}).get(city_key(city), CITY_UNKNOWN)


def review_features(rating, review_count, imputation_value) -> dict:
    """Frozen missing-review semantics.

    LiteAPI reports an absent rating as None OR as 0.0. Both mean "unknown",
    never "worst hotel in India". In the training data a missing score really
    does correlate with cheap properties (41% of Budget offers lack one, vs
    15.6% of Premium), so a model left to infer from missingness alone pushes
    unrated hotels toward the budget prior. At serving time that association
    does not hold - the unrated LiteAPI properties are 4-5 star international
    brands. We therefore substitute the frozen training median and flag the
    substitution, so the model can tell "average" from "genuinely low".
    """
    has = 1 if (rating is not None and float(rating) > 0) else 0
    score = float(rating) if has else float(imputation_value)
    try:
        rc = float(review_count) if review_count is not None else None
    except (TypeError, ValueError):
        rc = None
    import math
    return {"review_score": score,
            "has_review_score": has,
            "log_review_count": math.log1p(rc) if rc and rc > 0 else float("nan")}


# =====================================================================
# V3 — frozen M2 unseen-hotel features (LiteAPI-native, 2026 snapshot)
# =====================================================================
# Reproduced verbatim from the frozen experiment spec
# (experiments/liteapi_v3/reference/m2_frozen_spec.json). Feature ORDER is part
# of the contract - CatBoost is fed a DataFrame in exactly this order.
#
# `hotelId` is absent by design, and no current/comparable price ever enters
# this dict: the quote is compared to the prediction afterwards.

V3_FEATURES = ["market", "star_bucket", "stars_num", "room_category",
               "board_category", "cancellation_category", "leadTimeDays",
               "guest_rating", "has_guest_rating", "log_review_count",
               "chain", "hotelTypeId", "n_facilities"]
V3_CATEGORICAL = ["market", "star_bucket", "room_category",
                  "board_category", "cancellation_category", "chain"]

STAR_BUCKETS = ("1-2", "3", "4", "5", "UNRATED")
CHAIN_UNKNOWN = "NA"


def star_bucket(stars) -> str:
    """UNRATED / 1-2 / 3 / 4 / 5 - frozen bucketing.

    LiteAPI reports an absent class as null OR 0; both mean UNRATED, never
    'one star'.
    """
    try:
        s = float(stars)
    except (TypeError, ValueError):
        return "UNRATED"
    if s <= 0:
        return "UNRATED"
    return "1-2" if s <= 2 else ("3" if s < 4 else ("4" if s < 5 else "5"))


def stars_num(stars) -> float:
    """Numeric companion to star_bucket; -1 encodes 'no class published'."""
    try:
        s = float(stars)
    except (TypeError, ValueError):
        return -1.0
    return s if s > 0 else -1.0


def chain_category(chain) -> str:
    """LiteAPI emits null, '' or the literal 'Not Available' for no chain."""
    c = (str(chain or "")).strip()
    return CHAIN_UNKNOWN if (not c or c.casefold() == "not available") else c


def v3_features(*, market, stars, rating, review_count, chain, hotel_type_id,
                facility_ids, room_name, board_type, board_name, refundable_tag,
                lead_time_days) -> dict:
    """Assemble the frozen M2 feature row from a LiteAPI profile + offer.

    Every transform here matches the experiment: log1p on review count,
    len() on facilityIds (the raw 482-dim list is deliberately NOT used), and
    NaN - not a sentinel low value - for a genuinely absent guest rating.
    """
    import math
    has_rating = 1 if (rating is not None and _f(rating) > 0) else 0
    rc = _f(review_count)
    return {
        "market": str(market or CITY_UNKNOWN),
        "star_bucket": star_bucket(stars),
        "stars_num": stars_num(stars),
        "room_category": room_category(room_name),
        "board_category": breakfast_category_from_liteapi(board_type, board_name),
        "cancellation_category": cancellation_category_from_liteapi(refundable_tag),
        "leadTimeDays": int(lead_time_days),
        "guest_rating": _f(rating) if has_rating else float("nan"),
        "has_guest_rating": has_rating,
        "log_review_count": math.log1p(rc) if rc and rc > 0 else float("nan"),
        "chain": chain_category(chain),
        "hotelTypeId": int(_f(hotel_type_id) or 0),
        "n_facilities": len(facility_ids or []),
    }


def _f(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0
