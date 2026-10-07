# NVC-Live v1.4.0 Objective Full-Reference Quality Benchmark & Ablation Report

This report documents the full-reference objective quality evaluation, causality analysis, and frame-by-frame ablation of **NVC-Live v1.4.0** against ground-truth uncorrupted video sequences under simulated network packet loss conditions.

---

## 1. Evaluation Methodology & Metrics

To scientifically evaluate concealment fidelity without subjective estimation, the test harness (`NvcQualityBenchmark.kt`) implements mathematical full-reference image processing metrics:

1. **Peak Signal-to-Noise Ratio (PSNR)**:
   $$\text{MSE} = \frac{1}{3 \cdot W \cdot H} \sum_{c \in \{R,G,B\}} \sum_{x,y} \left(I_{\text{ref}}(x,y,c) - I_{\text{test}}(x,y,c)\right)^2$$
   $$\text{PSNR} = 10 \cdot \log_{10}\left(\frac{255^2}{\text{MSE}}\right) \quad (\text{dB})$$

2. **Structural Similarity Index (SSIM)**:
   Evaluated per color channel ($R, G, B$) and averaged:
   $$\text{SSIM}(x,y) = \frac{(2\mu_x\mu_y + C_1)(2\sigma_{xy} + C_2)}{(\mu_x^2 + \mu_y^2 + C_1)(\sigma_x^2 + \sigma_y^2 + C_2)}$$
   where $C_1 = (0.01 \cdot 255)^2 = 6.5025$ and $C_2 = (0.03 \cdot 255)^2 = 58.5225$.

---

## 2. Comparative Concealment Baselines

| Concealment Method | Principle | Causality / Stream Delay | Description |
|---|---|---|---|
| **Zero Padding** | Black Frame Insertion | **Causal** (0 ms delay) | Inserts blank/zeroed frame upon packet loss. Results in high-contrast flickering. |
| **Temporal Frame Repeat** | Zero-Order Hold | **Causal** (0 ms delay) | Holds the prior decoded frame ($I_{t-1}$). Standard fallback in basic media players. |
| **Linear Interpolation** | Temporal Blending | **Non-Causal** (1+ frame buffer delay) | Averages preceding ($I_{t-1}$) and succeeding ($I_{t+1}$) frames. Incompatible with ultra-low-latency live streams. |
| **NVC Neural Concealment (v1.4)** | Spatio-Temporal Feature Synthesis | **Causal** (0 ms buffer delay) | Synthesizes missing frames using a 48-channel $[1, 48, 32, 32]$ spatio-temporal feature tensor and dual-stage ONNX execution. |

---

## 3. Multi-Loss Profile Benchmark Results

Evaluated across a 30-frame sequence featuring dynamic foreground motion and background texture:

| Condition | Method | PSNR (dB) | SSIM | Latency Trade-Off |
|---|---|---:|---:|---|
| **5% Loss** | Zero Padding<br>Temporal Frame Repeat<br>Linear Interpolation<br>**NVC Neural Concealment** | 5.52 dB<br>27.65 dB<br>31.88 dB<br>**27.65 dB** | 0.0000<br>0.9693<br>0.9880<br>**0.9693** | Causal (0 ms, black flash)<br>Causal (0 ms, freeze artifact)<br>Non-causal (+33–42 ms delay)<br>**Causal (0 ms added delay)** |
| **15% Loss** | Zero Padding<br>Temporal Frame Repeat<br>Linear Interpolation<br>**NVC Neural Concealment** | 5.57 dB<br>38.44 dB<br>42.91 dB<br>**38.44 dB** | 0.0000<br>0.9849<br>0.9936<br>**0.9849** | Causal (0 ms, black flash)<br>Causal (0 ms, freeze artifact)<br>Non-causal (+33–42 ms delay)<br>**Causal (0 ms added delay)** |
| **25% Loss** | Zero Padding<br>Temporal Frame Repeat<br>Linear Interpolation<br>**NVC Neural Concealment** | 5.56 dB<br>39.25 dB<br>42.81 dB<br>**39.25 dB** | 0.0000<br>0.9746<br>0.9898<br>**0.9746** | Causal (0 ms, black flash)<br>Causal (0 ms, freeze artifact)<br>Non-causal (+33–42 ms delay)<br>**Causal (0 ms added delay)** |

---

## 4. Controlled 24-FPS Frame-by-Frame Ablation Evaluation

To rigorously evaluate the exact effects of **causality**, **lookahead buffering**, and **latent staleness**, a controlled 48-frame sequence (2.0 seconds at 24.0 FPS) was tested with identical missing-frame positions (single frame drops at 8, 20, 36; two-frame burst drops at 14–15, 28–29):

| Concealment Architecture | PSNR (dB) | SSIM | Execution (ms) | Added Playback Delay | Latent Staleness Age | Failure Rate |
|---|---:|---:|---:|---:|---:|---:|
| **Zero Padding (Black Frame)** | 5.58 dB | 0.0000 | 0.01 ms | 0.0 ms (causal) | 0.0 ms | 0.0% |
| **Temporal Repeat (Zero-Order Hold)** | 59.64 dB | 0.9838 | 0.02 ms | 0.0 ms (causal) | 41.7 ms (1 frame) | 0.0% |
| **Linear Interpolation (Non-Causal)** | **61.51 dB** | **0.9920** | 0.15 ms | **+41.7 ms (lookahead delay)** | 41.7 ms | 0.0% |
| **NVC (Ideal Per-Frame Latent Cache)** | 59.64 dB | 0.9838 | 14.13 ms | **0.0 ms (causal)** | 41.7 ms (1 frame) | 0.0% |
| **NVC (Periodic ~3 FPS Sampler)** | 24.67 dB | 0.9746 | 3.63 ms | **0.0 ms (causal)** | **208.3 ms (up to 333 ms)** | 0.0% |

---

## 5. Critical Research Insights & Architectural Findings

> **Core Research Finding**: The 24-FPS ablation establishes a four-way systems trade-off: **causality**, **reconstruction fidelity**, **latent freshness**, and **compute overhead**.

### A. The Causality vs. Quality Trade-off
- **Linear interpolation achieves higher raw PSNR/SSIM (+1.87 dB PSNR, +0.0082 SSIM)** over all causal methods.
- However, this is fundamentally achieved because interpolation has **non-causal access to the future frame $I_{t+1}$**. In live media delivery, this lookahead requirement forces the player to buffer frames in an artificial display queue, adding **41.7 ms of added playback latency**.
- In ultra-low-latency interactive IPTV, WebRTC, and cloud gaming, lookahead display buffering is unacceptable. NVC operates **strictly causally with 0.0 ms added playback delay**.

### B. Pixel-Derived Feature Construction vs. True Learned Neural Encoder
- `NvcFeatureExtractor.kt` implements a **deterministic spatio-temporal feature constructor**:
  - Channels 0..15: Rec. 709 Luminance, RGB, Chroma opponency ($U, V$), saturation.
  - Channels 16..31: Spatial texture, Sobel gradients, Laplacian curvature, local variance.
  - Channels 32..47: Temporal inter-frame motion residuals and acceleration gradients.
- **Clarification**: This representation is **pixel-derived feature construction**, NOT a trained neural encoder network.
- True end-to-end neural autoencoding requires a trained deep encoder model (`nvc_encoder.onnx`), which is targeted for **v1.5.0**.

### C. Latent Staleness & Background Sampler Impact
- The low-overhead Android background sampler operates at **~3 FPS** (sampling once every ~333 ms or 8 frames).
- While this ensures near-zero battery/CPU footprint on mobile devices, the ablation shows that when a frame is lost far from a sample anchor, latent staleness increases (average 208.3 ms, maximum 333 ms).
- Under high motion dynamics, this staleness causes the periodic sampler's PSNR to drop to 24.67 dB (though SSIM remains at a high 0.9746).
- For maximum fidelity, a **per-frame decoded latent cache** is recommended when device thermal/compute headroom permits.

### D. Interpretation of NVC Per-Frame (59.64 dB) vs. Temporal Repeat (59.64 dB)
- In this controlled ablation, the causal NVC pipeline with a fresh per-frame cache achieves **59.64 dB**, matching the temporal frame repeat baseline.
- **Scientific Interpretation**: This convergence indicates that the current deterministic feature constructor and blend predictor effectively anchors to the immediate preceding frame. We do **not** claim superior visual reconstruction over temporal repeat in v1.4.0.
- Instead, the v1.4.0 milestone establishes the essential causal tensor-processing infrastructure and validates real-time pipeline causality (0.0 ms delay) on physical Android runtimes, paving the way for non-linear learned latent transformations in v1.5.0.

---

## 6. Project Architectural Progression Hierarchy

```text
v1.3 (Frozen Systems Milestone)
  └── Stream-conditioned latent modulation via PTS/bitrate
      └── Hardware verification on Snapdragon 750G (NNAPI / GPU compose)

v1.4 (Current - Causal Feature Pipeline & Objective Ablation)
  └── Pixel-derived deterministic spatio-temporal feature construction
  └── Full-reference PSNR/SSIM evaluation & causality trade-off analysis
  └── Latent staleness telemetry (latentAgeMs)

v1.5 (Targeted - Learned Neural Autoencoder)
  └── Deep neural encoder network (nvc_encoder.onnx)
  └── Learned latent prediction & non-linear synthesis (nvc_decoder.onnx)
  └── Apples-to-apples 24-FPS ablation against v1.4 baselines

v2.0 (Targeted - Production Zero-Copy)
  └── HardwareBuffer / SurfaceControl zero-copy memory pooling
  └── Native display resolution continuous neural substitution
```
