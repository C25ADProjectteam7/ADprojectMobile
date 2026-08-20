"""
V3 — baseline-first modeling experiment (B0..B3 vs M0..M2)
==========================================================
EXPERIMENT ONLY. No production file is touched, no artifact is written.

Question: does hotel-class-aware ML beat a strong `market x stars x room`
quantile baseline for a COMPLETELY UNSEEN LiteAPI hotel?

Protocol
--------
* 5-fold WHOLE-HOTEL split on hotelId, stratified by market x star_bucket so
  every fold sees a comparable class mix. One manifest, reused by all
  candidates. train ∩ val hotelIds = {} is asserted per fold.
* HOTEL-BALANCED weighting is primary: each hotel contributes the same total
  weight, so one 5-star property with 50 rows cannot count 10x a 1-star
  property with 5. Row-weighted numbers are reported as secondary.
* Baseline tables are built from TRAINING hotels only, from per-hotel medians
  (one value per hotel per bucket), and require a minimum number of distinct
  HOTELS - not rows - before a bucket is usable.
* The target is the one-night comparable INR price. hotelId is never a feature.
  No current/target price is ever an input.
"""
from __future__ import annotations

import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
import pandas as pd
from catboost import CatBoostRegressor, Pool

HERE = Path(__file__).resolve().parent
REF = HERE / "reference"
REF.mkdir(exist_ok=True)

Q = (0.25, 0.50, 0.75)
SEED, N_FOLDS = 20260815, 5
MIN_HOTELS_BUCKET = 8
CB = dict(loss_function="MultiQuantile:alpha=0.25,0.5,0.75", iterations=500,
          depth=6, learning_rate=0.1, random_seed=SEED, verbose=0, thread_count=-1)
BUCKETS = ["1-2", "3", "4", "5", "UNRATED"]

M0_F = ["market", "star_bucket", "stars_num", "room_category",
        "board_category", "cancellation_category", "leadTimeDays"]
M0_C = ["market", "star_bucket", "room_category", "board_category", "cancellation_category"]
M1_F = M0_F + ["guest_rating", "has_guest_rating", "log_review_count"]
M1_C = M0_C
# facilities add only 2.0% incremental over stars, so only a COUNT is used -
# the raw 482-dimensional facilityIds list is deliberately not dumped in.
M2_F = M1_F + ["chain", "hotelTypeId", "n_facilities"]
M2_C = M0_C + ["chain"]


def star_bucket(s):
    if pd.isna(s) or s == 0:
        return "UNRATED"
    return "1-2" if s <= 2 else ("3" if s < 4 else ("4" if s < 5 else "5"))


def wquantile(v, w, q):
    """Weighted quantile - needed because every metric here is hotel-balanced."""
    o = np.argsort(v)
    v, w = np.asarray(v)[o], np.asarray(w)[o]
    cw = np.cumsum(w) - 0.5 * w
    cw /= np.sum(w)
    return float(np.interp(q, cw, v))


def pinball(y, p, w):
    tot = 0.0
    for i, t in enumerate(Q):
        d = y - p[:, i]
        tot += np.average(np.maximum(t * d, (t - 1) * d), weights=w)
    return float(tot / len(Q))


def metrics(y, p, w, hotel):
    p = np.sort(p, axis=1)
    lo, mid, hi = p[:, 0], p[:, 1], p[:, 2]
    ape = np.abs(y - mid) / np.maximum(y, 1e-9)
    return {"n": int(len(y)), "hotels": int(pd.Series(hotel).nunique()),
            "pinball": pinball(y, p, w),
            "MdAPE": wquantile(ape, w, .5),
            "ratio": wquantile(y / np.maximum(mid, 1e-9), w, .5),
            "coverage": float(np.average((y >= lo) & (y <= hi), weights=w))}


def build_split(hotels: pd.DataFrame) -> dict:
    """Deterministic hotel-level stratification on market x star_bucket."""
    rng = np.random.default_rng(SEED)
    assign = {}
    for key, g in hotels.groupby(["market", "star_bucket"]):
        ids = sorted(g.hotelId.astype(str))
        rng.shuffle(ids)
        for i, hid in enumerate(ids):
            assign[hid] = i % N_FOLDS
    return assign


def baseline_table(tr: pd.DataFrame, cols: list) -> dict:
    """Quantiles across per-hotel medians; needs >= MIN_HOTELS_BUCKET hotels."""
    hm = tr.groupby(cols + ["hotelId"]).price.median().reset_index()
    out = {}
    for key, g in hm.groupby(cols):
        if g.hotelId.nunique() < MIN_HOTELS_BUCKET:
            continue
        out[key if isinstance(key, tuple) else (key,)] = tuple(g.price.quantile(list(Q)))
    return out


def apply_hier(te: pd.DataFrame, tables: list, national: tuple) -> np.ndarray:
    out = np.zeros((len(te), 3))
    for i, row in enumerate(te.itertuples()):
        got = None
        for cols, tbl in tables:
            k = tuple(getattr(row, c) for c in cols)
            if k in tbl:
                got = tbl[k]
                break
        out[i] = got if got else national
    return out


def main() -> None:
    J = pd.read_parquet(HERE / "canonical.parquet")
    J["star_bucket"] = J.stars.map(star_bucket)
    J["stars_num"] = J.stars.fillna(-1)
    J["has_guest_rating"] = ((~J.rating.isna()) & (J.rating > 0)).astype(int)
    J["guest_rating"] = np.where(J.has_guest_rating == 1, J.rating, np.nan)
    J["log_review_count"] = np.log1p(J.reviewCount.where(J.reviewCount > 0))
    J["n_facilities"] = J.facilityIds.map(len)
    J["chain"] = J.chain.fillna("NA").replace("", "NA")
    J["hotelId"] = J.hotelId.astype(str)
    J["lead_b"] = J.leadTimeDays.astype(str)

    hotels = J.drop_duplicates("hotelId")[["hotelId", "market", "star_bucket"]]
    assign = build_split(hotels)
    json.dump({"seed": SEED, "folds": N_FOLDS,
               "stratification": "market x star_bucket, hotel-level",
               "assignment": assign}, open(REF / "split_manifest.json", "w"), indent=1)
    print(f"[split] {len(assign)} hotels -> {N_FOLDS} folds, stratified market x star_bucket")
    for f in range(N_FOLDS):
        v = [h for h, k in assign.items() if k == f]
        print(f"  fold{f}: {len(v)} val hotels | star mix "
              f"{dict(Counter(hotels.set_index('hotelId').star_bucket.reindex(v)))}")

    res = defaultdict(list)
    seg = defaultdict(lambda: defaultdict(list))

    for f in range(N_FOLDS):
        vh = {h for h, k in assign.items() if k == f}
        TR, TE = J[~J.hotelId.isin(vh)].copy(), J[J.hotelId.isin(vh)].copy()
        assert not (set(TR.hotelId) & set(TE.hotelId)), "hotel leaked across split"

        # hotel-balanced weights
        TR["w"] = 1.0 / TR.groupby("hotelId").hotelId.transform("size")
        TE["w"] = 1.0 / TE.groupby("hotelId").hotelId.transform("size")
        y_tr, y_te = TR.price.to_numpy(), TE.price.to_numpy()
        w_te, w_row = TE.w.to_numpy(), np.ones(len(TE))

        nat = tuple(TR.groupby("hotelId").price.median().quantile(list(Q)))
        t_m = baseline_table(TR, ["market"])
        t_ms = baseline_table(TR, ["market", "star_bucket"])
        t_msr = baseline_table(TR, ["market", "star_bucket", "room_category"])
        t_s = baseline_table(TR, ["star_bucket"])
        t_msrl = baseline_table(TR, ["market", "star_bucket", "room_category", "lead_b"])

        preds = {
            "B0": apply_hier(TE, [(["market"], t_m)], nat),
            "B1": apply_hier(TE, [(["market", "star_bucket"], t_ms),
                                  (["market"], t_m)], nat),
            "B2": apply_hier(TE, [(["market", "star_bucket", "room_category"], t_msr),
                                  (["market", "star_bucket"], t_ms),
                                  (["star_bucket"], t_s)], nat),
            "B3": apply_hier(TE, [(["market", "star_bucket", "room_category", "lead_b"], t_msrl),
                                  (["market", "star_bucket", "room_category"], t_msr),
                                  (["market", "star_bucket"], t_ms),
                                  (["star_bucket"], t_s)], nat),
        }
        for tag, F, C in (("M0", M0_F, M0_C), ("M1", M1_F, M1_C), ("M2", M2_F, M2_C)):
            m = CatBoostRegressor(**CB)
            m.fit(Pool(TR[F], y_tr, weight=TR.w.to_numpy(), cat_features=C))
            preds[tag] = m.predict(TE[F])

        for tag, p in preds.items():
            res[tag].append({"balanced": metrics(y_te, p, w_te, TE.hotelId),
                             "row": metrics(y_te, p, w_row, TE.hotelId)})
            for dim, col in (("star", "star_bucket"), ("market", "market"),
                             ("lead", "leadTimeDays"), ("room", "room_category")):
                for k, idx in TE.groupby(col).groups.items():
                    ii = TE.index.get_indexer(idx)
                    if len(ii) < 20:
                        continue
                    seg[tag][f"{dim}={k}"].append(
                        metrics(y_te[ii], p[ii], w_te[ii], TE.hotelId.iloc[ii]))
        print(f"  fold{f} done  B2 MdAPE={res['B2'][-1]['balanced']['MdAPE']:.3f}  "
              f"M1 MdAPE={res['M1'][-1]['balanced']['MdAPE']:.3f}")

    agg = lambda tag, kind, k: float(np.mean([x[kind][k] for x in res[tag]]))
    out = {t: {kind: {k: agg(t, kind, k) for k in ("pinball", "MdAPE", "ratio", "coverage")}
               for kind in ("balanced", "row")} for t in res}
    out["_segments"] = {t: {s: {k: float(np.mean([x[k] for x in v]))
                                for k in ("MdAPE", "ratio", "coverage")}
                            for s, v in seg[t].items()} for t in seg}
    json.dump(out, open(REF / "baseline_first_cv.json", "w"), indent=1)
    print("\n=== DONE ===")


if __name__ == "__main__":
    main()
