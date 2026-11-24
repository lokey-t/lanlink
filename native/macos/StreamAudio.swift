import AppKit
import AVFoundation
import CoreMedia
import ScreenCaptureKit

enum StreamAudio {
    static let sampleRate = 48_000.0
    static let samplesPerPacket: AVAudioFrameCount = 960
    static let packetBytes = Int(samplesPerPacket) * 4

    static func capture() -> Never {
        Task { @MainActor in
            do {
                let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
                guard let display = content.displays.first(where: { $0.displayID == CGMainDisplayID() }) else { exit(1) }
                let filter = SCContentFilter(display: display, excludingWindows: [])
                let config = SCStreamConfiguration()
                config.capturesAudio = true
                config.excludesCurrentProcessAudio = true
                config.sampleRate = Int(sampleRate)
                config.channelCount = 2
                let source = AudioCapture()
                let stream = SCStream(filter: filter, configuration: config, delegate: source)
                try stream.addStreamOutput(source, type: .audio, sampleHandlerQueue: DispatchQueue(label: "lanlink.audio.capture"))
                try await stream.startCapture()
                source.stream = stream
                activeCapture = source
            } catch {
                exit(1)
            }
        }
        dispatchMain()
    }

    private static var activeCapture: AudioCapture?
    private final class AudioCapture: NSObject, SCStreamOutput, SCStreamDelegate {
        var stream: SCStream?

        func stream(_ stream: SCStream, didStopWithError error: Error) { exit(1) }

        func stream(_ stream: SCStream, didOutputSampleBuffer sample: CMSampleBuffer, of type: SCStreamOutputType) {
            guard type == .audio, sample.isValid, sample.numSamples > 0,
                  let description = CMSampleBufferGetFormatDescription(sample),
                  let streamDescription = CMAudioFormatDescriptionGetStreamBasicDescription(description),
                  let format = AVAudioFormat(streamDescription: streamDescription),
                  format.commonFormat == .pcmFormatFloat32, format.channelCount == 2,
                  format.sampleRate == StreamAudio.sampleRate,
                  let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(sample.numSamples))
            else { return }
            buffer.frameLength = AVAudioFrameCount(sample.numSamples)
            guard CMSampleBufferCopyPCMDataIntoAudioBufferList(sample, at: 0, frameCount: Int32(sample.numSamples),
                                                              into: buffer.mutableAudioBufferList) == 0,
                  let channels = buffer.floatChannelData else { return }
            let stride = format.isInterleaved ? 2 : 1
            let left = channels[0], right = format.isInterleaved ? channels[0].advanced(by: 1) : channels[1]
            var pcm = [Int16]()
            pcm.reserveCapacity(sample.numSamples * 2)
            for index in 0..<sample.numSamples {
                pcm.append(pcm16(left[index * stride]))
                pcm.append(pcm16(right[index * stride]))
            }
            FileHandle.standardOutput.write(pcm.withUnsafeBufferPointer { Data(buffer: $0) })
        }

        private func pcm16(_ value: Float) -> Int16 {
            guard value.isFinite else { return 0 }
            return Int16(min(1, max(-1, value)) * 32767).littleEndian
        }
    }

    static func play() throws {
        let input = FileHandle.standardInput
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 2)!
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        try engine.start()
        defer { player.stop(); engine.stop() }
        let slots = DispatchSemaphore(value: 2)
        while true {
            var data = Data()
            while data.count < packetBytes {
                guard let part = try input.read(upToCount: packetBytes - data.count), !part.isEmpty else { return }
                data.append(part)
            }
            slots.wait()
            let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: samplesPerPacket)!
            buffer.frameLength = samplesPerPacket
            let channels = buffer.floatChannelData!
            data.withUnsafeBytes { bytes in
                for index in 0..<Int(samplesPerPacket) {
                    channels[0][index] = Float(Int16(littleEndian: bytes.loadUnaligned(fromByteOffset: index * 4, as: Int16.self))) / 32768
                    channels[1][index] = Float(Int16(littleEndian: bytes.loadUnaligned(fromByteOffset: index * 4 + 2, as: Int16.self))) / 32768
                }
            }
            player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { _ in slots.signal() }
            if !player.isPlaying { player.play() }
        }
    }
}
