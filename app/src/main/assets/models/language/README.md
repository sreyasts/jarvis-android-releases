---
license: apache-2.0
library_name: onnx
pipeline_tag: audio-classification
tags:
  - language-identification
  - spoken-language-identification
  - ecapa-tdnn
  - onnx
  - int8
datasets:
  - VoxLingua107
base_model: speechbrain/lang-id-voxlingua107-ecapa
---

# Spoken language identification: ECAPA-TDNN VoxLingua107, int8 ONNX

An on-device conversion of
[speechbrain/lang-id-voxlingua107-ecapa](https://huggingface.co/speechbrain/lang-id-voxlingua107-ecapa)
(revision `0253049ae131d6a4be1c4f0d8b0ff483a0f8c8e9`): one ONNX file that takes raw
16 kHz audio and returns log-probabilities of 107 languages. No feature extraction
is needed on the caller's side, so it runs with plain ONNX Runtime (Java, C, Python)
on a phone.

It is used by the «Слышно» voice recorder for Android to pick the language of a
new recording among the languages the user speaks.

## Files

| File | Size | SHA-256 |
|---|---|---|
| `lid-ecapa-voxlingua107.int8.onnx` | 26 602 386 bytes | `3f1504000353f1da5015ae0df0bdae8d1fd7064680e45a8c9cb02740d78c4fa9` |
| `lid-labels.txt` | 107 lines | language codes in the order of the model outputs (VoxLingua107, alphabetical) |
| `export_lid.py` | | the script that produced the file (deterministic: re-running it gives the same bytes) |

## Input and output

- input `wav`: float32 `[1, samples]`, 16 kHz mono, values in [-1, 1] (PCM16 / 32768);
  at least 1 s, 10 s is enough (see below);
- output `logp`: float32 `[1, 107]`, log-softmax over the labels in `lid-labels.txt`.

## What was changed

Apache-2.0 asks to state changes, so:

1. The SpeechBrain feature pipeline is moved into the graph: STFT as two
   convolutions with `hamming(400) * cos` and `hamming(400) * sin` kernels
   (hop 160, centered, zero padding), power spectrum, the SpeechBrain 60-bin mel
   matrix, `10 * log10` with an 80 dB floor, mean normalization over time. Then the
   original ECAPA-TDNN embedding model and classifier, unchanged.
2. Exported to ONNX (opset 17). Max difference of log-probabilities against the
   original SpeechBrain pipeline: 1.5e-5 (PyTorch module), 8.7e-5 (ONNX fp32 with
   the rewrite of step 3).
3. The 16 pointwise (1x1) convolutions, 93 % of the weights, are rewritten as
   MatMul with the activation on the left and the weight on the right. On ARM,
   ONNX Runtime's int8 MatMul is 3-5 times faster than its int8 convolution.
4. Dynamic int8 quantization (ONNX Runtime `quantize_dynamic`) of those MatMul
   weights only. The k>1 convolutions (7 % of the weights), the STFT and the mel
   matrix stay fp32.

## Accuracy

FLEURS test recordings, 12 per language, 15 languages (ru, uk, be, kk, uz, tg, en,
de, es, fr, it, pl, pt, ar, ja; Kyrgyz is not among the 107 VoxLingua107
languages). Choosing among the 15 languages: 175 of 180 correct; Russian against
any one of the others: 24 of 24, except Belarusian: 20 of 24. int8 gives the same
answers as fp32. The first 10 s give the same accuracy as the whole recording.

## Speed

10 s of audio, ONNX Runtime 1.28, 4 threads, without opening the session:

| Device | ABI | Time |
|---|---|---|
| Xiaomi MI 6 (Snapdragon 835) | arm64-v8a | 0.72 s |
| Huawei MGA-LX3 | arm64-v8a | 0.85 s |
| Xiaomi MI 5 (Snapdragon 820) | arm64-v8a | 2.0 s |
| Samsung SM-T800 (Exynos 5420) | armeabi-v7a | 2.3 s |
| Doogee X5pro (MT6735) | armeabi-v7a | 4.2 s |
| desktop x86-64, 2 threads | | 0.15 s |

The same answers as on the desktop for all test recordings on all of these
devices. With int8 convolutions instead of MatMul the phones took 3.6 / 4.4 /
6.8 / 15.1 / 38.3 s (2 threads).

## License and attribution

- Model weights: SpeechBrain, Apache License 2.0 (see `LICENSE`).
- Training data: VoxLingua107 (Valk and Alumäe, 2021), CC BY 4.0.

```bibtex
@inproceedings{valk2021slt,
  title={{VoxLingua107}: a Dataset for Spoken Language Recognition},
  author={J{\"o}rgen Valk and Tanel Alum{\"a}e},
  booktitle={Proc. IEEE SLT Workshop},
  year={2021},
}
@misc{speechbrain,
  title={{SpeechBrain}: A General-Purpose Speech Toolkit},
  author={Mirco Ravanelli and others},
  year={2021},
  eprint={2106.04624},
  archivePrefix={arXiv},
}
```
