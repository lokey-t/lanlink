import AppKit
import CoreMedia
import CoreVideo
import ScreenCaptureKit
import VideoToolbox

final class StreamVideo: NSObject, SCStreamOutput, SCStreamDelegate {
    private static var active: StreamVideo?
    private var stream: SCStream?
    private var encoder: VTCompressionSession?
    private let frameLock = NSLock()
    private let captureQueue = DispatchQueue(label: "lanlink.video.capture")
    private var lastImage: CVPixelBuffer?
    private var lastFrameAt = DispatchTime.now()
    private var frameCount = 0
    private var staticRefresh: DispatchSourceTimer?

    static func run(_ arguments: ArraySlice<String>) -> Never {
        let values = arguments.compactMap { Int32($0) }
        guard values.count == 4, (2...4096).contains(values[0]), (2...4096).contains(values[1]),
              (20...60).contains(values[2]), (500_000...8_000_000).contains(values[3])
        else { exit(1) }
        Task { @MainActor in
            let source = StreamVideo()
            active = source
            do { try await source.start(width: values[0], height: values[1], fps: values[2], bitrate: values[3]) }
            catch { exit(1) }
        }
        dispatchMain()
    }

    private func start(width: Int32, height: Int32, fps: Int32, bitrate: Int32) async throws {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let display = content.displays.first(where: { $0.displayID == CGMainDisplayID() }) else { exit(1) }
        let mode = CGDisplayCopyDisplayMode(display.displayID)
        let refresh = mode?.refreshRate ?? 0
        let captureRate = refresh > 0 ? min(fps, max(1, Int32(refresh.rounded()))) : fps
        let attributes = [
            kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder as String: true,
            kVTVideoEncoderSpecification_EnableLowLatencyRateControl as String: true,
        ] as CFDictionary
        let output: VTCompressionOutputCallback = { context, _, result, _, sample in
                guard let context, result == 0, let sample, CMSampleBufferDataIsReady(sample) else { return }
                Unmanaged<StreamVideo>.fromOpaque(context).takeUnretainedValue().send(sample)
        }
        func createEncoder(_ specification: CFDictionary) -> OSStatus {
            VTCompressionSessionCreate(allocator: nil, width: width, height: height, codecType: kCMVideoCodecType_H264,
                encoderSpecification: specification, imageBufferAttributes: nil, compressedDataAllocator: nil,
                outputCallback: output, refcon: Unmanaged.passUnretained(self).toOpaque(), compressionSessionOut: &self.encoder)
        }
        var status = createEncoder(attributes)
        if status != 0 {
            // Older Intel encoders may lack the low-latency selection mode.
            status = createEncoder([kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder as String: true] as CFDictionary)
        }
        guard status == 0, let encoder else { exit(1) }
        let properties: [String: Any] = [
            kVTCompressionPropertyKey_RealTime as String: true,
            kVTCompressionPropertyKey_AllowFrameReordering as String: false,
            kVTCompressionPropertyKey_AverageBitRate as String: NSNumber(value: bitrate),
            kVTCompressionPropertyKey_ExpectedFrameRate as String: NSNumber(value: captureRate),
            kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration as String: NSNumber(value: 1),
        ]
        guard VTSessionSetProperties(encoder, propertyDictionary: properties as CFDictionary) == 0 else { exit(1) }
        VTSessionSetProperty(encoder, key: kVTCompressionPropertyKey_MaxFrameDelayCount, value: NSNumber(value: 0))
        guard VTCompressionSessionPrepareToEncodeFrames(encoder) == 0 else { exit(1) }
        let config = SCStreamConfiguration()
        config.width = Int(width)
        config.height = Int(height)
        config.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        config.minimumFrameInterval = CMTime(value: 1, timescale: captureRate)
        config.queueDepth = 3
        config.showsCursor = true
        let capture = SCStream(filter: SCContentFilter(display: display, excludingWindows: []),
                               configuration: config, delegate: self)
        stream = capture
        try capture.addStreamOutput(self, type: .screen, sampleHandlerQueue: captureQueue)
        try await capture.startCapture()
        let timer = DispatchSource.makeTimerSource(queue: captureQueue)
        timer.schedule(deadline: .now() + .milliseconds(70), repeating: .milliseconds(70))
        timer.setEventHandler { [weak self] in
            guard let self, let image = self.lastImage,
                  DispatchTime.now().uptimeNanoseconds - self.lastFrameAt.uptimeNanoseconds >
                    (self.frameCount < 3 ? 70_000_000 : 500_000_000) else { return }
            self.encode(image, at: CMClockGetTime(CMClockGetHostTimeClock()))
        }
        staticRefresh = timer
        timer.resume()
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) { exit(1) }

    func stream(_ stream: SCStream, didOutputSampleBuffer sample: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, let pixels = CMSampleBufferGetImageBuffer(sample),
              let attachment = (CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false)
                as? [[SCStreamFrameInfo: Any]])?.first,
              let status = attachment[.status] as? Int, status == SCFrameStatus.complete.rawValue else { return }
        lastImage = pixels
        encode(pixels, at: CMSampleBufferGetPresentationTimeStamp(sample))
    }

    private func encode(_ pixels: CVPixelBuffer, at time: CMTime) {
        guard let encoder else { return }
        lastFrameAt = .now()
        frameCount += 1
        let code = VTCompressionSessionEncodeFrame(encoder, imageBuffer: pixels,
                  presentationTimeStamp: time,
                  duration: .invalid, frameProperties: nil, sourceFrameRefcon: nil, infoFlagsOut: nil)
        if code != 0 { exit(1) }
    }

    private func send(_ sample: CMSampleBuffer) {
        guard let format = CMSampleBufferGetFormatDescription(sample),
              let block = CMSampleBufferGetDataBuffer(sample) else { return }
        let size = CMBlockBufferGetDataLength(block)
        guard size > 0 && size < 4 * 1024 * 1024 else { return }
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false)
            as? [[String: Any]]
        let key = (attachments?.first?[kCMSampleAttachmentKey_NotSync as String] as? Bool) != true
        var parameterCount = 0
        var nalLength: Int32 = 0
        guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: 0,
              parameterSetPointerOut: nil, parameterSetSizeOut: nil,
              parameterSetCountOut: &parameterCount, nalUnitHeaderLengthOut: &nalLength) == 0,
              (1...4).contains(nalLength) else { return }
        let startCode: [UInt8] = [0, 0, 0, 1]
        var payload = Data([key ? 1 : 0, 0, 0, 0, 1, 9, 240])
        if key {
            for index in 0..<parameterCount {
                var bytes: UnsafePointer<UInt8>?
                var count = 0
                guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: index,
                    parameterSetPointerOut: &bytes, parameterSetSizeOut: &count,
                    parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil) == 0, let bytes else { return }
                payload.append(contentsOf: startCode)
                payload.append(bytes, count: count)
            }
        }
        var raw = Data(count: size)
        let copied = raw.withUnsafeMutableBytes { bytes in
            CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: size, destination: bytes.baseAddress!)
        }
        guard copied == 0 else { return }
        var offset = 0
        while offset + Int(nalLength) <= size {
            var count = 0
            for _ in 0..<Int(nalLength) { count = (count << 8) | Int(raw[offset]); offset += 1 }
            guard count > 0 && count <= size - offset else { return }
            payload.append(contentsOf: startCode)
            payload.append(raw[offset..<(offset + count)])
            offset += count
        }
        guard offset == size, payload.count < 4 * 1024 * 1024 else { return }
        var length = UInt32(payload.count).bigEndian
        var packet = withUnsafeBytes(of: &length) { Data($0) }
        packet.append(payload)
        frameLock.lock()
        FileHandle.standardOutput.write(packet)
        frameLock.unlock()
    }
}
