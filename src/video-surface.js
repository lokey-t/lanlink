import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const surfaceBytes = 4096 * 2048 * 4;
let windows;

// The UI owns this per-window mapping. Only local desktop RPC passes its name.
export function openVideoSurface(name) {
  if (process.platform !== 'win32' || typeof name !== 'string' || !/^Local\\LanLinkVideo-[a-f0-9]{32}$/.test(name)) return null;
  try {
    if (!windows) {
      const koffi = require('koffi');
      const kernel = koffi.load('kernel32.dll');
      windows = {
        view: (address, length) => Buffer.from(koffi.view(address, length)),
        open: kernel.func('void *OpenFileMappingW(uint32_t access, int inherit, str16 name)'),
        map: kernel.func('void *MapViewOfFile(void *handle, uint32_t access, uint32_t high, uint32_t low, size_t size)'),
        unmap: kernel.func('int UnmapViewOfFile(void *address)'),
        close: kernel.func('int CloseHandle(void *handle)'),
      };
    }
    const handle = windows.open(2, 0, name);
    if (!handle) return null;
    let address;
    try { address = windows.map(handle, 2, 0, 0, surfaceBytes); }
    finally { windows.close(handle); }
    if (!address) return null;
    let buffer;
    try { buffer = windows.view(address, surfaceBytes); }
    catch (error) { windows.unmap(address); throw error; }
    return {
      write(frame) {
        if (frame.length > surfaceBytes) throw Error('视频帧超出显示缓冲');
        frame.copy(buffer);
      },
      close() { windows.unmap(address); },
    };
  } catch { return null; }
}
