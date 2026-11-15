#!/usr/bin/env python3
"""Builds the model packs for manga translation (ADR-0005 §5) from their public sources.

Usage:
    python3 -m venv .venv && .venv/bin/pip install onnx onnxruntime==1.32.* numpy
    .venv/bin/python -I tools/manga-models/build_packs.py OUT_DIR [--with-inpaint]

Writes OUT_DIR/vision-v1/* (and OUT_DIR/inpaint-v1/*) plus manifest.json with each file's size and
SHA-256, which the app pins. Every step is deterministic for a given onnxruntime version.
"""
import hashlib, json, os, re, shutil, sys, urllib.request

HF = "https://huggingface.co"
SOURCES = {
    "detector": f"{HF}/ogkalu/comic-text-and-bubble-detector/resolve/main/detector-v4-s_int8.onnx",
    "encoder": f"{HF}/artsaraiva/manga-ocr-2025-onnx-past/resolve/main/encoder_model.onnx",
    "decoder": f"{HF}/artsaraiva/manga-ocr-2025-onnx-past/resolve/main/decoder_model_merged.onnx",
    "vocab": f"{HF}/artsaraiva/manga-ocr-2025-onnx-past/resolve/main/vocab.txt",
    "font": "https://github.com/google/fonts/raw/main/ofl/notosanstc/NotoSansTC%5Bwght%5D.ttf",
    "lama": f"{HF}/ogkalu/lama-manga-onnx-dynamic/resolve/main/lama-manga-dynamic.onnx",
}


def fetch(url, path):
    if not os.path.exists(path):
        print("downloading", url)
        urllib.request.urlretrieve(url, path + ".part")
        os.replace(path + ".part", path)
    return path


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build_vision(cache, out):
    from onnxruntime.quantization import quantize_dynamic, QuantType
    os.makedirs(out, exist_ok=True)
    det = fetch(SOURCES["detector"], f"{cache}/detector.onnx")
    enc = fetch(SOURCES["encoder"], f"{cache}/encoder_fp32.onnx")
    dec = fetch(SOURCES["decoder"], f"{cache}/decoder_fp32.onnx")
    # the detector is already int8
    shutil.copyfile(det, f"{out}/detector.onnx")
    # encoder: per-channel int8 on MatMul only (the patch-embedding conv stays fp32)
    quantize_dynamic(enc, f"{out}/encoder.onnx", weight_type=QuantType.QInt8, per_channel=True,
                     op_types_to_quantize=["MatMul", "Gemm"])
    # decoder (merged, with past): int8 on MatMul and the embeddings, inside the If branches too
    quantize_dynamic(dec, f"{out}/decoder.onnx", weight_type=QuantType.QInt8,
                     op_types_to_quantize=["MatMul", "Gemm", "Gather"], extra_options={"EnableSubgraph": True})
    fetch(SOURCES["vocab"], f"{out}/vocab.txt")
    fetch(SOURCES["font"], f"{out}/NotoSansTC-VF.ttf")


def build_inpaint(cache, out):
    import onnx
    from onnxruntime.quantization import quantize_dynamic, QuantType
    os.makedirs(out, exist_ok=True)
    src = fetch(SOURCES["lama"], f"{cache}/lama_fp32.onnx")
    convs = [n.name for n in onnx.load(src, load_external_data=False).graph.node if n.op_type == "Conv"]
    # quantizing the down-sampling convs (model.0-4) wrecks the output: keep them fp32
    blocks = [c for c in convs if not re.search(r"model\.[0-4]/", c) and "/ffc/" in c]
    quantize_dynamic(src, f"{out}/lama.onnx", weight_type=QuantType.QInt8, nodes_to_quantize=blocks, per_channel=True)


def manifest(root):
    packs = {}
    for pack in sorted(os.listdir(root)):
        d = os.path.join(root, pack)
        if os.path.isdir(d) and not pack.startswith("."):
            # files in sub-directories (a Core ML package) are named by their path in the pack
            files = sorted(os.path.relpath(os.path.join(r, f), d) for r, _, fs in os.walk(d) for f in fs)
            packs[pack] = [{"name": f, "size": os.path.getsize(os.path.join(d, f)), "sha256": sha256(os.path.join(d, f))}
                           for f in files]
    with open(os.path.join(root, "manifest.json"), "w") as f:
        json.dump(packs, f, indent=1)
    print(json.dumps(packs, indent=1))


if __name__ == "__main__":
    out = sys.argv[1]
    cache = os.path.join(out, ".cache"); os.makedirs(cache, exist_ok=True)
    build_vision(cache, os.path.join(out, "vision-v1"))
    if "--with-inpaint" in sys.argv:
        build_inpaint(cache, os.path.join(out, "inpaint-v1"))
    manifest(out)
