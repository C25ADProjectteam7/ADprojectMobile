"""
Decision-band audit — no retraining
===================================
Reproduces the APPROVED hybrid predictions deterministically (identical seed,
config, features and split boundaries as experiment_serving_parity.py) purely
to materialise per-row P25/P50/P75, then applies the FROZEN business rule:

    tolerance     = 15%   (product decision, not an ML parameter)
    decision_low  = min(P25, 0.85 * P50)
    decision_high = max(P75, 1.15 * P50)

    quote <  decision_low   -> CHEAP
    low  <= quote <= high   -> FAIR
    quote >  decision_high  -> EXPENSIVE

The band only ever widens, so decision_low <= P25 <= P50 <= P75 <= decision_high
holds by construction. The 15% is NOT tuned against these results.

The held-out actual price is used as a stand-in for "the current quote" so the
label distribution and stability can be measured; that is an audit device, not
a claim that the model saw it.

Run: .venv/bin/python training/v2mvp_india/audit_decision_band.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from catboost import CatBoostRegressor, Pool

sys.path.insert(0, str(Path(__file__).resolve().parent))
from serving_features import (  # noqa: E402
    CATEGORICAL, FEATURES, breakfast_category, cancellation_category,
    normalize_name, room_category,
)

ROOT = Path(__file__).resolve().parent.parent.parent
CLEAN = ROOT / "training" / "data" / "promptcloud" / "india_offers_clean.parquet"
OUT = Path(__file__).resolve().parent / "reference" / "decision_band.json"

Q = (0.25, 0.50, 0.75)
TOL = 0.15
CB = dict(loss_function="MultiQuantile:alpha=0.25,0.5,0.75", iterations=500,
          depth=6, learning_rate=0.1, random_seed=42, verbose=0, thread_count=-1)
MIN_HOTEL_TRAIN_OBS = 5
M0_FEATS = [f for f in FEATURES if f != "hotel_key"]


def band(P):
    lo = np.minimum(P[:, 0], (1 - TOL) * P[:, 1])
    hi = np.maximum(P[:, 2], (1 + TOL) * P[:, 1])
    return lo, hi


def label(price, lo, hi):
    o = np.full(len(price), "FAIR", dtype=object)
    o[price < lo] = "CHEAP"
    o[price > hi] = "EXPENSIVE"
    return o


def qtab(tr, keys, mn):
    g = tr.groupby(keys).price
    t = g.quantile(list(Q)).unstack()
    t.columns = ["p25", "p50", "p75"]
    t["n"] = g.size()
    return t[t.n >= mn]


def lookup(tr, te, levels, glob):
    P = np.tile(glob, (len(te), 1))
    done = np.zeros(len(te), bool)
    for keys, mn in levels:
        if done.all():
            break
        t = qtab(tr, keys, mn)
        if t.empty:
            continue
        idx = pd.MultiIndex.from_frame(te[keys]) if len(keys) > 1 else pd.Index(te[keys[0]])
        g = t.reindex(idx)
        ok = g.p50.notna().to_numpy() & ~done
        if ok.any():
            P[ok] = g[["p25", "p50", "p75"]].to_numpy()[ok]
            done |= ok
    return P


def hybrid_predictions(tr, te):
    glob = tr.price.quantile(list(Q)).to_numpy()
    tr, te = tr.copy(), te.copy()
    for x in (tr, te):
        x["lead_b"] = pd.cut(x.lead_time_days, [-1, 3, 7, 14, 99], labels=False)
    ok = tr[~tr.hotel_ambiguous].groupby("hotel_key").size()
    ok = set(ok[ok >= MIN_HOTEL_TRAIN_OBS].index)
    known = (te.hotel_key.isin(ok) & ~te.hotel_ambiguous).to_numpy()

    B1 = lookup(tr, te, [(["room_category", "breakfast_category", "lead_b"], 20),
                         (["room_category", "breakfast_category"], 20),
                         (["room_category"], 20)], glob)
    tr_ok = tr[tr.hotel_key.isin(ok) & ~tr.hotel_ambiguous]
    B2 = lookup(tr_ok, te, [(["hotel_key", "room_category"], 5), (["hotel_key"], 5)], glob)
    B2[~known] = B1[~known]

    m = CatBoostRegressor(**CB)
    m.fit(Pool(tr[FEATURES], tr.price, cat_features=CATEGORICAL))
    M1 = m.predict(te[FEATURES])
    H = np.where(known[:, None], B2, M1)
    return H, known


def audit(y, P, tag, out):
    lo, hi = band(P)
    p25, p50, p75 = P[:, 0], P[:, 1], P[:, 2]

    inv = int(((lo > p25) | (p25 > p50) | (p50 > p75) | (p75 > hi)).sum())
    lab = label(y, lo, hi)
    raw = label(y, p25, p75)                      # direct P25/P75 rule
    contra_hi = int(((y <= p75) & (lab == "EXPENSIVE")).sum())
    contra_lo = int(((y >= p25) & (lab == "CHEAP")).sum())

    flips = {}
    for nm, f in (("+/-2%", 0.02), ("+/-5%", 0.05)):
        a = np.mean(label(y * (1 + f), lo, hi) != lab)
        b = np.mean(label(y * (1 - f), lo, hi) != lab)
        flips[nm] = float((a + b) / 2)
        a = np.mean(label(y * (1 + f), p25, p75) != raw)
        b = np.mean(label(y * (1 - f), p25, p75) != raw)
        flips[nm + "_raw"] = float((a + b) / 2)

    dist = {k: float((lab == k).mean()) for k in ("CHEAP", "FAIR", "EXPENSIVE")}
    dist_raw = {k: float((raw == k).mean()) for k in ("CHEAP", "FAIR", "EXPENSIVE")}
    rec = {"n": int(len(y)), "invariant_violations": inv,
           "contradictions_expensive_below_p75": contra_hi,
           "contradictions_cheap_above_p25": contra_lo,
           "dist": dist, "dist_raw_p25p75": dist_raw,
           "inside_band": float(np.mean((y >= lo) & (y <= hi))),
           "inside_p25p75": float(np.mean((y >= p25) & (y <= p75))),
           "mean_band_width": float(np.mean(hi - lo)),
           "mean_iqr_width": float(np.mean(p75 - p25)),
           "flips": flips}
    out[tag] = rec
    print(f"\n  --- {tag} (n={len(y):,}) ---")
    print(f"  invariant low<=P25<=P50<=P75<=high violations : {inv}")
    print(f"  contradictions (EXPENSIVE but <=P75)          : {contra_hi}")
    print(f"  contradictions (CHEAP but >=P25)              : {contra_lo}")
    print(f"  band  : CHEAP {dist['CHEAP']*100:5.1f}%  FAIR {dist['FAIR']*100:5.1f}%  "
          f"EXPENSIVE {dist['EXPENSIVE']*100:5.1f}%   inside {rec['inside_band']*100:5.1f}%  "
          f"width {rec['mean_band_width']:.0f}")
    print(f"  raw   : CHEAP {dist_raw['CHEAP']*100:5.1f}%  FAIR {dist_raw['FAIR']*100:5.1f}%  "
          f"EXPENSIVE {dist_raw['EXPENSIVE']*100:5.1f}%   inside {rec['inside_p25p75']*100:5.1f}%  "
          f"width {rec['mean_iqr_width']:.0f}")
    print(f"  flip +/-2%: band {flips['+/-2%']*100:5.2f}%  raw {flips['+/-2%_raw']*100:5.2f}%   "
          f"| flip +/-5%: band {flips['+/-5%']*100:5.2f}%  raw {flips['+/-5%_raw']*100:5.2f}%")


def main() -> None:
    d = pd.read_parquet(CLEAN)
    d["hotel_key"] = d.hotel_name.map(normalize_name)
    d["room_category"] = d.room_norm.map(room_category)
    d["breakfast_category"] = d.bf.map(breakfast_category)
    d["cancellation_category"] = d.canc.map(cancellation_category)
    amb = d.groupby("hotel_key").hotel_id.nunique()
    d["hotel_ambiguous"] = d.hotel_key.map(amb > 1)

    print(f"decision band: low=min(P25, {1-TOL:.2f}*P50)  high=max(P75, {1+TOL:.2f}*P50)")
    dates = np.array(sorted(d.cr.unique()))
    out = {}

    cut = dates[int(len(dates) * 0.75)]
    tr, te = d[d.cr < cut], d[d.cr >= cut]
    H, known = hybrid_predictions(tr, te)
    y = te.price.to_numpy()
    audit(y, H, "primary", out)
    audit(y[known], H[known], "primary_known", out)
    audit(y[~known], H[~known], "primary_unknown", out)

    for k, start in enumerate(range(12, len(dates) - 3 + 1, 3), 1):
        trd, ted = dates[:start], dates[start:start + 3]
        a, b = d[d.cr.isin(trd)], d[d.cr.isin(ted)]
        if len(b) < 1000:
            continue
        H, known = hybrid_predictions(a, b)
        audit(b.price.to_numpy(), H, f"W{k}", out)

    ks = [k for k in out if k in ("primary", "W1", "W2", "W3", "W4")]
    print(f"\n{'='*88}\nAGGREGATE over {len(ks)} splits\n{'='*88}")
    for m, lbl in (("inside_band", "inside decision band"), ("inside_p25p75", "inside raw P25-P75")):
        print(f"  {lbl:24s} mean {np.mean([out[k][m] for k in ks])*100:5.1f}%")
    for k2 in ("CHEAP", "FAIR", "EXPENSIVE"):
        print(f"  {k2:10s} band {np.mean([out[k]['dist'][k2] for k in ks])*100:5.1f}%   "
              f"raw {np.mean([out[k]['dist_raw_p25p75'][k2] for k in ks])*100:5.1f}%")
    for f in ("+/-2%", "+/-5%"):
        print(f"  flip {f:6s} band {np.mean([out[k]['flips'][f] for k in ks])*100:5.2f}%   "
              f"raw {np.mean([out[k]['flips'][f+'_raw'] for k in ks])*100:5.2f}%")
    print(f"  total invariant violations across splits: "
          f"{sum(out[k]['invariant_violations'] for k in ks)}")
    print(f"  total semantic contradictions          : "
          f"{sum(out[k]['contradictions_expensive_below_p75']+out[k]['contradictions_cheap_above_p25'] for k in ks)}")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"tolerance": TOL, "splits": out}, indent=2, default=float))
    print(f"\n[out] wrote {OUT}")


if __name__ == "__main__":
    main()
