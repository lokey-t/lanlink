import Foundation
import Darwin

// A kernel lease is shared by every bundle/version using this data directory.
// Never unlink the file: removing a locked inode would let a third process lock
// a replacement file while the original owner is still running.
final class InstanceLease {
    static let reopenNotification = Notification.Name("com.lanlink.native.reopen")
    static let defaultDirectory = NSHomeDirectory() + "/.lanlink"
    private(set) var directory: String
    private var descriptor: Int32 = -1
    var isOwner: Bool { descriptor >= 0 }
    var lockPath: String { directory + "/native-instance.lock" }

    init(dataDirectory: String) {
        directory = URL(fileURLWithPath: dataDirectory).standardizedFileURL.resolvingSymlinksInPath().path
    }

    func acquire() throws -> Bool {
        if isOwner { return true }
        try FileManager.default.createDirectory(atPath: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        directory = URL(fileURLWithPath: directory).resolvingSymlinksInPath().path
        let fd = Darwin.open(lockPath, O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw posixError() }
        guard flock(fd, LOCK_EX | LOCK_NB) == 0 else {
            let code = errno; Darwin.close(fd)
            if code == EWOULDBLOCK || code == EAGAIN { return false }
            throw posixError(code)
        }
        descriptor = fd
        let owner = Data("\(getpid())\n".utf8)
        let written = owner.withUnsafeBytes { bytes -> Int in
            guard ftruncate(fd, 0) == 0 else { return -1 }
            return Darwin.write(fd, bytes.baseAddress, bytes.count)
        }
        guard written == owner.count else { let error = posixError(); release(); throw error }
        return true
    }

    func ownerPID() -> pid_t? {
        guard let handle = FileHandle(forReadingAtPath: lockPath) else { return nil }
        defer { try? handle.close() }
        guard let bytes = try? handle.read(upToCount: 64), let text = String(data: bytes, encoding: .utf8),
              let pid = Int32(text.trimmingCharacters(in: .whitespacesAndNewlines)), pid > 0 else { return nil }
        return pid
    }

    func release() {
        guard descriptor >= 0 else { return }
        flock(descriptor, LOCK_UN); Darwin.close(descriptor); descriptor = -1
    }
    private func posixError(_ code: Int32 = errno) -> NSError {
        NSError(domain: NSPOSIXErrorDomain, code: Int(code), userInfo: [NSLocalizedDescriptionKey: "无法检查 LanLink 的运行状态：\(String(cString: strerror(code)))"])
    }
    deinit { release() }
}
