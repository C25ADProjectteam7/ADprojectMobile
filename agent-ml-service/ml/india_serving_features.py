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
