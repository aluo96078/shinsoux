#!/usr/bin/env python3
"""Builds the model packs for manga translation (ADR-0005 §5) from their public sources.

Usage:
    python3 -m venv .venv && .venv/bin/pip install onnx onnxruntime==1.32.* numpy
    .venv/bin/python -I tools/manga-models/build_packs.py OUT_DIR [--with-inpaint] [--with-ocr]

Writes OUT_DIR/vision-v1/* (and OUT_DIR/inpaint-v1/*) plus manifest.json with each file's size and
SHA-256, which the app pins. Every step is deterministic for a given onnxruntime version.

--with-ocr also builds the recognisers for Chinese and English pages (docs/research/manga-multilingual.md):
PaddleOCR's PP-OCRv5 mobile models converted by paddle2onnx (pip install paddlepaddle==3.3.* paddle2onnx==2.1.* pyyaml),
and the Simplified Chinese font.
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
    "font-sc": "https://github.com/google/fonts/raw/main/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf",
}

# PaddleOCR models (Apache-2.0) on Hugging Face: pack -> (model, file in the pack)
PADDLE = {
    "ocr-lines-v1": ("PP-OCRv5_mobile_det", "lines.onnx"),
    "ocr-zh-v1": ("PP-OCRv5_mobile_rec", "rec.onnx"),
    "ocr-en-v1": ("en_PP-OCRv5_mobile_rec", "rec.onnx"),
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


def build_ocr(cache, out):
    import subprocess, yaml
    # the converter installed beside this interpreter (a venv run without activating it), else on PATH
    local = os.path.join(os.path.dirname(sys.executable), "paddle2onnx" + (".exe" if os.name == "nt" else ""))
    converter = local if os.path.isfile(local) else shutil.which("paddle2onnx")
    if converter is None:
        sys.exit("paddle2onnx not found: install it into this Python environment (pip install paddle2onnx)")
    for pack, (model, name) in PADDLE.items():
        src = os.path.join(cache, model); os.makedirs(src, exist_ok=True)
        for f in ("inference.json", "inference.pdiparams", "inference.yml"):
            fetch(f"{HF}/PaddlePaddle/{model}/resolve/main/{f}", os.path.join(src, f))
        d = os.path.join(out, pack); os.makedirs(d, exist_ok=True)
        subprocess.run([converter, "--model_dir", src, "--model_filename", "inference.json",
                        "--params_filename", "inference.pdiparams", "--save_file", os.path.join(d, name),
                        "--opset_version", "14"], check=True)
        dictionary = yaml.safe_load(open(os.path.join(src, "inference.yml"))).get("PostProcess", {}).get("character_dict")
        if dictionary:
            # one character per line, in the model's order (the blank and the space are added by the app)
            with open(os.path.join(d, "chars.txt"), "w", encoding="utf-8", newline="\n") as f:
                f.write("\n".join(dictionary))
    font = os.path.join(out, "font-sc-v1"); os.makedirs(font, exist_ok=True)
    fetch(SOURCES["font-sc"], os.path.join(font, "NotoSansSC-VF.ttf"))


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
    if "--ocr-only" not in sys.argv:
        build_vision(cache, os.path.join(out, "vision-v1"))
    if "--with-ocr" in sys.argv or "--ocr-only" in sys.argv:
        build_ocr(cache, out)
    if "--with-inpaint" in sys.argv:
        build_inpaint(cache, os.path.join(out, "inpaint-v1"))
    manifest(out)
