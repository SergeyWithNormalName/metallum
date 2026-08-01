# Original User Request

## 2026-07-31T19:16:32Z

# Teamwork Project Prompt — Refine L8 Materials & Water Optics

Working directory: /Users/sergejgenerozov/Documents/Эксперимент с модом/metallum

## Requirements

### R1. Refine Material Classification & Wetness Policy
Update `SurfaceMaterialPolicy.java` to prevent unnatural gloss on snow, leaves, and oxidized copper, and tune wetness response per material kind.

### R2. Add Micro-Puddles & World-Space Moisture Noise
Enhance `AdvancedDirectLightingShaderPatcher.java` shader patching to modulate wetness and roughness with world-position noise for natural puddle formation.

### R3. Implement Roughness-Based Environment Reflection Blurring
Modify environment lookup in `AdvancedDirectLightingShaderPatcher.java` so rough wet surfaces sample blurred environment reflections rather than sharp sky maps.

## Acceptance Criteria

### Visual Quality & Materials
- [ ] Snow and foliage do not exhibit glossy wet specular reflections during rain.
- [ ] Wet paths display non-uniform puddle variation across block boundaries.
- [ ] Rough wet wood/stone surfaces blur environment sky reflections correctly.
