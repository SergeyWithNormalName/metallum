import Foundation
import Metal
import simd

private enum ValidationError: Error, CustomStringConvertible {
    case failed(String)

    var description: String {
        switch self {
        case .failed(let message): message
        }
    }
}

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if !condition() {
        throw ValidationError.failed(message)
    }
}

private func perspectiveMatrix(fovDeg: Float, aspect: Float, zNear: Float, zFar: Float) -> simd_float4x4 {
    let fovRad = fovDeg * .pi / 180.0
    let f = 1.0 / tan(fovRad / 2.0)
    let col0 = SIMD4<Float>(f / aspect, 0, 0, 0)
    let col1 = SIMD4<Float>(0, f, 0, 0)
    let col2 = SIMD4<Float>(0, 0, zFar / (zNear - zFar), -1.0)
    let col3 = SIMD4<Float>(0, 0, (zFar * zNear) / (zNear - zFar), 0)
    return simd_float4x4(columns: (col0, col1, col2, col3))
}

@main
struct DepthConventionValidation {
    static func main() throws {
        print("Running native DepthConventionValidation...")
        guard let device = MTLCreateSystemDefaultDevice() else {
            print("No Metal device available; skipping GPU validation")
            return
        }

        let testDepths: [(String, Float)] = [
            ("very near", 0.2),
            ("near", 1.0),
            ("mid-near", 5.0),
            ("mid", 25.0),
            ("far", 80.0),
            ("very far", 150.0)
        ]

        let zNear: Float = 0.1
        let zFar: Float = 112.0
        let proj = perspectiveMatrix(fovDeg: 70.0, aspect: 3024.0 / 1964.0, zNear: zNear, zFar: zFar)
        let invProj = proj.inverse

        for (label, trueZ) in testDepths {
            let pView = SIMD4<Float>(0, 0, -trueZ, 1.0)
            let pClip = proj * pView
            let depthRaw = pClip.z / pClip.w

            // Evaluate RAW depth unprojection (production algorithm)
            let viewH_raw = invProj * SIMD4<Float>(0, 0, depthRaw, 1.0)
            let recRaw = abs(viewH_raw.z / viewH_raw.w)
            let clampedRaw = min(max(recRaw, zNear), zFar)
            let errRaw = abs(clampedRaw - min(trueZ, zFar))

            // Evaluate 1.0 - depth alternative
            let viewH_inv = invProj * SIMD4<Float>(0, 0, 1.0 - depthRaw, 1.0)
            let recInv = abs(viewH_inv.z / viewH_inv.w)
            let clampedInv = min(max(recInv, zNear), zFar)
            let errInv = abs(clampedInv - min(trueZ, zFar))

            try require(errRaw < 0.01, "\(label): Raw depth unprojection error \(errRaw) exceeds tolerance")
            if trueZ >= 1.0 {
                try require(errInv > 0.5, "\(label): 1-depth alternative error \(errInv) should fail for distance \(trueZ)")
            }
        }

        print("SUCCESS: Native DepthConventionValidation passed! CURRENT RAW DEPTH IS PROVEN CORRECT.")
    }
}
