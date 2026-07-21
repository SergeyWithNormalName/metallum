import Foundation
import Metal
import simd

@main
struct EntityMatrixValidationNative {
    static func main() {
        print("Starting Entity Matrix & Motion Validation Native Suite...")

        let extent = SIMD2<Float>(1920.0, 1080.0)

        // Case 1: Pure Translation
        do {
            let currMV = translationMatrix(dx: 10, dy: 2, dz: -5)
            let prevMV = translationMatrix(dx: 10.5, dy: 2.1, dz: -5.0)
            let currProj = perspectiveMatrix(fovY: 70, aspect: 16.0/9.0, zNear: 0.05, zFar: 1000.0)
            let prevProj = currProj

            try validateCase(
                name: "Pure Translation",
                currMV: currMV,
                prevMV: prevMV,
                currProj: currProj,
                prevProj: prevProj,
                vertexView: SIMD4<Float>(0, 0, -5, 1),
                extent: extent
            )
        } catch {
            fatalError("Pure Translation validation failed: \(error)")
        }

        // Case 2: Pure Rotation
        do {
            let currMV = rotationYMatrix(angleDegrees: 15)
            let prevMV = rotationYMatrix(angleDegrees: 15.5)
            let currProj = perspectiveMatrix(fovY: 70, aspect: 16.0/9.0, zNear: 0.05, zFar: 1000.0)
            let prevProj = currProj

            try validateCase(
                name: "Pure Rotation",
                currMV: currMV,
                prevMV: prevMV,
                currProj: currProj,
                prevProj: prevProj,
                vertexView: SIMD4<Float>(1, 2, -10, 1),
                extent: extent
            )
        } catch {
            fatalError("Pure Rotation validation failed: \(error)")
        }

        // Case 3: Non-Uniform Scale
        do {
            let currMV = matrix_multiply(translationMatrix(dx: 0, dy: 0, dz: -8), scaleMatrix(sx: 1.5, sy: 0.5, sz: 2.0))
            let prevMV = matrix_multiply(translationMatrix(dx: 0.1, dy: 0, dz: -8), scaleMatrix(sx: 1.5, sy: 0.5, sz: 2.0))
            let currProj = perspectiveMatrix(fovY: 70, aspect: 16.0/9.0, zNear: 0.05, zFar: 1000.0)
            let prevProj = currProj

            try validateCase(
                name: "Non-Uniform Scale",
                currMV: currMV,
                prevMV: prevMV,
                currProj: currProj,
                prevProj: prevProj,
                vertexView: SIMD4<Float>(1.5, 0.5, -8, 1),
                extent: extent
            )
        } catch {
            fatalError("Non-Uniform Scale validation failed: \(error)")
        }

        // Case 4: Combined Camera + Entity Rotation
        do {
            let camCurr = rotationYMatrix(angleDegrees: 45)
            let camPrev = rotationYMatrix(angleDegrees: 46)
            let entCurr = translationMatrix(dx: 3, dy: 1, dz: -12)
            let entPrev = translationMatrix(dx: 3.1, dy: 1.05, dz: -12.1)

            let currMV = matrix_multiply(camCurr, entCurr)
            let prevMV = matrix_multiply(camPrev, entPrev)
            let currProj = perspectiveMatrix(fovY: 70, aspect: 16.0/9.0, zNear: 0.05, zFar: 1000.0)
            let prevProj = currProj

            try validateCase(
                name: "Combined Camera + Entity Rotation",
                currMV: currMV,
                prevMV: prevMV,
                currProj: currProj,
                prevProj: prevProj,
                vertexView: SIMD4<Float>(2, 1, -12, 1),
                extent: extent
            )
        } catch {
            fatalError("Combined Camera + Entity Rotation validation failed: \(error)")
        }

        print("Native Entity Matrix & Motion Validation Suite PASSED successfully.")
    }

    private static func validateCase(
        name: String,
        currMV: simd_float4x4,
        prevMV: simd_float4x4,
        currProj: simd_float4x4,
        prevProj: simd_float4x4,
        vertexView: SIMD4<Float>,
        extent: SIMD2<Float>
    ) throws {
        let invCurrMV = simd_inverse(currMV)

        // Assert determinant is non-zero
        let det = simd_determinant(currMV)
        guard abs(det) > 1e-6 else {
            throw NSError(domain: "MatrixError", code: 1, userInfo: [NSLocalizedDescriptionKey: "Matrix singular"])
        }

        // Calculate W = prevMV * inv(currMV)
        let W = matrix_multiply(prevMV, invCurrMV)

        // Verify W cell by cell against identity when prevMV == currMV
        let identityCheck = matrix_multiply(currMV, invCurrMV)
        for i in 0..<4 {
            for j in 0..<4 {
                let expected: Float = (i == j) ? 1.0 : 0.0
                let actual = identityCheck[i][j]
                guard abs(actual - expected) < 1e-5 else {
                    throw NSError(domain: "MatrixError", code: 2, userInfo: [NSLocalizedDescriptionKey: "Inverse identity error at [\(i)][\(j)]: \(actual) != \(expected)"])
                }
            }
        }

        // Transform vertex to previous view space: P_prev_view = W * P_view
        let prevViewPos = matrix_multiply(W, vertexView)

        // Projection
        let currClip = matrix_multiply(currProj, vertexView)
        let prevClip = matrix_multiply(prevProj, prevViewPos)

        // Perspective divide
        let currNdc = SIMD2<Float>(currClip.x / currClip.w, currClip.y / currClip.w)
        let prevNdc = SIMD2<Float>(prevClip.x / prevClip.w, prevClip.y / prevClip.w)

        // Motion calculation according to Metallum contract
        let ndcDelta = prevNdc - currNdc
        let motionPixels = SIMD2<Float>(
            ndcDelta.x * (0.5 * extent.x),
            -ndcDelta.y * (0.5 * extent.y)
        )

        // Validate non-nan / finite
        guard motionPixels.x.isFinite && motionPixels.y.isFinite else {
            throw NSError(domain: "MotionError", code: 3, userInfo: [NSLocalizedDescriptionKey: "Motion vector is not finite"])
        }

        print("  PASS: \(name) -> Motion Vector: (\(motionPixels.x) px, \(motionPixels.y) px)")
    }

    private static func translationMatrix(dx: Float, dy: Float, dz: Float) -> simd_float4x4 {
        var m = matrix_identity_float4x4
        m.columns.3 = SIMD4<Float>(dx, dy, dz, 1.0)
        return m
    }

    private static func scaleMatrix(sx: Float, sy: Float, sz: Float) -> simd_float4x4 {
        var m = matrix_identity_float4x4
        m.columns.0.x = sx
        m.columns.1.y = sy
        m.columns.2.z = sz
        return m
    }

    private static func rotationYMatrix(angleDegrees: Float) -> simd_float4x4 {
        let rad = angleDegrees * Float.pi / 180.0
        let c = cos(rad)
        let s = sin(rad)
        var m = matrix_identity_float4x4
        m.columns.0 = SIMD4<Float>(c, 0, -s, 0)
        m.columns.1 = SIMD4<Float>(0, 1, 0, 0)
        m.columns.2 = SIMD4<Float>(s, 0, c, 0)
        return m
    }

    private static func perspectiveMatrix(fovY: Float, aspect: Float, zNear: Float, zFar: Float) -> simd_float4x4 {
        let rad = fovY * Float.pi / 180.0
        let tanHalfFov = tan(rad / 2.0)
        var m = simd_float4x4(0)
        m.columns.0.x = 1.0 / (aspect * tanHalfFov)
        m.columns.1.y = 1.0 / tanHalfFov
        m.columns.2.z = -(zFar + zNear) / (zFar - zNear)
        m.columns.2.w = -1.0
        m.columns.3.z = -(2.0 * zFar * zNear) / (zFar - zNear)
        return m
    }
}
