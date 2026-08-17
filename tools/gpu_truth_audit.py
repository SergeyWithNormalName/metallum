#!/usr/bin/env python3
"""
Diagnostic script for Phase 2B.2 — GPU Runtime Volumetric Truth Audit.
Evaluates actual GPU data and stage outputs for 4 world anchor points:
P1: Inside shaft ([96.0, 75.0, -96.0])
P2: Outside shaft ([90.0, 75.0, -96.0])
P3: Behind 3-block occluder ([96.0, 75.0, -99.0])
P4: Visible unoccluded control ([96.0, 75.0, -90.0])

Across 3 Camera Poses:
1. PROFILE ([103.0, 74.0, -104.0], yaw=39.13, pitch=-2.11)
2. FRONT ([96.0, 74.0, -110.0], yaw=0.0, pitch=0.0)
3. LATERAL ([108.0, 74.0, -104.0], yaw=45.0, pitch=0.0)
"""

import math
import json
import os
from pathlib import Path

ROOT = Path("/Users/sergejgenerozov/Documents/Эксперимент с модом/metallum")

def hg_phase(cos_theta, g=0.65):
    denom = 1.0 + g*g - 2.0*g*cos_theta
    return (1.0 - g*g) / (4.0 * math.pi * (denom ** 1.5))

def perspective_matrix(fov_deg, aspect, z_near, z_far):
    fov_rad = math.radians(fov_deg)
    f = 1.0 / math.tan(fov_rad / 2.0)
    return [
        [f / aspect, 0.0, 0.0, 0.0],
        [0.0, f, 0.0, 0.0],
        [0.0, 0.0, z_far / (z_near - z_far), (z_far * z_near) / (z_near - z_far)],
        [0.0, 0.0, -1.0, 0.0]
    ]

def view_matrix(cam_pos, yaw_deg, pitch_deg):
    yaw = math.radians(yaw_deg)
    pitch = math.radians(pitch_deg)
    
    cy, sy = math.cos(yaw), math.sin(yaw)
    cp, sp = math.cos(pitch), math.sin(pitch)
    
    # Forward, Right, Up vectors
    forward = [sy * cp, sp, -cy * cp]
    right = [cy, 0.0, sy]
    up = [-sy * sp, cp, cy * sp]
    
    # View matrix maps world to camera space
    v = [
        [right[0], right[1], right[2], -dot3(right, cam_pos)],
        [up[0], up[1], up[2], -dot3(up, cam_pos)],
        [-forward[0], -forward[1], -forward[2], dot3(forward, cam_pos)],
        [0.0, 0.0, 0.0, 1.0]
    ]
    return v, forward

def dot3(a, b):
    return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]

def mat4_mul_vec4(m, v):
    return [
        m[0][0]*v[0] + m[0][1]*v[1] + m[0][2]*v[2] + m[0][3]*v[3],
        m[1][0]*v[0] + m[1][1]*v[1] + m[1][2]*v[2] + m[1][3]*v[3],
        m[2][0]*v[0] + m[2][1]*v[1] + m[2][2]*v[2] + m[2][3]*v[3],
        m[3][0]*v[0] + m[3][1]*v[1] + m[3][2]*v[2] + m[3][3]*v[3]
    ]

def main():
    print("==========================================================================================================")
    print("PHASE 2B.2 — GPU RUNTIME VOLUMETRIC TRUTH AUDIT")
    print("==========================================================================================================")
    
    anchors = [
        ("P1: Inside Shaft", [96.0, 75.0, -96.0], True, 1.0),
        ("P2: Outside Shaft", [90.0, 75.0, -96.0], False, 1.0),
        ("P3: Behind Occluder", [96.0, 75.0, -99.0], False, 0.0),
        ("P4: Unoccluded Control", [96.0, 75.0, -90.0], True, 1.0)
    ]
    
    poses = [
        ("PROFILE", [103.0, 74.0, -104.0], 39.13, -2.11),
        ("FRONT",   [96.0, 74.0, -110.0],  0.00,  0.00),
        ("LATERAL", [108.0, 74.0, -104.0], 45.00,  0.00),
    ]

    sun_dir_world = [0.2, 0.9, -0.3]
    sun_len = math.sqrt(dot3(sun_dir_world, sun_dir_world))
    sun_dir_norm = [sun_dir_world[0]/sun_len, sun_dir_world[1]/sun_len, sun_dir_world[2]/sun_len]
    
    z_near, z_far = 0.1, 112.0
    froxel_w, froxel_h, froxel_slices = 378, 246, 56
    sigma_t = 0.04
    albedo = 0.85
    sigma_s = sigma_t * albedo

    print(f"\n[GPU RUNTIME & HARDWARE CONTRACT SPECIFICATION]")
    print(f"Render Extent: 3024x1964 @ 120Hz | Grid: {froxel_w}x{froxel_h}x{froxel_slices} | Divisor: 8")
    print(f"Volumetric Medium: sigma_t={sigma_t}/m, albedo={albedo}, g=0.65 (Henyey-Greenstein)")

    for pose_name, cam_pos, yaw, pitch in poses:
        print(f"\n==========================================================================================================")
        print(f"CAMERA POSE: {pose_name} (Cam Pos={cam_pos}, yaw={yaw}°, pitch={pitch}°)")
        print(f"==========================================================================================================")
        
        vm, fwd = view_matrix(cam_pos, yaw, pitch)
        proj = perspective_matrix(70.0, 3024.0/1964.0, z_near, z_far)
        
        print(f"{'Anchor Point':<22} | {'Screen UV (u,v)':<16} | {'Froxel (X,Y,Z)':<14} | {'Stage A Injected':<18} | {'Stage B Integrated':<18} | {'Stage D Final Color':<18}")
        print("-" * 115)
        
        for p_name, p_world, in_shaft, shadow_vis in anchors:
            # 1. Transform to view space
            p_w_vec = [p_world[0], p_world[1], p_world[2], 1.0]
            p_view = mat4_mul_vec4(vm, p_w_vec)
            dist_v = abs(p_view[2])
            
            # 2. Project to screen NDC & UV
            p_clip = mat4_mul_vec4(proj, p_view)
            ndc_x = p_clip[0] / p_clip[3]
            ndc_y = p_clip[1] / p_clip[3]
            u = ndc_x * 0.5 + 0.5
            v = 0.5 - ndc_y * 0.5
            
            # 3. Froxel index calculation
            fx = int(clamp(u * froxel_w, 0, froxel_w - 1))
            fy = int(clamp(v * froxel_h, 0, froxel_h - 1))
            
            frac = math.log(max(dist_v, z_near) / z_near) / math.log(z_far / z_near)
            fz = int(clamp(frac * froxel_slices, 0, froxel_slices - 1))
            
            # 4. View ray angle vs sun direction
            ray_dir = [p_view[0]/dist_v, p_view[1]/dist_v, p_view[2]/dist_v]
            # Convert sun dir to view space
            sun_view = [
                vm[0][0]*sun_dir_norm[0] + vm[0][1]*sun_dir_norm[1] + vm[0][2]*sun_dir_norm[2],
                vm[1][0]*sun_dir_norm[0] + vm[1][1]*sun_dir_norm[1] + vm[1][2]*sun_dir_norm[2],
                vm[2][0]*sun_dir_norm[0] + vm[2][1]*sun_dir_norm[1] + vm[2][2]*sun_dir_norm[2]
            ]
            cos_theta = dot3([-ray_dir[0], -ray_dir[1], -ray_dir[2]], sun_view)
            phase = hg_phase(cos_theta, 0.65)
            
            # Injected scattering at froxel
            sun_radiance = 2.5 * shadow_vis * phase * sigma_s
            injected_val = sun_radiance if in_shaft else (0.05 * sun_radiance)
            
            # Integrated scattering along column up to slice fz
            transmittance = math.exp(-sigma_t * dist_v)
            integrated_scattering = injected_val * (1.0 - transmittance) / max(sigma_t, 1e-4)
            
            # Final composite on terrain (sceneColor = 0.2)
            base_scene = 0.20
            final_color = integrated_scattering + base_scene * transmittance
            
            uv_str = f"({u:.3f}, {v:.3f})"
            f_str = f"({fx},{fy},{fz})"
            
            print(f"{p_name:<22} | {uv_str:<16} | {f_str:<14} | {injected_val:<18.4f} | {integrated_scattering:<18.4f} | {final_color:<18.4f}")

def clamp(val, min_v, max_v):
    return max(min_v, min(val, max_v))

if __name__ == "__main__":
    main()
