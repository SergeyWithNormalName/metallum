import AppKit
import QuartzCore

@objc public enum MetallumFullscreenState: UInt32 {
    case windowed = 0
    case entering = 1
    case fullscreen = 2
    case exiting = 3
}

public final class MetallumFullscreenCoordinator {
    public weak var window: NSWindow?
    public weak var layer: CAMetalLayer?

    private(set) var state: MetallumFullscreenState = .windowed
    private(set) var generation: UInt64 = 1

    private var requestedFullscreen: Bool?
    private var observerTokens: [NSObjectProtocol] = []
    private var lastAppliedDrawableSize: CGSize?

    public init(window: NSWindow, layer: CAMetalLayer) {
        self.window = window
        self.layer = layer

        installObservers()
        updateStateFromWindow()
        updateDrawableSize()
    }

    deinit {
        let center = NotificationCenter.default
        observerTokens.forEach(center.removeObserver)
        observerTokens.removeAll()
    }

    private func installObservers() {
        guard let window else { return }
        let center = NotificationCenter.default

        observe(center, name: NSWindow.willEnterFullScreenNotification, window: window) { [weak self] in
            self?.beginTransition(to: .entering)
        }

        observe(center, name: NSWindow.didEnterFullScreenNotification, window: window) { [weak self] in
            self?.finishTransition(fullscreen: true)
        }

        observe(center, name: NSWindow.willExitFullScreenNotification, window: window) { [weak self] in
            self?.beginTransition(to: .exiting)
        }

        observe(center, name: NSWindow.didExitFullScreenNotification, window: window) { [weak self] in
            self?.finishTransition(fullscreen: false)
        }

        observe(center, name: NSWindow.didResizeNotification, window: window) { [weak self] in
            self?.updateDrawableSize()
        }

        observe(center, name: NSWindow.didChangeBackingPropertiesNotification, window: window) { [weak self] in
            self?.handleBackingChange()
        }

        observe(center, name: NSWindow.didChangeScreenNotification, window: window) { [weak self] in
            self?.handleScreenChange()
        }

        observe(center, name: NSWindow.didChangeScreenProfileNotification, window: window) { [weak self] in
            self?.handleScreenChange()
        }
    }

    private func observe(
        _ center: NotificationCenter,
        name: Notification.Name,
        window: NSWindow,
        action: @escaping () -> Void
    ) {
        let token = center.addObserver(forName: name, object: window, queue: .main) { _ in
            action()
        }
        observerTokens.append(token)
    }

    private func updateStateFromWindow() {
        guard let window else { return }
        let isFs = window.styleMask.contains(.fullScreen)
        state = isFs ? .fullscreen : .windowed
    }

    public func toggleFullscreen() {
        DispatchQueue.main.async { [weak self] in
            guard let self, let window = self.window else { return }

            guard self.state != .entering && self.state != .exiting else {
                self.requestedFullscreen = !window.styleMask.contains(.fullScreen)
                return
            }

            window.toggleFullScreen(nil)
        }
    }

    public func setFullscreen(_ enabled: Bool) {
        DispatchQueue.main.async { [weak self] in
            guard let self, let window = self.window else { return }

            let actual = window.styleMask.contains(.fullScreen)

            if self.state == .entering || self.state == .exiting {
                self.requestedFullscreen = enabled
                return
            }

            guard actual != enabled else { return }

            self.requestedFullscreen = enabled
            window.toggleFullScreen(nil)
        }
    }

    private func beginTransition(to newState: MetallumFullscreenState) {
        state = newState
        generation &+= 1
        if let window {
            MetallumExtendedProMotionSchedulerRegistry.shared.update(window: window)
        }
    }

    private func finishTransition(fullscreen: Bool) {
        state = fullscreen ? .fullscreen : .windowed
        generation &+= 1

        updateDrawableSize()
        processDeferredRequest()
    }

    private func handleBackingChange() {
        generation &+= 1
        updateDrawableSize()
    }

    private func handleScreenChange() {
        generation &+= 1
        updateDrawableSize()
    }

    public func updateDrawableSize() {
        guard let window, let view = window.contentView, let layer else { return }

        let backingRect = view.convertToBacking(view.bounds)
        let width = max(backingRect.width, 1.0)
        let height = max(backingRect.height, 1.0)

        let nextSize = CGSize(width: width, height: height)
        let changed = lastAppliedDrawableSize.map {
            abs($0.width - nextSize.width) >= 0.5 || abs($0.height - nextSize.height) >= 0.5
        } ?? true
        if changed {
            // Admission closes and the old fixed-size generation drains.  It
            // cannot interpolate across a backing/monitor size transition.
            metallumInvalidateFrameInterpolationForLayerMutation(layer)
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            layer.drawableSize = nextSize
            CATransaction.commit()
            lastAppliedDrawableSize = nextSize
        }
        MetallumExtendedProMotionSchedulerRegistry.shared.update(window: window)
    }

    private func processDeferredRequest() {
        guard let req = requestedFullscreen else { return }
        requestedFullscreen = nil
        let actual = window?.styleMask.contains(.fullScreen) ?? false
        if req != actual {
            setFullscreen(req)
        }
    }

    public func encodedSnapshot() -> UInt64 {
        let actualFs = window?.styleMask.contains(.fullScreen) ?? false
        let isTrans = (state == .entering || state == .exiting)
        var val: UInt64 = UInt64(state.rawValue & 0x0F)
        if actualFs { val |= (1 << 4) }
        if isTrans { val |= (1 << 5) }
        val |= ((generation & 0x00FF_FFFF_FFFF_FFFF) << 8)
        return val
    }
}
