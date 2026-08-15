"""
Promote the FROZEN V3 M2 candidate into a production artifact — NO REFIT
========================================================================
This script does not train anything. It copies the exact CatBoost binary that
was calibrated on CAL and evaluated on TEST, verifies its SHA256 against the
frozen candidate, and packages it with the metadata the predictor needs.

    models/hotel_price_india_v3_m2.cbm      byte-identical copy of m2_final.cbm
    models/hotel_price_india_v3_m2.joblib   B2 tables + qhat + schema + metrics

The HISTORICAL B2 tables are copied VERBATIM from the v2.1 bundle (which was
itself verified byte-identical to v1) and are asserted equal here. They are
never rebuilt from the V3 dataset - the historical path is unchanged.

V1 and V2.1 artifacts are opened read-only and never written.

Run: .venv/bin/python training/v2mvp_india/build_artifact_v3.py
"""
from __future__ import annotations

import hashlib
import json
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT))
EXP = ROOT / "training/v2mvp_india/experiments/liteapi_v3"

FROZEN_CBM = EXP / "reference" / "m2_final.cbm"
FROZEN_SHA_PREFIX = "0b481c38eb45d270"
V21 = ROOT / "models" / "hotel_price_india_hybrid_v21.joblib"
V1_CBM = ROOT / "models" / "hotel_price_india_hybrid_v1.cbm"
OUT_CBM = ROOT / "models" / "hotel_price_india_v3_m2.cbm"
OUT_META = ROOT / "models" / "hotel_price_india_v3_m2.joblib"

MODEL_VERSION = "india-v3-m2"
# The frozen calibration value. 0.4207 is its 4-decimal display; the exact
# value below is what was actually used to calibrate and to score TEST, so it
# is what ships - rounding here would be a real deviation from the experiment.
QHAT = 0.420727990504081
BUSINESS_TOLERANCE = 0.15
TEMPORAL_FACTOR_HISTORICAL = 1.3707      # 2020 B2 only
TEMPORAL_FACTOR_ML = 1.0                 # V3 is already 2026-native


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main() -> None:
    spec = json.load(open(EXP / "reference" / "m2_frozen_spec.json"))
    calib = json.load(open(EXP / "reference" / "calibration.json"))
    assert calib["qhat"] == QHAT, "qhat drifted from the frozen calibration"
    assert round(QHAT, 4) == 0.4207, "qhat no longer matches the reported 0.4207"

    # ---- promote the exact binary, no refit ----------------------------
    frozen_sha = sha(FROZEN_CBM)
    assert frozen_sha.startswith(FROZEN_SHA_PREFIX), \
        f"frozen candidate SHA {frozen_sha} does not match {FROZEN_SHA_PREFIX}"
    shutil.copyfile(FROZEN_CBM, OUT_CBM)
    promoted_sha = sha(OUT_CBM)
    assert promoted_sha == frozen_sha, "promoted binary differs from the frozen candidate"
    print(f"[promote] {OUT_CBM.name} <- m2_final.cbm  (no refit)")
    print(f"[verify ] frozen  SHA256 {frozen_sha}")
    print(f"[verify ] promoted SHA256 {promoted_sha}   MATCH")

    # ---- HISTORICAL B2 copied verbatim from v2.1 (== v1) ---------------
    v21 = joblib.load(V21)
    b2_room, b2_hotel = v21["b2_hotel_room"].copy(), v21["b2_hotel"].copy()
    for name, a, b in (("b2_hotel_room", v21["b2_hotel_room"], b2_room),
                       ("b2_hotel", v21["b2_hotel"], b2_hotel)):
        assert list(a.columns) == list(b.columns) and len(a) == len(b)
        for c in ("p25", "p50", "p75", "n"):
            assert np.array_equal(a[c].to_numpy(), b[c].to_numpy()), f"{name}.{c} changed"
    print(f"[B2] copied verbatim from v2.1: {len(b2_room):,} hotel x room cells, "
          f"{len(b2_hotel):,} hotel rows, {len(v21['known_hotel_keys']):,} known keys")

    t = json.load(open(EXP / "reference" / "caltest_manifests.json"))
    meta = {
        "modelVersion": MODEL_VERSION,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "supersedes": "india-hybrid-v21 (ML path only; HISTORICAL path unchanged since v1)",
        "trainingProvider": "LiteAPI",
        "collectionVersion": "v3-liteapi-native-1",
        "collectionDate": "2026-08-15",
        "architecture": {
            "known": "B2 hotel-own historical quantiles (2020 PromptCloud, unchanged since v1)",
            "unknown": "V3 M2 CatBoost MultiQuantile trained on LiteAPI-native 2026 rates",
            "routing": "unambiguous normalized hotel name AND >= 5 training offers -> B2, else V3 ML",
            "postProcessing": "sort -> (ML only) CQR widen -> temporal factor per path -> band",
        },
        "datasetCounts": {"trainHotels": 852, "trainRows": 18882,
                          "calHotels": 150, "calRows": 2888,
                          "testHotels": 173, "testRows": 3228,
                          "disjoint": "TRAIN, CAL and TEST hotelId sets are mutually disjoint"},
        "datasetSha256": spec["datasetSha256"],
        "frozenModelSha256": frozen_sha,
        "frozenSpecSha256": sha(EXP / "reference" / "m2_frozen_spec.json"),
        "calTestManifestSha256": sha(EXP / "reference" / "caltest_manifests.json"),
        "featureSchema": spec["features"],
        "categoricalFeatures": spec["categoricalFeatures"],
        "missingSemantics": spec["missingSemantics"],
        "transforms": spec["transforms"],
        "excludedFeatures": {
            "hotelId": "exact identity is meaningless for an unseen hotel",
            "currentPrice": "never a feature; compared to the prediction afterwards",
            "facilityIds(raw)": "482-dim list excluded; only n_facilities is used "
                                "(incremental signal over stars measured at 2.0%)",
            "v21 family features": "not part of frozen M2",
            "price_rank/default_rank": "not present in the LiteAPI-native dataset",
        },
        "calibration": {
            "qhat": QHAT,
            "appliesTo": "V3 ML path only",
            "score": "normalized CQR: E = max(qlo - y, y - qhi) / (qhi - qlo)",
            "selection": "hotel-balanced 50th percentile of E on the CAL hotels",
            "calCoverageRaw": calib["calCoverageRaw"],
            "calCoverageCalibrated": calib["calCoverageCalibrated"],
            "guarantee": "GROUP-DISJOINT EMPIRICAL calibration. CAL hotels are permanently "
                         "excluded from model fitting, but rows are clustered within hotels, "
                         "so row-level exchangeability does not hold and NO formal marginal "
                         "coverage guarantee is claimed.",
            "segmentSpecific": False,
        },
        "temporalAdjustment": {
            "historicalPathFactor": TEMPORAL_FACTOR_HISTORICAL,
            "mlPathFactor": TEMPORAL_FACTOR_ML,
            "why": "B2 quantiles come from March 2020 PromptCloud data and are rebased with the "
                   "MoSPI hotel-lodging CPI factor. The V3 ML model was trained on 2026 "
                   "LiteAPI-native prices and is ALREADY on the current price level - applying "
                   "any CPI factor to it would double-count inflation.",
            "source": "MoSPI Hotel Lodging Charges, item 6.1.04.2.2.07.0 (2012=100) chained to "
                      "item 330 (2024=100); Feb 2020 anchor -> Jul 2026",
            "currentPriceAdjusted": False,
        },
        "testMetrics": {
            "protocol": "single untouched TEST set, hotel-balanced, read once after locking",
            "B2_MdAPE": 0.335, "M2_MdAPE": 0.289,
            "B2_pinball": 1219.1, "M2_raw_pinball": 1097.3, "M2_calibrated_pinball": 1101.2,
            "coverageRaw": 0.329, "coverageCalibrated": 0.549,
            "medianActualOverPredictedP50": 0.98,
            "improvementVsB2": {"MdAPE": "+13.8%", "pinball": "+10.0%"},
            "note": "MdAPE 0.289 is a median absolute percentage error. It is NOT an accuracy "
                    "figure and must not be reported as '71.1% accurate'.",
        },
        "requestContract": {"rooms": 1, "adults": 2, "children": 0, "nights": 1,
                            "currency": "INR", "guestNationality": "IN",
                            "comparisonBasis": "PER_NIGHT_1ROOM_2ADULTS",
                            "offerSelection": "CHEAPEST_COMPARABLE_ONE_NIGHT"},
        "businessTolerance": BUSINESS_TOLERANCE,
        "decisionBand": "low=min(p25, 0.85*p50); high=max(p75, 1.15*p50)",
        "markets": sorted(t["CAL"].keys()),
        "limitations": [
            "One booking date: lead times 1-14 vary but the booking date does not, so this "
            "supports current fair-price bands and NOT price-movement forecasting.",
            "Calibrated TEST coverage is 0.549 against a 0.50 nominal - slight over-coverage.",
            "The UNRATED-star cell reached only 0.320 calibrated coverage; a single global "
            "qhat under-covers it. No segment-specific calibration is applied.",
            "173 TEST hotels is a modest final sample; the Jaipur/Kolkata/Pune regressions and "
            "Goa's 1.30 ratio may be sample noise.",
            "Hotels missing a guest rating are the one segment where M2 slightly trails B2.",
            "Trained on LiteAPI inventory: rate availability correlates with star class, so the "
            "serving population - not the whole Indian market - is what this model describes.",
        ],
    }

    joblib.dump({
        "metadata": meta,
        "b2_hotel_room": b2_room,
        "b2_hotel": b2_hotel,
        "known_hotel_keys": v21["known_hotel_keys"],
        "ambiguous_hotel_keys": v21["ambiguous_hotel_keys"],
        "hotel_obs_counts": v21["hotel_obs_counts"],
        "city_aliases": v21["city_aliases"],
        "global_quantiles": v21["global_quantiles"],
    }, OUT_META)
    print(f"[meta] saved {OUT_META.name} ({OUT_META.stat().st_size/1e6:.2f} MB)")

    back = joblib.load(OUT_META)
    for k in ("b2_hotel_room", "b2_hotel"):
        assert back[k].equals(v21[k]), f"{k} not identical to v2.1 after round-trip"
    assert back["known_hotel_keys"] == v21["known_hotel_keys"]
    print("[verify] B2 tables + known keys identical to v2.1 after reload")
    for f in (ROOT / "models" / "hotel_price_india_hybrid_v1.cbm",
              ROOT / "models" / "hotel_price_india_hybrid_v1.joblib",
              ROOT / "models" / "hotel_price_india_hybrid_v21.cbm", V21):
        print(f"[untouched] {f.name:<38}{sha(f)[:16]}...")
    print(f"[sha256] {OUT_CBM.name}   {sha(OUT_CBM)}")
    print(f"[sha256] {OUT_META.name}  {sha(OUT_META)}")


if __name__ == "__main__":
    main()
