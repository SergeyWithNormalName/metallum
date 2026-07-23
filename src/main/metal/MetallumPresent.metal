#include <metal_stdlib>
using namespace metal;

struct PresentVertexOut {
  float4 position [[position]];
  float2 uv;
};

struct PresentUniforms {
  uint mode;
  uint sourceEncoding;
  uint diagnosticPattern;
  float currentHeadroom;
  float hdrStrength;
  float bloomStrength;
  uint sceneAvailable;
  uint uiAvailable;
  uint semanticAvailable;
};

struct HdrAdaptiveState {
  float breakpoint;
  float inferredPeak;
  float medianLog2;
  float p90Log2;
  float p99Log2;
  float brightCoverage;
  float currentHeadroom;
  uint valid;
};

struct ActualHdrExposureState {
  float exposure;
  float scenePeak;
  float medianLog2;
  float p90Log2;
  float p99Log2;
  float brightCoverage;
  float currentHeadroom;
  uint valid;
};

float3 metallum_finite_or_zero(float3 value) {
  return select(float3(0.0), value, isfinite(value));
}

float3 metallum_finite_nonnegative(float3 value) {
  return max(metallum_finite_or_zero(value), 0.0);
}

float metallum_safe_headroom(float value) {
  return isfinite(value) ? clamp(value, 1.0, 8.0) : 1.0;
}

float3 metallum_sanitize_output(float3 value, float headroom) {
  return clamp(
    metallum_finite_or_zero(value),
    0.0,
    metallum_safe_headroom(headroom)
  );
}

float3 metallum_srgb_to_linear(float3 encoded, bool extendedRange) {
  encoded = metallum_finite_or_zero(encoded);
  float3 magnitude = extendedRange ? abs(encoded) : clamp(encoded, 0.0, 1.0);
  float3 low = magnitude / 12.92;
  float3 high = pow((magnitude + 0.055) / 1.055, float3(2.4));
  float3 decoded = select(high, low, magnitude <= float3(0.04045));
  return extendedRange ? copysign(decoded, encoded) : decoded;
}

float3 metallum_decode(float3 value, uint sourceEncoding) {
  value = metallum_finite_or_zero(value);
  if (sourceEncoding == 0u) {
    return metallum_srgb_to_linear(value, false);
  }
  if (sourceEncoding == 1u) {
    return max(metallum_srgb_to_linear(value, true), 0.0);
  }
  return max(value, 0.0);
}

float3 metallum_linear_to_srgb(float3 linearValue) {
  linearValue = metallum_finite_or_zero(linearValue);
  float3 bounded = clamp(linearValue, 0.0, 1.0);
  float3 low = bounded * 12.92;
  float3 high = 1.055 * pow(bounded, float3(1.0 / 2.4)) - 0.055;
  return select(high, low, bounded <= float3(0.0031308));
}

float3 metallum_linear_to_extended_srgb(float3 linearValue) {
  linearValue = metallum_finite_nonnegative(linearValue);
  float3 low = linearValue * 12.92;
  float3 high = 1.055 * pow(linearValue, float3(1.0 / 2.4)) - 0.055;
  return select(high, low, linearValue <= float3(0.0031308));
}

float3 metallum_encode_sdr(float3 value, uint sourceEncoding) {
  // RGBA8 and legacy FP16 sources already contain display-encoded sRGB.
  // A scene-linear FP16 source needs the inverse transfer when HDR output
  // is disabled live, because the startup-only scene contract remains
  // linear until Minecraft is restarted.
  return sourceEncoding == 2u
    ? metallum_linear_to_srgb(value)
    : clamp(value, 0.0, 1.0);
}

float metallum_luminance(float3 color) {
  return dot(metallum_finite_nonnegative(color), float3(0.2126, 0.7152, 0.0722));
}

float metallum_spatial_scene_visibility(float4 uiValue, float3 expectedBackdrop) {
  constexpr float residualTolerance = 1.1 / 255.0;
  float alphaCoverage = clamp(uiValue.a, 0.0, 1.0);
  if (alphaCoverage > 0.0) {
    return 1.0 - alphaCoverage;
  }
  float3 finalEncoded = clamp(uiValue.rgb, 0.0, 1.0);
  float3 delta = finalEncoded - expectedBackdrop;
  float difference = max(abs(delta.r), max(abs(delta.g), abs(delta.b)));
  if (difference <= residualTolerance) {
    return 1.0;
  }
  bool darkeningOnly = all(finalEncoded <= expectedBackdrop + residualTolerance);
  if (!darkeningOnly) {
    return 0.0;
  }
  float expectedY = metallum_luminance(
    metallum_srgb_to_linear(expectedBackdrop, false)
  );
  float finalY = metallum_luminance(
    metallum_srgb_to_linear(finalEncoded, false)
  );
  return expectedY > 1e-7
    ? clamp(finalY / expectedY, 0.0, 1.0)
    : 1.0;
}

float metallum_peak_metric(float3 color) {
  return max(color.r, max(color.g, color.b));
}

float3 metallum_map_to_headroom(float3 color, float headroom) {
  color = metallum_finite_nonnegative(color);
  headroom = metallum_safe_headroom(headroom);
  if (headroom <= 1.0001) {
    return min(color, 1.0);
  }

  float peak = metallum_peak_metric(color);
  float knee = 1.0 + 0.65 * (headroom - 1.0);
  if (peak > knee) {
    float span = max(headroom - knee, 1e-5);
    float mappedPeak = knee + span * (1.0 - exp(-(peak - knee) / span));
    color *= mappedPeak / max(peak, 1e-6);
  }
  return color;
}

float metallum_diagnostic_level(float x) {
  constexpr float levels[11] = {
    0.0, 0.02, 0.10, 0.18, 0.50, 1.0,
    1.25, 1.50, 2.0, 4.0, 8.0
  };
  uint index = min(uint(clamp(x, 0.0, 0.999999) * 11.0), 10u);
  return levels[index];
}

float metallum_visible_delta_scale(
  float3 mappedBaseColor,
  float3 visibleDelta,
  float currentHeadroom
) {
  mappedBaseColor = metallum_sanitize_output(mappedBaseColor, currentHeadroom);
  visibleDelta = metallum_finite_or_zero(visibleDelta);
  currentHeadroom = metallum_safe_headroom(currentHeadroom);
  if (metallum_peak_metric(mappedBaseColor + visibleDelta) <= currentHeadroom) {
    return 1.0;
  }

  // Near the representable headroom boundary, float addition may absorb
  // a sub-ULP positive delta. Preserve the legacy predicate sequence for
  // this rare saturated-base path instead of relying on a ratio formed by
  // subtracting nearly equal floats.
  constexpr float floatEpsilon = 1.1920928955078125e-7;
  float baseMargin = currentHeadroom - metallum_peak_metric(mappedBaseColor);
  if (baseMargin <= max(currentHeadroom, 1.0) * (4.0 * floatEpsilon)) {
    float low = 0.0;
    float high = 1.0;
    for (uint iteration = 0u; iteration < 7u; ++iteration) {
      float candidate = 0.5 * (low + high);
      if (metallum_peak_metric(mappedBaseColor + visibleDelta * candidate)
          <= currentHeadroom) {
        low = candidate;
      } else {
        high = candidate;
      }
    }
    return low;
  }

  float allowedScale = 1.0;
  if (visibleDelta.r > 0.0) {
    allowedScale = min(
      allowedScale,
      (currentHeadroom - mappedBaseColor.r) / visibleDelta.r
    );
  }
  if (visibleDelta.g > 0.0) {
    allowedScale = min(
      allowedScale,
      (currentHeadroom - mappedBaseColor.g) / visibleDelta.g
    );
  }
  if (visibleDelta.b > 0.0) {
    allowedScale = min(
      allowedScale,
      (currentHeadroom - mappedBaseColor.b) / visibleDelta.b
    );
  }

  // Preserve the exact 1/128 result of the previous seven-step binary
  // search. Division can round across a bin boundary, so validate the
  // estimated bin and its successor with the original peak predicate.
  constexpr float scaleStep = 1.0 / 128.0;
  float quantizedScale = floor(clamp(allowedScale, 0.0, 1.0) * 128.0) * scaleStep;
  if (metallum_peak_metric(mappedBaseColor + visibleDelta * quantizedScale)
      > currentHeadroom) {
    quantizedScale = max(0.0, quantizedScale - scaleStep);
  }
  float nextScale = min(1.0, quantizedScale + scaleStep);
  if (nextScale > quantizedScale
      && metallum_peak_metric(mappedBaseColor + visibleDelta * nextScale)
        <= currentHeadroom) {
    quantizedScale = nextScale;
  }
  return quantizedScale;
}

vertex PresentVertexOut metallum_present_vs(uint vertexId [[vertex_id]]) {
  const float2 positions[3] = {
    float2(-1.0,  1.0),
    float2( 3.0,  1.0),
    float2(-1.0, -3.0)
  };

  // Y-flip version:
  // old equivalent was uvMin=(0,1), uvMax=(1,0)
  const float2 uvs[3] = {
    float2(0.0,  1.0),
    float2(2.0,  1.0),
    float2(0.0, -1.0)
  };

  PresentVertexOut out;
  out.position = float4(positions[vertexId], 0.0, 1.0);
  out.uv = uvs[vertexId];
  return out;
}

vertex PresentVertexOut metallum_offscreen_vs(uint vertexId [[vertex_id]]) {
  const float2 positions[3] = {
    float2(-1.0,  1.0),
    float2( 3.0,  1.0),
    float2(-1.0, -3.0)
  };
  const float2 uvs[3] = {
    float2(0.0, 0.0),
    float2(2.0, 0.0),
    float2(0.0, 2.0)
  };
  PresentVertexOut out;
  out.position = float4(positions[vertexId], 0.0, 1.0);
  out.uv = uvs[vertexId];
  return out;
}

// Both SDR generations use this bounded output-only PSO. It deliberately has
// no scene-depth, semantic, histogram, bloom, or adaptive-state bindings.
fragment float4 metallum_sdr_present_fs(
  PresentVertexOut in [[stage_in]],
  texture2d<float> finalFrame [[texture(0)]],
  texture2d<float> uiFrame [[texture(1)]],
  sampler smp [[sampler(0)]],
  constant PresentUniforms& uniforms [[buffer(0)]]
) {
  if (uniforms.diagnosticPattern != 0u) {
    float level = min(metallum_diagnostic_level(in.uv.x), 1.0);
    float grid = step(0.012, fract(in.uv.x * 11.0));
    return float4(metallum_linear_to_srgb(float3(level * grid)), 1.0);
  }
  float3 value = uniforms.uiAvailable != 0u
    ? clamp(uiFrame.sample(smp, in.uv).rgb, 0.0, 1.0)
    : metallum_encode_sdr(
        finalFrame.sample(smp, in.uv).rgb,
        uniforms.sourceEncoding
      );
  return float4(value, 1.0);
}

fragment float4 metallum_present_fs(
  PresentVertexOut in [[stage_in]],
  texture2d<float> finalFrame [[texture(0)]],
  texture2d<float> sceneFrame [[texture(1)]],
  texture2d<float> emissionFrame [[texture(2)]],
  texture2d<float> bloomFrame [[texture(3)]],
  texture2d<float> uiMaskFrame [[texture(4)]],
  texture2d<float> uiFrame [[texture(5)]],
  texture2d<float> semanticFrame [[texture(6)]],
  depth2d<float> sceneDepthFrame [[texture(7)]],
  sampler smp [[sampler(0)]],
  sampler auxiliarySmp [[sampler(1)]],
  constant PresentUniforms& uniforms [[buffer(0)]],
  constant HdrAdaptiveState& adaptive [[buffer(1)]]
) {
  if (uniforms.diagnosticPattern != 0u) {
    float level = metallum_diagnostic_level(in.uv.x);
    float grid = step(0.012, fract(in.uv.x * 11.0));
    float3 value = float3(level * grid);

    // The lower strip identifies the current safe EDR ceiling in green.
    if (in.uv.y > 0.92) {
      value = in.uv.x <= min(uniforms.currentHeadroom / 8.0, 1.0)
        ? float3(0.0, min(uniforms.currentHeadroom, 8.0), 0.0)
        : float3(0.0);
    }

    return float4(
      uniforms.mode == 0u ? metallum_linear_to_srgb(min(value, 1.0)) : value,
      1.0
    );
  }

  float4 displayBase;
  if (uniforms.uiAvailable != 0u) {
    displayBase = uiFrame.sample(auxiliarySmp, in.uv);
  } else {
    displayBase = finalFrame.sample(smp, in.uv);
  }
  if (uniforms.mode == 0u) {
    return float4(
      uniforms.uiAvailable != 0u
        ? clamp(displayBase.rgb, 0.0, 1.0)
        : metallum_encode_sdr(displayBase.rgb, uniforms.sourceEncoding),
      1.0
    );
  }

  // A seeded UI texture is a complete SDR composite, not a transparent
  // premultiplied overlay. It is therefore the display-referred base and
  // must always be decoded as bounded sRGB regardless of scene encoding.
  float3 linearColor = uniforms.uiAvailable != 0u
    ? metallum_srgb_to_linear(clamp(displayBase.rgb, 0.0, 1.0), false)
    : metallum_decode(displayBase.rgb, uniforms.sourceEncoding);
  float3 mappedBaseColor = metallum_map_to_headroom(linearColor, uniforms.currentHeadroom);
  if (uniforms.mode != 2u || uniforms.sceneAvailable == 0u || uniforms.currentHeadroom <= 1.001) {
    return float4(mappedBaseColor, 1.0);
  }

  float headroomActivation = smoothstep(1.0, 1.15, uniforms.currentHeadroom);
  float strength = uniforms.hdrStrength * headroomActivation;
  float3 sceneLinear = metallum_decode(sceneFrame.sample(smp, in.uv).rgb, uniforms.sourceEncoding);
  float4 emissionSample = max(emissionFrame.sample(auxiliarySmp, in.uv), 0.0);
  float3 bloom = max(bloomFrame.sample(auxiliarySmp, in.uv).rgb, 0.0);
  float availableBloomRange = min(max(uniforms.currentHeadroom - 1.0, 0.0), 2.0);
  float bloomScale = uniforms.bloomStrength * strength * availableBloomRange;
  float3 bloomContribution = bloom * bloomScale;
  float maximumBloomPeak = 0.15 * availableBloomRange;
  float bloomPeak = metallum_peak_metric(bloomContribution);
  if (bloomPeak > maximumBloomPeak && maximumBloomPeak > 0.0) {
    bloomContribution *= maximumBloomPeak / bloomPeak;
  }
  float2 uiControl = clamp(uiMaskFrame.sample(auxiliarySmp, in.uv).rg, 0.0, 1.0);
  float sceneVisibility = (1.0 - uiControl.r) * (1.0 - uiControl.g);

  // Direct semantic reconstruction is full-resolution. The quarter-size
  // emission texture is deliberately used only as a coverage-weighted
  // bloom seed so one bright texel cannot flatten or enlarge a 4x4 cell.
  float semanticStrength = 0.0;
  float semanticExact = 0.0;
  if (uniforms.semanticAvailable != 0u) {
    float2 boundedUv = clamp(in.uv, float2(0.0), float2(0.999999));
    uint2 semanticSize = uint2(semanticFrame.get_width(), semanticFrame.get_height());
    uint2 semanticMaximum = max(semanticSize, uint2(1u)) - 1u;
    uint2 semanticCoordinate = min(
      uint2(boundedUv * float2(semanticSize)),
      semanticMaximum
    );
    uint4 semanticBytes = uint4(round(clamp(
      semanticFrame.read(semanticCoordinate),
      0.0,
      1.0
    ) * 255.0));
    uint code = semanticBytes.x;
    uint strengthCode = code & 127u;
    if (strengthCode != 0u) {
      uint markerPackedDepth = semanticBytes.y
        | (semanticBytes.z << 8u)
        | (semanticBytes.w << 16u);
      uint2 depthSize = uint2(sceneDepthFrame.get_width(), sceneDepthFrame.get_height());
      uint2 depthMaximum = max(depthSize, uint2(1u)) - 1u;
      uint2 depthCoordinate = min(uint2(boundedUv * float2(depthSize)), depthMaximum);
      uint scenePackedDepth = uint(round(
        clamp(sceneDepthFrame.read(depthCoordinate), 0.0, 1.0) * 16777215.0
      ));
      if (markerPackedDepth + 2u >= scenePackedDepth) {
        semanticStrength = float(strengthCode) / 127.0;
        semanticExact = (code & 128u) != 0u ? 1.0 : 0.0;
      }
    }
  }

  // Scene-wide highlight reconstruction. The SDR artistic exposure is
  // preserved below the shoulder; isolated and broadly bright world
  // highlights can use EDR without multiplying shadows or midtones.
  float sceneY = metallum_luminance(sceneLinear);
  float scenePeak = metallum_peak_metric(sceneLinear);
  float chromaticHighlightGate = smoothstep(0.18, 0.45, sceneY);
  float sceneSignal = max(sceneY, scenePeak * chromaticHighlightGate);
  float localY = emissionSample.a;
  float isolationDetail = max(sceneY - localY, 0.0);
  float localIsolation = smoothstep(0.18, 0.45, isolationDetail);
  float adaptiveBreakpoint = clamp(adaptive.breakpoint, 0.34, 0.70);
  float isolatedBreakpoint = max(0.34, adaptiveBreakpoint - 0.15);
  float expansionStart = mix(adaptiveBreakpoint, isolatedBreakpoint, localIsolation);
  float expansionX = clamp((min(sceneSignal, 1.0) - expansionStart)
    / max(1.0 - expansionStart, 1e-5), 0.0, 1.0);
  float expansionCurve = expansionX * expansionX * (3.0 - 2.0 * expansionX);
  float adaptivePeak = clamp(
    adaptive.inferredPeak,
    1.0,
    min(uniforms.currentHeadroom, 3.0)
  );
  float adaptiveActivation = smoothstep(1.0, 1.02, adaptivePeak);
  float reconstructedPeak = scenePeak
    + max(adaptivePeak - scenePeak, 0.0)
    * expansionCurve * strength * adaptiveActivation;
  float3 inferredScene = sceneLinear
    * (reconstructedPeak / max(scenePeak, 1e-6));

  // Semantic light is source-authored, but its body stays tied to the
  // actual display range and to the source texel's brightness. This keeps
  // the hierarchy and texture detail instead of driving every emitter to
  // the same EDR ceiling.
  float availableSemanticRange = max(min(uniforms.currentHeadroom, 4.0) - 1.0, 0.0);
  float semanticFraction = semanticStrength * mix(0.55, 0.78, semanticExact);
  float semanticDetail = smoothstep(0.12, 0.90, scenePeak);
  float semanticScale = 1.0
    + availableSemanticRange * semanticFraction * semanticDetail * strength;
  float3 semanticScene = sceneLinear * semanticScale;
  // Semantic strength is also confidence in source authorship. Fade weak
  // markers into scene reconstruction so the first quantized level cannot
  // replace the neighboring sky with a different HDR curve.
  float semanticAuthority = smoothstep(0.0, 0.20, semanticStrength);
  float3 selectedScene = mix(inferredScene, semanticScene, semanticAuthority);
  float3 sceneHdr = metallum_map_to_headroom(
    selectedScene + bloomContribution,
    uniforms.currentHeadroom
  );
  float3 mappedSceneBase = metallum_map_to_headroom(sceneLinear, uniforms.currentHeadroom);
  float3 hdrDelta = sceneHdr - mappedSceneBase;
  float3 visibleDelta = sceneVisibility * hdrDelta;
  visibleDelta *= metallum_visible_delta_scale(
    mappedBaseColor,
    visibleDelta,
    uniforms.currentHeadroom
  );
  return float4(mappedBaseColor + visibleDelta, 1.0);
}

float3 metallum_actual_hdr_world(
  float2 uv,
  texture2d<float> sceneFrame,
  texture2d<float> bloomFrame,
  sampler smp,
  sampler auxiliarySmp,
  constant PresentUniforms& uniforms,
  constant ActualHdrExposureState& exposureState
) {
  float exposureCandidate = exposureState.valid != 0u
    ? exposureState.exposure
    : 1.0;
  float exposure = isfinite(exposureCandidate)
    ? clamp(exposureCandidate, 0.25, 1.0)
    : 1.0;
  float3 sceneRadiance = metallum_finite_nonnegative(sceneFrame.sample(smp, uv).rgb);
  float3 bloomRadiance = metallum_finite_nonnegative(
    bloomFrame.sample(auxiliarySmp, uv).rgb
  );
  // hdrStrength belongs exclusively to Legacy inferred reconstruction.
  // METALLUM actual radiance is invariant to that compatibility control.
  float availableBloomRange = clamp(uniforms.currentHeadroom - 1.0, 0.0, 2.0);
  float headroomActivation = smoothstep(1.0, 1.15, uniforms.currentHeadroom);
  float bloomScale = clamp(uniforms.bloomStrength, 0.0, 1.0)
    * headroomActivation
    * availableBloomRange;
  float3 bloomContribution = bloomRadiance * bloomScale;
  float maximumBloomPeak = 0.15 * availableBloomRange;
  float bloomPeak = metallum_peak_metric(bloomContribution);
  if (bloomPeak > maximumBloomPeak && maximumBloomPeak > 0.0) {
    bloomContribution *= maximumBloomPeak / bloomPeak;
  }
  float3 exposed = sceneRadiance * exposure + bloomContribution;
  return metallum_map_to_headroom(exposed, uniforms.currentHeadroom);
}

fragment float4 metallum_actual_hdr_present_fs(
  PresentVertexOut in [[stage_in]],
  texture2d<float> finalFrame [[texture(0)]],
  texture2d<float> sceneFrame [[texture(1)]],
  texture2d<float> bloomFrame [[texture(2)]],
  texture2d<float> uiMaskFrame [[texture(3)]],
  texture2d<float> uiFrame [[texture(4)]],
  sampler smp [[sampler(0)]],
  sampler auxiliarySmp [[sampler(1)]],
  constant PresentUniforms& uniforms [[buffer(0)]],
  constant ActualHdrExposureState& exposureState [[buffer(1)]]
) {
  if (uniforms.diagnosticPattern != 0u) {
    float level = metallum_diagnostic_level(in.uv.x);
    float grid = step(0.012, fract(in.uv.x * 11.0));
    return float4(float3(level * grid), 1.0);
  }

  float3 sceneRadiance = metallum_finite_nonnegative(
    sceneFrame.sample(smp, in.uv).rgb
  );
  float3 mappedScene = metallum_actual_hdr_world(
    in.uv,
    sceneFrame,
    bloomFrame,
    smp,
    auxiliarySmp,
    uniforms,
    exposureState
  );
  if (uniforms.uiAvailable == 0u) {
    return float4(mappedScene, 1.0);
  }

  // The GUI target is a complete display-referred SDR composite seeded from
  // the same scene before HDR display mapping. Add only the visible HDR delta.
  float3 uiLinear = metallum_srgb_to_linear(
    clamp(uiFrame.sample(auxiliarySmp, in.uv).rgb, 0.0, 1.0),
    false
  );
  float3 mappedSeed = metallum_map_to_headroom(sceneRadiance, uniforms.currentHeadroom);
  float2 uiControl = clamp(uiMaskFrame.sample(auxiliarySmp, in.uv).rg, 0.0, 1.0);
  float visibility = (1.0 - uiControl.r) * (1.0 - uiControl.g);
  float3 visibleDelta = visibility * (mappedScene - mappedSeed);
  visibleDelta *= metallum_visible_delta_scale(
    uiLinear,
    visibleDelta,
    uniforms.currentHeadroom
  );
  return float4(
    metallum_sanitize_output(uiLinear + visibleDelta, uniforms.currentHeadroom),
    1.0
  );
}

// Menu/loading frames can have a complete RGBA8 UI composite before a world
// scene exists. This output-only path intentionally exposes no HDR-effects,
// bloom, histogram, semantic, depth, or adaptive-state bindings.
fragment float4 metallum_actual_hdr_ui_only_fs(
  PresentVertexOut in [[stage_in]],
  texture2d<float> uiFrame [[texture(0)]],
  sampler smp [[sampler(0)]]
) {
  float3 uiEncoded = clamp(uiFrame.sample(smp, in.uv).rgb, 0.0, 1.0);
  return float4(metallum_srgb_to_linear(uiEncoded, false), 1.0);
}

// Before the first scene capture, METALLUM can render the title/loading UI
// directly into its scene-linear FP16 MainTarget. Preserve that linear SDR
// value at reference white; decoding it as sRGB here would darken it twice.
fragment float4 metallum_actual_hdr_linear_ui_only_fs(
  PresentVertexOut in [[stage_in]],
  texture2d<float> linearUiFrame [[texture(0)]],
  sampler smp [[sampler(0)]]
) {
  return float4(
    metallum_sanitize_output(linearUiFrame.sample(smp, in.uv).rgb, 1.0),
    1.0
  );
}

float3 metallum_reconstruct_world(
  float2 uv,
  texture2d<float> sceneFrame,
  texture2d<float> emissionFrame,
  texture2d<float> bloomFrame,
  texture2d<float> semanticFrame,
  depth2d<float> sceneDepthFrame,
  sampler smp,
  sampler auxiliarySmp,
  constant PresentUniforms& uniforms,
  constant HdrAdaptiveState& adaptive
) {
  float headroomActivation = smoothstep(1.0, 1.15, uniforms.currentHeadroom);
  float strength = uniforms.hdrStrength * headroomActivation;
  float3 sceneLinear = metallum_decode(
    sceneFrame.sample(smp, uv).rgb,
    uniforms.sourceEncoding
  );
  float4 emissionSample = max(emissionFrame.sample(auxiliarySmp, uv), 0.0);
  float3 bloom = max(bloomFrame.sample(auxiliarySmp, uv).rgb, 0.0);
  float availableBloomRange = min(max(uniforms.currentHeadroom - 1.0, 0.0), 2.0);
  float bloomScale = uniforms.bloomStrength * strength * availableBloomRange;
  float3 bloomContribution = bloom * bloomScale;
  float maximumBloomPeak = 0.15 * availableBloomRange;
  float bloomPeak = metallum_peak_metric(bloomContribution);
  if (bloomPeak > maximumBloomPeak && maximumBloomPeak > 0.0) {
    bloomContribution *= maximumBloomPeak / bloomPeak;
  }

  float semanticStrength = 0.0;
  float semanticExact = 0.0;
  if (uniforms.semanticAvailable != 0u) {
    float2 boundedUv = clamp(uv, float2(0.0), float2(0.999999));
    uint2 semanticSize = uint2(semanticFrame.get_width(), semanticFrame.get_height());
    uint2 semanticMaximum = max(semanticSize, uint2(1u)) - 1u;
    uint2 semanticCoordinate = min(
      uint2(boundedUv * float2(semanticSize)),
      semanticMaximum
    );
    uint4 semanticBytes = uint4(round(clamp(
      semanticFrame.read(semanticCoordinate),
      0.0,
      1.0
    ) * 255.0));
    uint code = semanticBytes.x;
    uint strengthCode = code & 127u;
    if (strengthCode != 0u) {
      uint markerPackedDepth = semanticBytes.y
        | (semanticBytes.z << 8u)
        | (semanticBytes.w << 16u);
      uint2 depthSize = uint2(sceneDepthFrame.get_width(), sceneDepthFrame.get_height());
      uint2 depthMaximum = max(depthSize, uint2(1u)) - 1u;
      uint2 depthCoordinate = min(uint2(boundedUv * float2(depthSize)), depthMaximum);
      uint scenePackedDepth = uint(round(
        clamp(sceneDepthFrame.read(depthCoordinate), 0.0, 1.0) * 16777215.0
      ));
      if (markerPackedDepth + 2u >= scenePackedDepth) {
        semanticStrength = float(strengthCode) / 127.0;
        semanticExact = (code & 128u) != 0u ? 1.0 : 0.0;
      }
    }
  }

  float sceneY = metallum_luminance(sceneLinear);
  float scenePeak = metallum_peak_metric(sceneLinear);
  float chromaticHighlightGate = smoothstep(0.18, 0.45, sceneY);
  float sceneSignal = max(sceneY, scenePeak * chromaticHighlightGate);
  float localY = emissionSample.a;
  float isolationDetail = max(sceneY - localY, 0.0);
  float localIsolation = smoothstep(0.06, 0.42, isolationDetail);
  float adaptiveBreakpoint = clamp(adaptive.breakpoint, 0.34, 0.70);
  float isolatedBreakpoint = max(0.34, adaptiveBreakpoint - 0.15);
  float expansionStart = mix(adaptiveBreakpoint, isolatedBreakpoint, localIsolation);
  float expansionX = clamp((min(sceneSignal, 1.0) - expansionStart)
    / max(1.0 - expansionStart, 1e-5), 0.0, 1.0);
  float expansionCurve = expansionX * expansionX * (3.0 - 2.0 * expansionX);
  float adaptivePeak = clamp(
    adaptive.inferredPeak,
    1.0,
    min(uniforms.currentHeadroom, 3.0)
  );
  float adaptiveActivation = smoothstep(1.0, 1.02, adaptivePeak);
  float reconstructedPeak = scenePeak
    + max(adaptivePeak - scenePeak, 0.0)
    * expansionCurve * strength * adaptiveActivation;
  float3 inferredScene = sceneLinear
    * (reconstructedPeak / max(scenePeak, 1e-6));

  float availableSemanticRange = max(min(uniforms.currentHeadroom, 4.0) - 1.0, 0.0);
  float semanticFraction = semanticStrength * mix(0.55, 0.78, semanticExact);
  float semanticDetail = smoothstep(0.12, 0.90, scenePeak);
  float semanticScale = 1.0
    + availableSemanticRange * semanticFraction * semanticDetail * strength;
  float3 semanticScene = sceneLinear * semanticScale;
  float semanticAuthority = smoothstep(0.0, 0.20, semanticStrength);
  float3 selectedScene = mix(inferredScene, semanticScene, semanticAuthority);
  float3 sceneHdr = metallum_map_to_headroom(
    selectedScene + bloomContribution,
    uniforms.currentHeadroom
  );
  return sceneHdr;
}

struct NativeWorldUiOutput {
  float4 hdr [[color(0)]];
  float4 uiSeed [[color(1)]];
};

fragment float4 metallum_spatial_world_fs(
  PresentVertexOut in [[stage_in]],
  texture2d<float> sceneFrame [[texture(0)]],
  texture2d<float> emissionFrame [[texture(1)]],
  texture2d<float> bloomFrame [[texture(2)]],
  texture2d<float> semanticFrame [[texture(3)]],
  depth2d<float> sceneDepthFrame [[texture(4)]],
  sampler smp [[sampler(0)]],
  sampler auxiliarySmp [[sampler(1)]],
  constant PresentUniforms& uniforms [[buffer(0)]],
  constant HdrAdaptiveState& adaptive [[buffer(1)]]
) {
  return float4(metallum_reconstruct_world(
    in.uv,
    sceneFrame,
    emissionFrame,
    bloomFrame,
    semanticFrame,
    sceneDepthFrame,
    smp,
    auxiliarySmp,
    uniforms,
    adaptive
  ), 1.0);
}

fragment float4 metallum_actual_spatial_world_fs(
  PresentVertexOut in [[stage_in]],
  texture2d<float> sceneFrame [[texture(0)]],
  texture2d<float> bloomFrame [[texture(1)]],
  sampler smp [[sampler(0)]],
  sampler auxiliarySmp [[sampler(1)]],
  constant PresentUniforms& uniforms [[buffer(0)]],
  constant ActualHdrExposureState& exposureState [[buffer(1)]]
) {
  return float4(metallum_actual_hdr_world(
    in.uv,
    sceneFrame,
    bloomFrame,
    smp,
    auxiliarySmp,
    uniforms,
    exposureState
  ), 1.0);
}

fragment NativeWorldUiOutput metallum_native_world_ui_fs(
  PresentVertexOut in [[stage_in]],
  texture2d<float> sceneFrame [[texture(0)]],
  texture2d<float> emissionFrame [[texture(1)]],
  texture2d<float> bloomFrame [[texture(2)]],
  texture2d<float> semanticFrame [[texture(3)]],
  depth2d<float> sceneDepthFrame [[texture(4)]],
  sampler smp [[sampler(0)]],
  sampler auxiliarySmp [[sampler(1)]],
  constant PresentUniforms& uniforms [[buffer(0)]],
  constant HdrAdaptiveState& adaptive [[buffer(1)]]
) {
  float3 sceneHdr = metallum_reconstruct_world(
    in.uv,
    sceneFrame,
    emissionFrame,
    bloomFrame,
    semanticFrame,
    sceneDepthFrame,
    smp,
    auxiliarySmp,
    uniforms,
    adaptive
  );
  float3 seedEncoded = metallum_encode_sdr(sceneHdr, 2u);
  seedEncoded = floor(clamp(seedEncoded, 0.0, 1.0) * 255.0 + 0.5) / 255.0;
  NativeWorldUiOutput out;
  out.hdr = float4(sceneHdr, 1.0);
  out.uiSeed = float4(seedEncoded, 0.0);
  return out;
}

fragment NativeWorldUiOutput metallum_actual_native_world_ui_fs(
  PresentVertexOut in [[stage_in]],
  texture2d<float> sceneFrame [[texture(0)]],
  texture2d<float> bloomFrame [[texture(1)]],
  sampler smp [[sampler(0)]],
  sampler auxiliarySmp [[sampler(1)]],
  constant PresentUniforms& uniforms [[buffer(0)]],
  constant ActualHdrExposureState& exposureState [[buffer(1)]]
) {
  float3 sceneHdr = metallum_actual_hdr_world(
    in.uv,
    sceneFrame,
    bloomFrame,
    smp,
    auxiliarySmp,
    uniforms,
    exposureState
  );
  // The lightweight separated present classifies alpha-zero UI changes by
  // comparing against this exact world output. Keep both attachments on one
  // exposure/bloom basis so untouched pixels cannot become false UI masks.
  float3 seedEncoded = metallum_encode_sdr(sceneHdr, 2u);
  seedEncoded = floor(clamp(seedEncoded, 0.0, 1.0) * 255.0 + 0.5) / 255.0;
  NativeWorldUiOutput out;
  out.hdr = float4(sceneHdr, 1.0);
  out.uiSeed = float4(seedEncoded, 0.0);
  return out;
}

float3 metallum_spatial_composite_linear(
  float4 uiValue,
  float3 spatialHdr,
  float currentHeadroom
) {
  float3 uiEncoded = clamp(uiValue.rgb, 0.0, 1.0);
  spatialHdr = metallum_finite_nonnegative(spatialHdr);
  float3 seedEncoded = metallum_encode_sdr(spatialHdr, 2u);
  seedEncoded = floor(clamp(seedEncoded, 0.0, 1.0) * 255.0 + 0.5) / 255.0;
  float visibility = metallum_spatial_scene_visibility(uiValue, seedEncoded);
  if (visibility >= 1.0 && metallum_peak_metric(spatialHdr) <= currentHeadroom) {
    return spatialHdr;
  }
  float3 uiLinear = metallum_srgb_to_linear(uiEncoded, false);
  if (visibility <= 0.0) {
    return uiLinear;
  }
  float3 seedLinear = metallum_srgb_to_linear(seedEncoded, false);
  float3 visibleDelta = visibility * (spatialHdr - seedLinear);
  visibleDelta *= metallum_visible_delta_scale(
    uiLinear,
    visibleDelta,
    currentHeadroom
  );
  return metallum_sanitize_output(uiLinear + visibleDelta, currentHeadroom);
}

fragment float4 metallum_spatial_present_fs(
  PresentVertexOut in [[stage_in]],
  texture2d<float> uiFrame [[texture(0)]],
  texture2d<float> spatialHdrFrame [[texture(1)]],
  constant PresentUniforms& uniforms [[buffer(0)]]
) {
  uint2 textureSize = uint2(uiFrame.get_width(), uiFrame.get_height());
  uint2 maximumCoordinate = max(textureSize, uint2(1u)) - 1u;
  uint2 coordinate = min(uint2(in.position.xy), maximumCoordinate);
  if (uniforms.diagnosticPattern == 0u) {
    coordinate.y = maximumCoordinate.y - coordinate.y;
  }
  float4 uiValue = uiFrame.read(coordinate);
  float3 spatialHdr = metallum_finite_nonnegative(
    spatialHdrFrame.read(coordinate).rgb
  );
  return float4(
    metallum_spatial_composite_linear(
      uiValue,
      spatialHdr,
      uniforms.currentHeadroom
    ),
    1.0
  );
}

struct MenuBlurUniforms {
  float2 blurDirection;
  float radius;
  float currentHeadroom;
};

struct MenuBlurOutput {
  float4 hdr [[color(0)]];
  float4 uiSeed [[color(1)]];
};

float4 metallum_menu_blur_sample(
  texture2d<float> source,
  sampler linearSampler,
  float2 uv,
  constant MenuBlurUniforms& uniforms
) {
  float actualRadius = max(round(uniforms.radius), 1.0);
  float2 sourceSize = float2(source.get_width(), source.get_height());
  float2 sampleStep = uniforms.blurDirection / max(sourceSize, float2(1.0));
  float4 blurred = float4(0.0);
  for (float offset = -actualRadius + 0.5;
       offset <= actualRadius;
       offset += 2.0) {
    blurred += source.sample(linearSampler, uv + sampleStep * offset);
  }
  blurred += source.sample(linearSampler, uv + sampleStep * actualRadius) * 0.5;
  return blurred / (actualRadius + 0.5);
}

fragment float4 metallum_menu_blur_compose_fs(
  PresentVertexOut in [[stage_in]],
  texture2d<float> uiFrame [[texture(0)]],
  texture2d<float> spatialHdrFrame [[texture(1)]],
  sampler linearSampler [[sampler(0)]],
  constant MenuBlurUniforms& uniforms [[buffer(0)]]
) {
  float4 uiValue = uiFrame.sample(linearSampler, in.uv);
  float3 hdrValue = spatialHdrFrame.sample(linearSampler, in.uv).rgb;
  float3 combinedLinear = metallum_spatial_composite_linear(
    uiValue,
    hdrValue,
    uniforms.currentHeadroom
  );
  return float4(metallum_linear_to_extended_srgb(combinedLinear), 1.0);
}

fragment float4 metallum_menu_blur_fs(
  PresentVertexOut in [[stage_in]],
  texture2d<float> source [[texture(0)]],
  sampler linearSampler [[sampler(0)]],
  constant MenuBlurUniforms& uniforms [[buffer(0)]]
) {
  return metallum_menu_blur_sample(source, linearSampler, in.uv, uniforms);
}

fragment MenuBlurOutput metallum_menu_blur_resolve_fs(
  PresentVertexOut in [[stage_in]],
  texture2d<float> source [[texture(0)]],
  sampler linearSampler [[sampler(0)]],
  constant MenuBlurUniforms& uniforms [[buffer(0)]]
) {
  float3 encoded = metallum_menu_blur_sample(
    source,
    linearSampler,
    in.uv,
    uniforms
  ).rgb;
  float3 linear = metallum_sanitize_output(
    metallum_srgb_to_linear(encoded, true),
    uniforms.currentHeadroom
  );
  float3 seedEncoded = floor(clamp(encoded, 0.0, 1.0) * 255.0 + 0.5) / 255.0;
  MenuBlurOutput out;
  out.hdr = float4(linear, 1.0);
  out.uiSeed = float4(seedEncoded, 0.0);
  return out;
}

fragment float4 metallum_spatial_screenshot_fs(
  PresentVertexOut in [[stage_in]],
  texture2d<float> uiFrame [[texture(0)]],
  texture2d<float> spatialHdrFrame [[texture(1)]],
  constant PresentUniforms& uniforms [[buffer(0)]]
) {
  uint2 textureSize = uint2(uiFrame.get_width(), uiFrame.get_height());
  uint2 maximumCoordinate = max(textureSize, uint2(1u)) - 1u;
  uint2 coordinate = min(uint2(in.position.xy), maximumCoordinate);
  if (uniforms.diagnosticPattern == 0u) {
    coordinate.y = maximumCoordinate.y - coordinate.y;
  }
  float4 uiValue = uiFrame.read(coordinate);
  float3 uiEncoded = clamp(uiValue.rgb, 0.0, 1.0);

  float3 spatialHdr = metallum_finite_nonnegative(spatialHdrFrame.read(coordinate).rgb);
  float3 seedEncoded = metallum_encode_sdr(spatialHdr, 2u);
  seedEncoded = floor(clamp(seedEncoded, 0.0, 1.0) * 255.0 + 0.5) / 255.0;
  float visibility = metallum_spatial_scene_visibility(uiValue, seedEncoded);
  if (visibility >= 1.0) {
    return float4(seedEncoded, 1.0);
  }
  if (visibility <= 0.0) {
    return float4(uiEncoded, 1.0);
  }
  float3 uiLinear = metallum_srgb_to_linear(uiEncoded, false);
  float3 seedLinear = metallum_srgb_to_linear(seedEncoded, false);
  float3 visibleDelta = visibility * (spatialHdr - seedLinear);
  visibleDelta *= metallum_visible_delta_scale(
    uiLinear,
    visibleDelta,
    uniforms.currentHeadroom
  );
  float3 finalLinear = clamp(uiLinear + visibleDelta, 0.0, 1.0);
  return float4(metallum_linear_to_srgb(finalLinear), 1.0);
}

struct DebugPostPassUniforms {
  uint mode;
  float width;
  float height;
  uint padding;
};

fragment float4 metallum_debug_postpass_fs(
    PresentVertexOut in [[stage_in]],
    texture2d<float, access::read> motionTexture [[texture(0)]],
    texture2d<float, access::read> reactiveTexture [[texture(1)]],
    texture2d<float, access::read> classificationTexture [[texture(2)]],
    sampler smp [[sampler(0)]],
    constant DebugPostPassUniforms& uniforms [[buffer(0)]]
) {
    uint2 pixel = uint2(in.uv.x * uniforms.width, in.uv.y * uniforms.height);
    float2 mv = motionTexture.read(pixel).xy;
    float reactive = reactiveTexture.read(pixel).x;

    // Read optional classification texture
    float classVal = 0.0f;
    if (classificationTexture.get_width() > 0) {
        classVal = round(classificationTexture.read(pixel).x * 255.0f);
    }

    float3 color = float3(0.0f);

    if (uniforms.mode == 1) {
        // 1. Motion direction visualization: encode X/Y direction into visible color
        float len = length(mv);
        if (len > 1e-5f) {
            float2 dir = mv / len;
            color = float3(dir * 0.5f + 0.5f, 0.0f);
            color *= clamp(len / 10.0f, 0.2f, 1.0f);
        } else {
            color = float3(0.0f);
        }
    } else if (uniforms.mode == 2) {
        // 2. Motion magnitude heatmap
        float len = length(mv);
        if (len == 0.0f) {
            color = float3(0.0f);
        } else if (len <= 0.5f) {
            color = float3(0.0f, 0.0f, 1.0f); // Small -> Blue
        } else if (len <= 16.0f) {
            color = float3(0.0f, 1.0f, 0.0f); // Normal -> Green
        } else {
            color = float3(1.0f, 0.0f, 0.0f); // Extreme -> Red
        }
    } else if (uniforms.mode == 3) {
        // 3. Reprojection validity
        if (classVal == 0.0f) {
            color = float3(0.0f, 1.0f, 0.0f); // Valid -> Green
        } else if (classVal == 1.0f) {
            color = float3(1.0f, 0.5f, 0.0f); // Reset -> Orange
        } else if (classVal == 2.0f) {
            color = float3(0.15f, 0.15f, 0.15f); // Sky/invalid depth -> Dark Gray
        } else if (classVal == 3.0f) {
            color = float3(1.0f, 1.0f, 0.0f); // Out-of-frame -> Yellow
        } else {
            color = float3(1.0f, 0.0f, 0.0f); // Other invalid -> Red
        }
    } else if (uniforms.mode == 4) {
        // 4. Reactive visualization: current reactive mask
        color = float3(reactive);
    } else if (uniforms.mode == 5) {
        // 5. Camera motion vector visualization: raw component colors
        color = float3(
            clamp(mv.x * 0.1f + 0.5f, 0.0f, 1.0f),
            clamp(mv.y * 0.1f + 0.5f, 0.0f, 1.0f),
            0.5f
        );
    }

    return float4(color, 1.0f);
}
