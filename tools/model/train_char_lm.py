"""Train a tiny character language model that reranks Zhuyin candidates.

Runs on Google Colab (GPU). Steps, each callable on its own so a long run can be
resumed in the same kernel:

    prepare(work, max_chars)   # zh Wikipedia -> Traditional (Taiwan) text
    build_vocab(work)          # most frequent characters -> vocab.json
    train(work, steps)         # tiny causal Transformer
    export(work)               # ONNX scorer + int8 copy
    evaluate(work)             # homophone top-1 vs the bundled dictionary order

The exported scorer takes the typed context plus up to K candidate characters
and returns log P(candidate char | everything before it). Candidates that share
a reading are ranked by the sum of those log-probabilities blended with the
dictionary rank (see `fused_score`).
"""

from __future__ import annotations

import json
import math
import random
import re
from collections import Counter
from pathlib import Path

PAD, UNK, BOS = 0, 1, 2
SPECIALS = ["<pad>", "<unk>", "<bos>"]
VOCAB_SIZE = 7000          # including specials
MAX_CONTEXT = 32           # characters of context the app sends
K = 4                      # candidate characters scored per call
SEQ_LEN = 64               # training window
DICTIONARY_URL = (
    "https://raw.githubusercontent.com/RaibowSky/dynamic-zhuyin-keyboard/main/"
    "app/src/main/assets/zhuyin_cedict.tsv"
)
TONES = set("ˉˊˇˋ˙")
CJK = re.compile(r"[㐀-鿿]")


# ---------------------------------------------------------------- corpus
def prepare(work: Path, max_chars: int = 200_000_000, dev_docs: int = 3000) -> None:
    """Stream zh Wikipedia, convert to Taiwan Traditional, write train/dev text."""
    import opencc
    from datasets import load_dataset

    work.mkdir(parents=True, exist_ok=True)
    converter = opencc.OpenCC("s2twp")
    stream = load_dataset("wikimedia/wikipedia", "20231101.zh", split="train", streaming=True)
    written = 0
    with (work / "train.txt").open("w", encoding="utf-8") as train, \
            (work / "dev.txt").open("w", encoding="utf-8") as dev:
        for index, article in enumerate(stream):
            text = converter.convert(article["text"])
            lines = [line.strip() for line in text.split("\n")]
            body = "\n".join(line for line in lines if len(CJK.findall(line)) >= 8)
            if not body:
                continue
            (dev if index < dev_docs else train).write(body + "\n\n")
            if index >= dev_docs:
                written += len(body)
                if written >= max_chars:
                    break
            if index % 20000 == 0:
                print(f"articles={index} train_chars={written}", flush=True)
    print(f"done train_chars={written}")


def build_vocab(work: Path) -> list[str]:
    counts: Counter[str] = Counter()
    with (work / "train.txt").open(encoding="utf-8") as f:
        for line in f:
            counts.update(line.rstrip("\n"))
    counts.pop("\n", None)
    chars = [c for c, _ in counts.most_common(VOCAB_SIZE - len(SPECIALS))]
    vocab = SPECIALS + chars
    (work / "vocab.json").write_text(json.dumps(vocab, ensure_ascii=False), encoding="utf-8")
    covered = sum(counts[c] for c in chars) / max(1, sum(counts.values()))
    print(f"vocab={len(vocab)} coverage={covered:.4%}")
    return vocab


def load_vocab(work: Path) -> dict[str, int]:
    vocab = json.loads((work / "vocab.json").read_text(encoding="utf-8"))
    return {c: i for i, c in enumerate(vocab)}


def encode_file(work: Path, name: str):
    import numpy as np

    from array import array as typed_array

    index = load_vocab(work)
    ids = typed_array("H")
    start_of_article = True
    with (work / name).open(encoding="utf-8") as f:
        for line in f:  # articles are separated by blank lines
            line = line.rstrip("\n")
            if not line:
                start_of_article = True
                continue
            if start_of_article:
                ids.append(BOS)
                start_of_article = False
            else:
                ids.append(index.get("。", UNK))
            ids.extend(index.get(c, UNK) for c in line)
    array = np.frombuffer(ids, dtype=np.uint16).copy()
    np.save(work / (name + ".npy"), array)
    print(f"{name}: tokens={len(array)}")
    return array


# ---------------------------------------------------------------- model
def make_model(vocab_size: int, d: int = 192, layers: int = 3, heads: int = 4, ff: int = 768):
    import torch
    from torch import nn

    class Block(nn.Module):
        def __init__(self):
            super().__init__()
            self.ln1 = nn.LayerNorm(d)
            self.qkv = nn.Linear(d, 3 * d)
            self.proj = nn.Linear(d, d)
            self.ln2 = nn.LayerNorm(d)
            self.ff = nn.Sequential(nn.Linear(d, ff), nn.GELU(), nn.Linear(ff, d))

        def forward(self, x, mask):
            b, t, _ = x.shape
            q, k, v = self.qkv(self.ln1(x)).view(b, t, 3, heads, d // heads).permute(2, 0, 3, 1, 4)
            att = (q @ k.transpose(-2, -1)) / math.sqrt(d // heads)
            att = att.masked_fill(mask[:t, :t] == 0, float("-inf")).softmax(-1)
            x = x + self.proj((att @ v).transpose(1, 2).reshape(b, t, d))
            return x + self.ff(self.ln2(x))

    class CharLM(nn.Module):
        def __init__(self):
            super().__init__()
            self.tok = nn.Embedding(vocab_size, d)
            self.pos = nn.Embedding(SEQ_LEN, d)
            self.blocks = nn.ModuleList(Block() for _ in range(layers))
            self.ln = nn.LayerNorm(d)
            self.register_buffer("mask", torch.tril(torch.ones(SEQ_LEN, SEQ_LEN)), persistent=False)
            self.config = dict(vocab=vocab_size, d=d, layers=layers, heads=heads, ff=ff)

        def hidden(self, ids):
            t = ids.shape[1]
            x = self.tok(ids) + self.pos(torch.arange(t, device=ids.device))
            for block in self.blocks:
                x = block(x, self.mask)
            return self.ln(x)

        def logits(self, h):
            return h @ self.tok.weight.t()  # tied embeddings

        def forward(self, ids):
            return self.logits(self.hidden(ids))

    return CharLM()


def train(work: Path, steps: int = 20000, batch: int = 256, lr: float = 2e-3) -> None:
    import numpy as np
    import torch

    torch.manual_seed(0)
    data = np.load(work / "train.txt.npy") if (work / "train.txt.npy").exists() else encode_file(work, "train.txt")
    dev = np.load(work / "dev.txt.npy") if (work / "dev.txt.npy").exists() else encode_file(work, "dev.txt")
    vocab_size = len(load_vocab(work))
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model = make_model(vocab_size).to(device)
    print(f"params={sum(p.numel() for p in model.parameters()):,} device={device}")
    opt = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=0.01)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=lr, total_steps=steps, pct_start=0.05)
    scaler = torch.cuda.amp.GradScaler(enabled=device == "cuda")

    def batch_of(array, n):
        starts = np.random.randint(0, len(array) - SEQ_LEN - 1, size=n)
        x = np.stack([array[s:s + SEQ_LEN] for s in starts]).astype(np.int64)
        y = np.stack([array[s + 1:s + SEQ_LEN + 1] for s in starts]).astype(np.int64)
        return torch.from_numpy(x).to(device), torch.from_numpy(y).to(device)

    loss_fn = torch.nn.CrossEntropyLoss(ignore_index=PAD)
    for step in range(1, steps + 1):
        x, y = batch_of(data, batch)
        with torch.autocast(device_type=device, dtype=torch.float16, enabled=device == "cuda"):
            loss = loss_fn(model(x).float().view(-1, vocab_size), y.view(-1))
        opt.zero_grad(set_to_none=True)
        scaler.scale(loss).backward()
        scaler.unscale_(opt)
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        scaler.step(opt)
        scaler.update()
        sched.step()
        if step % 1000 == 0 or step == steps:
            model.eval()
            with torch.no_grad():
                dx, dy = batch_of(dev, 256)
                dev_loss = loss_fn(model(dx).float().view(-1, vocab_size), dy.view(-1)).item()
            model.train()
            print(f"step={step} loss={loss.item():.3f} dev_loss={dev_loss:.3f} "
                  f"dev_ppl={math.exp(dev_loss):.1f}", flush=True)
    torch.save({"state": model.state_dict(), "config": model.config}, work / "char_lm.pt")


def load_model(work: Path):
    import torch

    checkpoint = torch.load(work / "char_lm.pt", map_location="cpu")
    config = checkpoint["config"]
    model = make_model(config["vocab"], config["d"], config["layers"], config["heads"], config["ff"])
    model.load_state_dict(checkpoint["state"])
    return model.eval()


# ---------------------------------------------------------------- export
def make_scorer(model):
    """Wrap the LM as: ids [B, C+K], targets [B, K] -> log-probs [B, K].

    Row b is context (C tokens, identical for all rows) followed by a candidate
    padded on the right to K. Position C-1+j predicts candidate character j; a
    target of PAD contributes 0, so shorter candidates are summed correctly.
    """
    import torch

    class Scorer(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.model = model

        def forward(self, ids, targets):
            h = self.model.hidden(ids)[:, -K - 1:-1, :]
            logp = torch.log_softmax(self.model.logits(h), dim=-1)
            picked = logp.gather(-1, targets.unsqueeze(-1)).squeeze(-1)
            return picked * (targets != PAD).to(picked.dtype)

    return Scorer().eval()


def export(work: Path) -> None:
    import torch
    from onnxruntime.quantization import QuantType, quantize_dynamic

    scorer = make_scorer(load_model(work))
    ids = torch.full((4, 12 + K), BOS, dtype=torch.long)
    targets = torch.ones((4, K), dtype=torch.long)
    onnx_path = work / "zhuyin_rerank.fp32.onnx"
    torch.onnx.export(
        scorer, (ids, targets), onnx_path, opset_version=17,
        input_names=["ids", "targets"], output_names=["logp"], dynamo=False,
        dynamic_axes={"ids": {0: "batch", 1: "length"}, "targets": {0: "batch"}, "logp": {0: "batch"}},
    )
    quantize_dynamic(onnx_path, work / "zhuyin_rerank.onnx", weight_type=QuantType.QInt8)
    meta = {"format": 1, "max_context": MAX_CONTEXT, "k": K, "pad": PAD, "unk": UNK, "bos": BOS,
            "beta": 1.0}  # evaluate() replaces beta with the tuned value
    (work / "meta.json").write_text(json.dumps(meta), encoding="utf-8")
    for name in ["zhuyin_rerank.fp32.onnx", "zhuyin_rerank.onnx"]:
        print(name, (work / name).stat().st_size)


# ---------------------------------------------------------------- evaluation
def load_dictionary(work: Path) -> dict[str, list[str]]:
    import urllib.request

    path = work / "zhuyin_cedict.tsv"
    if not path.exists():
        urllib.request.urlretrieve(DICTIONARY_URL, path)
    entries = {}
    with path.open(encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            key, _, words = line.rstrip("\n").partition("\t")
            entries[key] = words.split(" ")
    return entries


def fused_score(model_logp: float, rank: int, beta: float) -> float:
    """Model log-probability plus a prior that favours the dictionary's order."""
    return model_logp - beta * math.log(rank + 1)


def evaluate(work: Path, samples: int = 6000, top_n: int = 8) -> dict:
    """Pick 1- and 2-character words from dev text whose toned reading is unique,
    rerank the dictionary's same-length candidates, and compare top-1 accuracy."""
    import numpy as np
    import onnxruntime as ort

    entries = load_dictionary(work)
    readings: dict[str, set[str]] = {}
    for key, words in entries.items():
        if any(c in TONES for c in key):
            for w in words:
                if len(w) <= 2:
                    readings.setdefault(w, set()).add(key)
    index = load_vocab(work)
    text = (work / "dev.txt").read_text(encoding="utf-8").replace("\n", "。")
    rng = random.Random(0)
    cases = []
    while len(cases) < samples:
        i = rng.randrange(MAX_CONTEXT, len(text) - 2)
        word = text[i:i + rng.choice((1, 2))]
        keys = readings.get(word)
        if not keys or len(keys) != 1:
            continue
        candidates = [w for w in entries[next(iter(keys))] if len(w) == len(word)][:top_n]
        if word not in candidates or len(candidates) < 2:
            continue
        cases.append((text[i - MAX_CONTEXT + 1:i], word, candidates))

    session = ort.InferenceSession(str(work / "zhuyin_rerank.onnx"), providers=["CPUExecutionProvider"])

    def model_scores(context, candidates):
        ctx = [index.get(c, UNK) for c in context] or [BOS]
        ids, targets = [], []
        for cand in candidates:
            tail = [index.get(c, UNK) for c in cand] + [PAD] * (K - len(cand))
            ids.append(ctx + tail)
            targets.append(tail)
        out = session.run(None, {"ids": np.asarray(ids, dtype=np.int64),
                                 "targets": np.asarray(targets, dtype=np.int64)})[0]
        return out.sum(axis=1)

    scored = [(word, candidates, model_scores(context, candidates)) for context, word, candidates in cases]
    baseline = sum(c[0] == w for w, c, _ in scored) / len(scored)
    results = {}
    for beta in (0.0, 0.5, 1.0, 1.5, 2.0, 3.0, 5.0):
        hits = 0
        for word, candidates, scores in scored:
            best = max(range(len(candidates)), key=lambda r: fused_score(scores[r], r, beta))
            hits += candidates[best] == word
        results[beta] = hits / len(scored)
    beta = max(results, key=results.get)
    report = {"cases": len(scored), "baseline_top1": baseline, "by_beta": results,
              "beta": beta, "best_top1": results[beta]}
    (work / "eval.json").write_text(json.dumps(report, indent=1), encoding="utf-8")
    meta = json.loads((work / "meta.json").read_text(encoding="utf-8"))
    meta["beta"] = beta
    (work / "meta.json").write_text(json.dumps(meta), encoding="utf-8")
    print(json.dumps(report, indent=1))
    return report
