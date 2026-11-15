#!/usr/bin/env python3
"""Builds the vision-coreml-v1 pack: the comic text and bubble detector (RT-DETRv2 R50) as a
Core ML model, for the GPU of iPhones and iPads (ADR-0005 §8, docs/research/manga-gpu-npu.md).

Usage (Python 3.13; coremltools has no native modules for newer Pythons):
    python3.13 -m venv .venv-coreml
    .venv-coreml/bin/pip install "torch==2.7.0" "transformers>=4.49,<5" coremltools "numpy<2.2" safetensors pillow
    .venv-coreml/bin/python -I tools/manga-models/convert_coreml_detector.py OUT_DIR

Writes OUT_DIR/vision-coreml-v1/detector.mlpackage: fp16, a fixed 640 x 640 input "pixels"
(RGB in 0..1, resized, not normalized) and outputs "scores" (1 x 300 x 3, sigmoid) and "boxes"
(1 x 300 x 4, centre x, centre y, width, height, normalized). The mlpackage's Manifest.json
holds random identifiers, so a rebuild is equivalent but not byte-identical: pin what you upload.
"""
import os, sys, urllib.request

HF = "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/main"


def main(out_root):
    import torch, coremltools as ct
    from transformers import RTDetrV2ForObjectDetection
    cache = os.path.join(out_root, ".cache", "detector-r50")
    os.makedirs(cache, exist_ok=True)
    for name in ["config.json", "preprocessor_config.json", "model.safetensors"]:
        path = os.path.join(cache, name)
        if not os.path.exists(path):
            print("downloading", name)
            urllib.request.urlretrieve(f"{HF}/{name}", path + ".part")
            os.replace(path + ".part", path)
    # anchors for a fixed 640 input are computed once, so the traced graph has no shape arithmetic
    model = RTDetrV2ForObjectDetection.from_pretrained(cache, anchor_image_size=[640, 640]).eval()

    class Detector(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, pixels):
            o = self.m(pixel_values=pixels)
            return o.logits.sigmoid(), o.pred_boxes

    example = torch.rand(1, 3, 640, 640)
    with torch.no_grad():
        traced = torch.jit.trace(Detector(model).eval(), example)
    ml = ct.convert(
        traced,
        inputs=[ct.TensorType(name="pixels", shape=example.shape)],
        outputs=[ct.TensorType(name="scores"), ct.TensorType(name="boxes")],
        compute_precision=ct.precision.FLOAT16,
        minimum_deployment_target=ct.target.iOS17,
        convert_to="mlprogram",
    )
    out = os.path.join(out_root, "vision-coreml-v1")
    os.makedirs(out, exist_ok=True)
    ml.save(os.path.join(out, "detector.mlpackage"))
    print("wrote", out)


if __name__ == "__main__":
    main(sys.argv[1])
